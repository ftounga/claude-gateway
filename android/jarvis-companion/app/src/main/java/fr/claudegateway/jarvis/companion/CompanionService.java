package fr.claudegateway.jarvis.companion;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import fr.claudegateway.jarvis.companion.core.Backoff;
import fr.claudegateway.jarvis.companion.core.BatteryPolicy;
import fr.claudegateway.jarvis.companion.core.CompanionApi;
import fr.claudegateway.jarvis.companion.core.ReplyWindow;
import fr.claudegateway.jarvis.companion.core.WakeEngine;
import fr.claudegateway.jarvis.companion.core.WakeListener;
import fr.claudegateway.jarvis.companion.core.WakeProfile;

/**
 * <b>« Jarvis écoute »</b> — le service de premier plan de type micro (F-232 / SF-232-01, D1, D3).
 *
 * <p>Il lit le micro à 16 kHz, découpe les mots isolés et compare chacun aux gabarits appris, <b>sur
 * l'appareil</b>. Rien n'est enregistré, rien du micro ne sort. Au mot, il relâche le micro (la PWA en aura
 * besoin pour la dictée) et réveille l'écran Jarvis par une notification à intention plein écran ; l'écoute
 * reprend quand l'écran s'éteint, ou au plus tard {@link #RESUME_AFTER_WAKE_MS} après le mot.</p>
 *
 * <p><b>SF-232-03 — il parle aussi téléphone verrouillé (D1b).</b> Appairé, il attend les annonces de la
 * gateway (attente longue, jeton dédié) : fin d'un tour né à la voix, alerte de veille. Il relâche le micro, dit
 * l'annonce avec la synthèse vocale de l'appareil, puis ouvre une <b>fenêtre de réponse</b>
 * ({@link ReplyWindow#DURATION_MS}) : le premier mot entendu, sans « Jarvis », ouvre l'écran Jarvis sur le lien
 * de l'annonce. Jeton révoqué ⇒ il l'oublie et le dit.</p>
 */
public final class CompanionService extends Service {

    static final String ACTION_START = "fr.claudegateway.jarvis.companion.START";
    static final String ACTION_PAUSE = "fr.claudegateway.jarvis.companion.PAUSE";
    static final String ACTION_RESUME = "fr.claudegateway.jarvis.companion.RESUME";
    static final String ACTION_QUIT = "fr.claudegateway.jarvis.companion.QUIT";

    static final String CHANNEL_LISTEN = "jarvis_listen";
    static final String CHANNEL_WAKE = "jarvis_wake";
    static final String CHANNEL_ANNOUNCE = "jarvis_announce";
    private static final int ID_LISTEN = 1;
    private static final int ID_WAKE = 2;
    private static final int ID_ANNOUNCE = 3;

    private static final String TAG = "JarvisCompanion";
    private static final long RESUME_AFTER_WAKE_MS = 10 * 60 * 1000L;
    private static final long RETRY_MIC_MS = 30 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final BatteryPolicy battery = new BatteryPolicy();
    private final ReplyWindow reply = new ReplyWindow();
    private Prefs prefs;
    private volatile Thread capture;
    private volatile boolean capturing;
    /** Vrai entre le mot et la reprise : le micro est rendu à la PWA. */
    private boolean awaitingResume;
    /** Vrai pendant qu'une annonce est dite : le micro est relâché pour ne pas s'entendre. */
    private boolean speaking;
    private String status = "";

    /** La boucle des annonces : une génération par démarrage, l'ancienne s'éteint d'elle-même. */
    private volatile int feedGeneration;
    private volatile boolean feeding;
    private TextToSpeech tts;
    private volatile boolean ttsReady;

    private final Runnable resumeAfterWake = this::resumeAfterWake;
    private final Runnable retryMic = this::refresh;
    private final Runnable closeReply = this::closeReply;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onBattery(intent);
        }
    };

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (awaitingResume) {
                resumeAfterWake();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        createChannels(this);
        Intent sticky = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) {
            onBattery(sticky);
        }
        registerReceiver(screenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        tts = new TextToSpeech(this, code -> {
            if (code == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.FRANCE);
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        // Rien : le micro est déjà relâché.
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        main.post(() -> afterSpeech(utteranceId));
                    }

                    @Override
                    public void onError(String utteranceId) {
                        main.post(() -> afterSpeech(null));
                    }
                });
                ttsReady = true;
            } else {
                Log.w(TAG, "Synthèse vocale indisponible : les annonces restent en notification");
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        // Toujours passer au premier plan d'abord : Android l'exige dans les secondes qui suivent.
        goForeground();
        if (ACTION_QUIT.equals(action)) {
            prefs.setListening(false);
            stopCapture();
            stopFeed();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            prefs.setListening(false);
        } else if (intent != null) {
            prefs.setListening(true);
        }
        // intent == null : relance par le système (START_STICKY) — on garde l'interrupteur tel quel,
        // une pause voulue par le PO ne doit pas être levée dans son dos.
        awaitingResume = false;
        main.removeCallbacks(resumeAfterWake);
        refresh();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCapture();
        stopFeed();
        main.removeCallbacksAndMessages(null);
        unregisterReceiver(batteryReceiver);
        unregisterReceiver(screenOffReceiver);
        if (tts != null) {
            tts.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ état

    /** Recalcule ce qui doit tourner : interrupteur, batterie, gabarits, mot en cours, annonce en cours. */
    private void refresh() {
        main.removeCallbacks(retryMic);
        WakeProfile profile = prefs.profile();
        boolean active = prefs.listening() && !battery.pausedForBattery();
        boolean wanted = active && !awaitingResume && !speaking && profile != null;
        if (wanted && !capturing) {
            startCapture(profile);
        } else if (!wanted && capturing) {
            stopCapture();
        }
        // Les annonces : appairé et actif (pas en pause, pas en batterie faible).
        boolean feedWanted = active && prefs.token() != null;
        if (feedWanted && !feeding) {
            startFeed();
        } else if (!feedWanted && feeding) {
            stopFeed();
        }
        if (!prefs.listening()) {
            status = "En pause";
        } else if (profile == null) {
            status = "Apprends d'abord « Jarvis » dans l'application";
        } else if (battery.pausedForBattery()) {
            status = "En pause : batterie faible (reprise à " + BatteryPolicy.RESUME_FROM_PERCENT + " % ou en charge)";
        } else if (awaitingResume) {
            status = "Jarvis est ouvert";
        } else if (speaking) {
            status = "Jarvis parle";
        } else if (capturing) {
            status = reply.isOpen(SystemClock.elapsedRealtime()) ? "Jarvis attend ta réponse" : "Jarvis écoute";
        }
        notifyListen();
    }

    private void onBattery(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int percent = level >= 0 && scale > 0 ? level * 100 / scale : -1;
        boolean before = battery.pausedForBattery();
        battery.update(percent, plugged != 0);
        if (prefs != null && before != battery.pausedForBattery()) {
            refresh();
        }
    }

    /** Un mot vient d'être entendu (fil principal) : réponse à une annonce, ou peut-être « Jarvis ». */
    private void onWord(double[][] features, WakeProfile profile) {
        String link = reply.onWord(SystemClock.elapsedRealtime());
        if (link != null) {
            main.removeCallbacks(closeReply);
            Log.i(TAG, "Réponse à une annonce");
            openJarvis(link);
        } else if (WakeEngine.matches(features, profile)) {
            Log.i(TAG, "Mot d'éveil reconnu");
            openJarvis(null);
        }
    }

    /** Rend le micro à la PWA et ouvre Jarvis — sur le lien d'une annonce, ou l'écran Jarvis. */
    private void openJarvis(String path) {
        awaitingResume = true;
        reply.close();
        stopCapture();
        main.removeCallbacks(resumeAfterWake);
        main.postDelayed(resumeAfterWake, RESUME_AFTER_WAKE_MS);
        refresh();
        showWake(path);
    }

    private void resumeAfterWake() {
        main.removeCallbacks(resumeAfterWake);
        awaitingResume = false;
        getSystemService(NotificationManager.class).cancel(ID_WAKE);
        refresh();
    }

    // ------------------------------------------------------------------ annonces (SF-232-03)

    private void startFeed() {
        String token = prefs.token();
        if (token == null) {
            return;
        }
        feeding = true;
        final int generation = ++feedGeneration;
        Thread thread = new Thread(() -> feedLoop(generation, token), "jarvis-announcements");
        thread.setDaemon(true);
        thread.start();
    }

    private void stopFeed() {
        feeding = false;
        // Une attente longue en cours ne s'interrompt pas : sa réponse sera ignorée (génération dépassée).
        feedGeneration++;
    }

    private boolean current(int generation) {
        return feeding && generation == feedGeneration;
    }

    private void feedLoop(int generation, String token) {
        Backoff backoff = new Backoff();
        while (current(generation)) {
            try {
                CompanionApi.Feed feed = CompanionApi.announcements(prefs.gateway(), token, prefs.cursor());
                backoff.reset();
                if (!current(generation)) {
                    return;
                }
                if (feed.cursor != null) {
                    prefs.setCursor(feed.cursor);
                }
                if (!feed.items.isEmpty()) {
                    List<CompanionApi.Announcement> items = feed.items;
                    main.post(() -> announce(items));
                }
            } catch (CompanionApi.Unauthorized e) {
                main.post(this::onRevoked);
                return;
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Annonces indisponibles : " + e.getClass().getSimpleName());
                SystemClock.sleep(backoff.next());
            }
        }
    }

    private void onRevoked() {
        prefs.clearPairing();
        stopFeed();
        notifyAnnouncement("Appairage révoqué", "Ce téléphone ne reçoit plus les annonces. Réappaire-le depuis Jarvis.",
                null);
        refresh();
    }

    /** Dit les annonces reçues (fil principal). L'écran Jarvis ouvert les dit déjà lui-même : on se tait. */
    private void announce(List<CompanionApi.Announcement> items) {
        if (awaitingResume || !prefs.listening()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (CompanionApi.Announcement a : items) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(a.text);
        }
        String link = items.get(items.size() - 1).link;
        notifyAnnouncement("Jarvis", text.toString(), link);
        if (!ttsReady) {
            return;
        }
        speaking = true;
        reply.close();
        main.removeCallbacks(closeReply);
        refresh();
        int queued = tts.speak(text.toString(), TextToSpeech.QUEUE_FLUSH, null, link == null ? "" : link);
        if (queued != TextToSpeech.SUCCESS) {
            afterSpeech(null);
        }
    }

    /** L'annonce est dite : la réponse s'écoute quelques secondes, sans mot d'éveil. */
    private void afterSpeech(String link) {
        if (!speaking) {
            return;
        }
        speaking = false;
        if (link != null && !awaitingResume) {
            reply.open(SystemClock.elapsedRealtime(), link.isEmpty() ? "/jarvis" : link);
            main.postDelayed(closeReply, ReplyWindow.DURATION_MS);
        }
        refresh();
    }

    private void closeReply() {
        reply.close();
        refresh();
    }

    // ------------------------------------------------------------------ micro

    private void startCapture(WakeProfile profile) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Autorise le micro dans l'application";
            return;
        }
        int min = AudioRecord.getMinBufferSize(WakeEngine.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, WakeEngine.RATE / 5 * 2);
        AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeEngine.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
        } catch (RuntimeException e) {
            micUnavailable(e);
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            micUnavailable(null);
            return;
        }
        capturing = true;
        WakeListener listener = new WakeListener(features -> {
            if (features.length >= WakeEngine.MIN_WORD_FRAMES) {
                main.post(() -> onWord(features, profile));
            }
        });
        Thread thread = new Thread(() -> {
            short[] pcm = new short[WakeEngine.RATE / 10];
            try {
                record.startRecording();
                while (capturing) {
                    int n = record.read(pcm, 0, pcm.length);
                    if (n > 0) {
                        listener.push(pcm, n);
                    } else if (n < 0) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Lecture du micro interrompue", e);
            } finally {
                try {
                    record.stop();
                } catch (RuntimeException ignored) {
                    // Déjà arrêté.
                }
                record.release();
            }
        }, "jarvis-wake");
        capture = thread;
        thread.start();
    }

    private void micUnavailable(Exception cause) {
        Log.w(TAG, "Micro indisponible", cause);
        capturing = false;
        status = "Micro indisponible — nouvel essai dans 30 s";
        main.postDelayed(retryMic, RETRY_MIC_MS);
    }

    private void stopCapture() {
        capturing = false;
        Thread thread = capture;
        capture = null;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ notifications

    static void createChannels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel listen = new NotificationChannel(CHANNEL_LISTEN, "Jarvis écoute",
                NotificationManager.IMPORTANCE_LOW);
        listen.setDescription("Notification permanente tant que le mot d'éveil est écouté.");
        listen.setShowBadge(false);
        NotificationChannel wake = new NotificationChannel(CHANNEL_WAKE, "Jarvis t'écoute",
                NotificationManager.IMPORTANCE_HIGH);
        wake.setDescription("Ouvre Jarvis quand le mot d'éveil est reconnu.");
        wake.setSound(null, null);
        NotificationChannel announce = new NotificationChannel(CHANNEL_ANNOUNCE, "Annonces de Jarvis",
                NotificationManager.IMPORTANCE_DEFAULT);
        announce.setDescription("Ce que Jarvis vient de dire à voix haute : fin d'une demande, alerte de veille.");
        // Silencieuse : l'annonce est déjà dite.
        announce.setSound(null, null);
        nm.createNotificationChannel(listen);
        nm.createNotificationChannel(wake);
        nm.createNotificationChannel(announce);
    }

    private void goForeground() {
        Notification n = buildListen();
        // Le type « micro » n'existe qu'à partir d'Android 11 (API 30) : le passer sur Android 10 lèverait.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(ID_LISTEN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(ID_LISTEN, n);
        }
    }

    private void notifyListen() {
        getSystemService(NotificationManager.class).notify(ID_LISTEN, buildListen());
    }

    private Notification buildListen() {
        boolean on = prefs != null && prefs.listening();
        Notification.Builder b = new Notification.Builder(this, CHANNEL_LISTEN)
                .setSmallIcon(R.drawable.ic_jarvis)
                .setContentTitle(status.isEmpty() ? "Jarvis écoute" : status)
                .setContentText("Dis « Jarvis » — rien du micro ne sort du téléphone.")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(activity(new Intent(this, MainActivity.class), 10));
        if (on) {
            b.addAction(new Notification.Action.Builder(null, "Pause", service(ACTION_PAUSE, 11)).build());
        } else {
            b.addAction(new Notification.Action.Builder(null, "Reprendre", service(ACTION_RESUME, 12)).build());
        }
        b.addAction(new Notification.Action.Builder(null, "Quitter", service(ACTION_QUIT, 13)).build());
        return b.build();
    }

    /** L'annonce, aussi en notification : trace lisible, et repli quand la synthèse vocale manque. */
    private void notifyAnnouncement(String title, String text, String path) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ANNOUNCE)
                .setSmallIcon(R.drawable.ic_jarvis)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(activity(wakeIntent(path), 30));
        getSystemService(NotificationManager.class).notify(ID_ANNOUNCE, b.build());
    }

    private Intent wakeIntent(String path) {
        Intent intent = new Intent(this, WakeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        if (path != null) {
            intent.putExtra(WakeActivity.EXTRA_PATH, path);
        }
        return intent;
    }

    /**
     * Le réveil de l'écran Jarvis. Intention plein écran : téléphone verrouillé, elle allume l'écran et
     * lance {@link WakeActivity} ; téléphone en main, Android l'affiche en bannière, qu'un toucher ouvre.
     * Sans le droit « plein écran » (Android 14+), il reste la bannière.
     */
    private void showWake(String path) {
        PendingIntent open = activity(wakeIntent(path), 20);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_WAKE)
                .setSmallIcon(R.drawable.ic_jarvis)
                .setContentTitle("Jarvis t'écoute")
                .setContentText("Toucher pour ouvrir Jarvis")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .setContentIntent(open);
        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                || nm.canUseFullScreenIntent();
        if (fullScreen) {
            b.setFullScreenIntent(open, true);
        }
        nm.notify(ID_WAKE, b.build());
    }

    private PendingIntent activity(Intent intent, int code) {
        return PendingIntent.getActivity(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent service(String action, int code) {
        Intent intent = new Intent(this, CompanionService.class).setAction(action);
        return PendingIntent.getService(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
