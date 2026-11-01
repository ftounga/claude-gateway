package fr.claudegateway.jarvis.companion;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.KeyguardManager;
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
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import fr.claudegateway.jarvis.companion.core.AnnouncementPolicy;
import fr.claudegateway.jarvis.companion.core.SpeechInterruptPolicy;

import fr.claudegateway.jarvis.companion.core.BatteryPolicy;
import fr.claudegateway.jarvis.companion.core.WakeEngine;
import fr.claudegateway.jarvis.companion.core.WakeListener;
import fr.claudegateway.jarvis.companion.core.WakeProfile;

/**
 * <b>« Jarvis écoute »</b> — le service de premier plan de type micro (F-232 / SF-232-01, D1, D3).
 *
 * <p>Il lit le micro à 16 kHz, découpe les mots isolés et compare chacun aux gabarits appris, <b>sur
 * l'appareil</b>. Rien n'est enregistré, rien de ce qu'il entend ne sort (le réseau ne sert qu'aux annonces).
 * Au mot, il relâche le micro (la PWA en aura besoin pour la dictée) et réveille l'écran Jarvis par une
 * notification à intention plein écran ; l'écoute reprend quand l'écran s'éteint, ou au plus tard
 * {@link #RESUME_AFTER_WAKE_MS} après le mot.</p>
 *
 * <p><b>SF-232-03 — les annonces téléphone verrouillé (D1b).</b> Appairé, il relève toutes les 20 s les annonces de
 * la gateway (fins de tour lancés via Jarvis, alertes de veille) avec son jeton dédié ; téléphone verrouillé ou écran
 * éteint, il les <b>dit à voix haute</b>, puis écoute {@link AnnouncementPolicy#REPLY_WINDOW_MS} ms : le premier mot
 * entendu ouvre Jarvis, sans mot d'éveil. En pause ou batterie faible, il ne relève rien.</p>
 */
public final class CompanionService extends Service {

    static final String ACTION_START = "fr.claudegateway.jarvis.companion.START";
    static final String ACTION_PAUSE = "fr.claudegateway.jarvis.companion.PAUSE";
    static final String ACTION_RESUME = "fr.claudegateway.jarvis.companion.RESUME";
    static final String ACTION_QUIT = "fr.claudegateway.jarvis.companion.QUIT";

    static final String CHANNEL_LISTEN = "jarvis_listen";
    static final String CHANNEL_WAKE = "jarvis_wake";
    private static final int ID_LISTEN = 1;
    private static final int ID_WAKE = 2;

    private static final String TAG = "JarvisCompanion";
    private static final long RESUME_AFTER_WAKE_MS = 10 * 60 * 1000L;
    private static final long RETRY_MIC_MS = 30 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final BatteryPolicy battery = new BatteryPolicy();
    private Prefs prefs;
    private volatile Thread capture;
    private volatile boolean capturing;
    /** Vrai entre le mot et la reprise : le micro est rendu à la PWA. */
    private boolean awaitingResume;
    private String status = "";

    private final Runnable resumeAfterWake = this::resumeAfterWake;
    private final Runnable retryMic = this::refresh;

    // ---- SF-232-03 : les annonces ----
    private static final String LAST_UTTERANCE = "jarvis-last";
    private final AnnouncementPolicy announcer = new AnnouncementPolicy();
    private HandlerThread network;
    private Handler networkHandler;
    private TextToSpeech tts;
    /** F-233 / SF-233-10 — couper le compagnon en parlant. */
    private final SpeechInterruptPolicy interruptPolicy = new SpeechInterruptPolicy();
    private volatile boolean ttsReady;
    /** Vrai pendant que le compagnon parle : le micro est rendu (pas d'écho dans le mot d'éveil). */
    private boolean speaking;
    /** Vrai pendant l'écoute courte de la réponse, après une annonce. */
    private boolean replyWindow;
    private final Runnable poll = this::poll;
    private final Runnable endReplyWindow = this::endReplyWindow;

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
        network = new HandlerThread("jarvis-announce");
        network.start();
        networkHandler = new Handler(network.getLooper());
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.FRANCE);
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        // F-233 / SF-233-10 : le micro reste ouvert ; l'écho du début de phrase est ignoré.
                        interruptPolicy.onUtteranceStart(android.os.SystemClock.elapsedRealtime());
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        if (LAST_UTTERANCE.equals(utteranceId)) {
                            main.post(CompanionService.this::afterSpeaking);
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        main.post(() -> {
                            speaking = false;
                            interruptPolicy.onSpeakingEnd();
                            stopCapture();
                            refresh();
                        });
                    }
                });
                ttsReady = true;
            }
        });
        main.postDelayed(poll, 2_000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        // Toujours passer au premier plan d'abord : Android l'exige dans les secondes qui suivent.
        goForeground();
        if (ACTION_QUIT.equals(action)) {
            prefs.setListening(false);
            stopCapture();
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
        main.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.shutdown();
        }
        if (network != null) {
            network.quitSafely();
        }
        unregisterReceiver(batteryReceiver);
        unregisterReceiver(screenOffReceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ état

    /** Recalcule ce qui doit tourner : interrupteur, batterie, gabarits, mot en cours. */
    private void refresh() {
        main.removeCallbacks(retryMic);
        WakeProfile profile = prefs.profile();
        if (replyWindow || (speaking && capturing)) {
            // L'écoute courte de la réponse — ou l'écoute pendant une annonce (SF-233-10) — tient le micro ; elle
            // rend la main à sa fin.
            notifyListen();
            return;
        }
        boolean wanted = prefs.listening() && !awaitingResume && !speaking && !battery.pausedForBattery()
                && profile != null;
        if (wanted && !capturing) {
            startCapture(WakeListener.forWake(profile, () -> main.post(this::onWake)));
        } else if (!wanted && capturing) {
            stopCapture();
        }
        if (!prefs.listening()) {
            status = "En pause";
        } else if (profile == null) {
            status = "Apprends d'abord « Jarvis » dans l'application";
        } else if (battery.pausedForBattery()) {
            status = "En pause : batterie faible (reprise à " + BatteryPolicy.RESUME_FROM_PERCENT + " % ou en charge)";
        } else if (awaitingResume) {
            status = "Jarvis est ouvert";
        } else if (capturing) {
            status = "Jarvis écoute";
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

    private void onWake() {
        Log.i(TAG, "Mot d'éveil reconnu");
        replyWindow = false;
        main.removeCallbacks(endReplyWindow);
        awaitingResume = true;
        stopCapture();
        main.removeCallbacks(resumeAfterWake);
        main.postDelayed(resumeAfterWake, RESUME_AFTER_WAKE_MS);
        refresh();
        showWake();
    }

    private void resumeAfterWake() {
        main.removeCallbacks(resumeAfterWake);
        awaitingResume = false;
        getSystemService(NotificationManager.class).cancel(ID_WAKE);
        refresh();
    }

    // ------------------------------------------------------------------ micro

    private void startCapture(WakeListener listener) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Autorise le micro dans l'application";
            return;
        }
        int min = AudioRecord.getMinBufferSize(WakeEngine.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, WakeEngine.RATE / 5 * 2);
        AudioRecord record;
        try {
            // F-233 / SF-233-10 (D12) : source « communication » — le traitement d'écho du téléphone s'y applique,
            // pour que la voix du compagnon au haut-parleur ne se déclenche pas elle-même.
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, WakeEngine.RATE,
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
        AcousticEchoCanceler echo = attachEchoCanceler(record.getAudioSessionId());
        NoiseSuppressor noise = attachNoiseSuppressor(record.getAudioSessionId());
        capturing = true;
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
                if (echo != null) {
                    echo.release();
                }
                if (noise != null) {
                    noise.release();
                }
                record.release();
            }
        }, "jarvis-wake");
        capture = thread;
        thread.start();
    }

    /** SF-233-10 (D12) — l'annulation d'écho de l'appareil, quand il l'offre ; sinon rien. */
    private static AcousticEchoCanceler attachEchoCanceler(int session) {
        try {
            if (!AcousticEchoCanceler.isAvailable()) {
                return null;
            }
            AcousticEchoCanceler effect = AcousticEchoCanceler.create(session);
            if (effect != null) {
                effect.setEnabled(true);
            }
            return effect;
        } catch (RuntimeException e) {
            Log.w(TAG, "Annulation d'écho indisponible", e);
            return null;
        }
    }

    /** SF-233-10 (D12) — la réduction de bruit de l'appareil, quand il l'offre ; sinon rien. */
    private static NoiseSuppressor attachNoiseSuppressor(int session) {
        try {
            if (!NoiseSuppressor.isAvailable()) {
                return null;
            }
            NoiseSuppressor effect = NoiseSuppressor.create(session);
            if (effect != null) {
                effect.setEnabled(true);
            }
            return effect;
        } catch (RuntimeException e) {
            Log.w(TAG, "Réduction de bruit indisponible", e);
            return null;
        }
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

    // ------------------------------------------------------------------ annonces (SF-232-03)

    /** Une relève : seulement appairé, à l'écoute et hors batterie faible ; le réseau hors du fil principal. */
    private void poll() {
        main.removeCallbacks(poll);
        String token = prefs.token();
        if (token == null || !prefs.listening() || battery.pausedForBattery()) {
            main.postDelayed(poll, AnnouncementPolicy.POLL_MS);
            return;
        }
        String gateway = prefs.gateway();
        String cursor = prefs.cursor();
        networkHandler.post(() -> {
            try {
                AnnouncementClient.Feed feed = AnnouncementClient.fetch(gateway, token, cursor);
                main.post(() -> onFeed(feed));
            } catch (AnnouncementClient.Unauthorized e) {
                main.post(this::onRevoked);
            } catch (IOException | RuntimeException e) {
                // Jamais dit à voix haute : on réessaie plus tard, en espaçant. Jamais le jeton dans le journal.
                Log.w(TAG, "Relève des annonces impossible : " + e.getClass().getSimpleName());
                main.post(() -> main.postDelayed(poll, announcer.nextDelay(false)));
            }
        });
    }

    private void onFeed(AnnouncementClient.Feed feed) {
        if (feed.cursor != null) {
            prefs.setCursor(feed.cursor);
        }
        List<AnnouncementPolicy.Item> fresh = announcer.fresh(feed.items);
        if (!fresh.isEmpty() && ttsReady && AnnouncementPolicy.shouldSpeak(locked(), screenOn(), awaitingResume)) {
            speak(fresh);
        }
        main.postDelayed(poll, announcer.nextDelay(true));
    }

    private void onRevoked() {
        Log.i(TAG, "Jeton des annonces refusé : réappairage nécessaire");
        prefs.setToken(null);
        status = "Annonces coupées : réappaire le compagnon";
        notifyListen();
        main.postDelayed(poll, AnnouncementPolicy.POLL_MS);
    }

    private void speak(List<AnnouncementPolicy.Item> items) {
        speaking = true;
        // F-233 / SF-233-10 (D8) : le micro reste ouvert pendant l'annonce (annulation d'écho) ; un mot entendu la
        // coupe et ouvre Jarvis, qui écoute. Sans micro autorisé, on parle sans écouter, comme avant.
        stopCapture();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCapture(new WakeListener(features -> {
                if (interruptPolicy.shouldInterrupt(features.length, android.os.SystemClock.elapsedRealtime())) {
                    main.post(this::onInterruptedWhileSpeaking);
                }
            }));
        }
        refresh();
        for (int i = 0; i < items.size(); i++) {
            String id = i == items.size() - 1 ? LAST_UTTERANCE : "jarvis-" + i;
            tts.speak(items.get(i).text, TextToSpeech.QUEUE_ADD, null, id);
        }
    }

    /** SF-233-10 — le PO a parlé pendant l'annonce : le compagnon se tait net et ouvre Jarvis, qui écoute. */
    private void onInterruptedWhileSpeaking() {
        if (!speaking) {
            return;
        }
        speaking = false;
        interruptPolicy.onSpeakingEnd();
        if (tts != null) {
            tts.stop();
        }
        stopCapture();
        onWake();
    }

    /** Fin de la dernière annonce : on écoute la réponse quelques secondes, sans mot d'éveil. */
    private void afterSpeaking() {
        if (!speaking) {
            return; // déjà coupé par la voix (SF-233-10)
        }
        speaking = false;
        interruptPolicy.onSpeakingEnd();
        if (awaitingResume || !prefs.listening() || battery.pausedForBattery()
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            refresh();
            return;
        }
        stopCapture();
        replyWindow = true;
        startCapture(new WakeListener(features -> {
            if (features.length >= WakeEngine.MIN_WORD_FRAMES) {
                main.post(this::onReplyHeard);
            }
        }));
        main.postDelayed(endReplyWindow, AnnouncementPolicy.REPLY_WINDOW_MS);
        status = "Jarvis t'écoute";
        notifyListen();
    }

    private void onReplyHeard() {
        if (replyWindow) {
            onWake(); // ouvre Jarvis, qui écoute aussitôt (SF-232-02)
        }
    }

    private void endReplyWindow() {
        if (!replyWindow) {
            return;
        }
        replyWindow = false;
        stopCapture();
        refresh();
    }

    private boolean locked() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    private boolean screenOn() {
        PowerManager power = getSystemService(PowerManager.class);
        return power == null || power.isInteractive();
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
        nm.createNotificationChannel(listen);
        nm.createNotificationChannel(wake);
    }

    private void goForeground() {
        Notification n = buildListen();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                .setContentText("Dis « Jarvis » — rien ne sort du téléphone avant le mot.")
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

    /**
     * Le réveil de l'écran Jarvis. Intention plein écran : téléphone verrouillé, elle allume l'écran et
     * lance {@link WakeActivity} ; téléphone en main, Android l'affiche en bannière, qu'un toucher ouvre.
     * Sans le droit « plein écran » (Android 14+), il reste la bannière.
     */
    private void showWake() {
        PendingIntent open = activity(new Intent(this, WakeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION), 20);
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
