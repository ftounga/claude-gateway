package fr.claudegateway.jarvis.companion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.speech.tts.TextToSpeech;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import fr.claudegateway.jarvis.companion.core.CompanionApi;
import fr.claudegateway.jarvis.companion.core.WakeEngine;
import fr.claudegateway.jarvis.companion.core.WakeListener;
import fr.claudegateway.jarvis.companion.core.WakeProfile;

/**
 * Le premier lancement et les réglages (D1c) : chaque permission est demandée <b>avec son
 * explication</b>, une par une ; puis l'apprentissage du mot (trois « Jarvis ») ; puis l'interrupteur.
 * Écran construit en code (aucune mise en page XML, aucune bibliothèque) : il ne sert qu'à régler.
 */
public final class MainActivity extends Activity {

    static final String EXTRA_RESUME = "resume";
    private static final int REQUEST_PERMISSIONS = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private TextView state;
    private Button learn;
    private Button toggle;
    private volatile boolean enrolling;
    private EditText codeInput;
    private Button pairButton;
    private TextView pairingState;
    private volatile boolean pairing;
    private TextToSpeech voiceTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        CompanionService.createChannels(this);
        setContentView(buildScreen());
        if (getIntent().getBooleanExtra(EXTRA_RESUME, false) && prefs.profile() != null) {
            startListening();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onPause() {
        enrolling = false;
        super.onPause();
    }

    private View buildScreen() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        column.addView(text("Jarvis, téléphone verrouillé", 22));
        column.addView(text("Ce compagnon écoute le mot « Jarvis » sur le téléphone, et seulement lui : "
                + "rien n'est enregistré, rien du micro ne sort du téléphone. Au mot, il ouvre l'écran Jarvis de "
                + "l'application, avec ta session habituelle. Appairé, il reçoit aussi les annonces de Jarvis "
                + "et te les dit à voix haute — son jeton ne sert qu'à cela.", 15));

        column.addView(text("1. L'adresse de l'application", 17));
        EditText gateway = new EditText(this);
        gateway.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        gateway.setText(prefs.gateway());
        column.addView(gateway);
        Button save = new Button(this);
        save.setText("Enregistrer l'adresse");
        save.setOnClickListener(v -> {
            if (prefs.setGateway(gateway.getText().toString())) {
                gateway.setText(prefs.gateway());
                say("Adresse enregistrée.");
            } else {
                say("Adresse refusée : elle doit commencer par https://");
            }
        });
        column.addView(save);

        // SF-232-03 — l'appairage : sans lui, le compagnon n'entend que « Jarvis » ; avec lui, il te parle aussi.
        column.addView(text("2. Appairer ce téléphone", 17));
        column.addView(text("Pour que Jarvis te dise à voix haute, même téléphone verrouillé, qu'une demande est "
                + "finie ou qu'une veille a bougé. Dans l'application, écran Jarvis → « Compagnon Android » → "
                + "« Appairer un téléphone », puis tape le code ici.", 14));
        codeInput = new EditText(this);
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeInput.setHint("Code à 8 caractères");
        column.addView(codeInput);
        pairButton = new Button(this);
        pairButton.setOnClickListener(v -> {
            if (prefs.token() != null) {
                forgetPairing();
            } else {
                pair(codeInput.getText().toString());
            }
        });
        column.addView(pairButton);
        pairingState = text("", 14);
        column.addView(pairingState);
        Button voice = new Button(this);
        voice.setText("Tester la voix de Jarvis");
        voice.setOnClickListener(v -> testVoice());
        column.addView(voice);

        column.addView(text("3. Les autorisations", 17));
        column.addView(text("Le micro, pour entendre le mot ; les notifications, pour « Jarvis écoute » "
                + "et l'ouverture au mot.", 14));
        Button permissions = new Button(this);
        permissions.setText("Autoriser le micro et les notifications");
        permissions.setOnClickListener(v -> askPermissions());
        column.addView(permissions);

        column.addView(text("Sans cette exemption, Android coupe l'écoute quand le téléphone dort.", 14));
        Button batteryButton = new Button(this);
        batteryButton.setText("Ne pas couper Jarvis pour économiser la batterie");
        batteryButton.setOnClickListener(v -> askBatteryExemption());
        column.addView(batteryButton);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            column.addView(text("Pour allumer l'écran au mot, téléphone verrouillé.", 14));
            Button fullScreen = new Button(this);
            fullScreen.setText("Autoriser l'ouverture plein écran");
            fullScreen.setOnClickListener(v -> startActivity(new Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName()))));
            column.addView(fullScreen);
        }

        column.addView(text("4. Apprendre ta voix", 17));
        column.addView(text("Dis « Jarvis » trois fois, en marquant une petite pause entre chaque.", 14));
        learn = new Button(this);
        learn.setOnClickListener(v -> learnWake());
        column.addView(learn);

        column.addView(text("5. L'écoute", 17));
        toggle = new Button(this);
        toggle.setOnClickListener(v -> {
            if (prefs.listening()) {
                startService(new Intent(this, CompanionService.class).setAction(CompanionService.ACTION_QUIT));
                prefs.setListening(false);
            } else {
                startListening();
            }
            render();
        });
        column.addView(toggle);

        state = text("", 15);
        column.addView(state);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        return scroll;
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        view.setPadding(0, pad, 0, pad / 2);
        return view;
    }

    private void say(String message) {
        state.setText(message);
    }

    // ------------------------------------------------------------------ appairage (SF-232-03)

    private void renderPairing() {
        boolean paired = prefs.token() != null;
        codeInput.setEnabled(!paired && !pairing);
        codeInput.setVisibility(paired ? View.GONE : View.VISIBLE);
        pairButton.setEnabled(!pairing);
        pairButton.setText(paired ? "Oublier l'appairage" : pairing ? "Appairage…" : "Appairer");
        if (!pairing) {
            pairingState.setText(paired
                    ? "Appairé : Jarvis te parlera ici quand une demande est finie ou qu'une veille bouge."
                    : "Pas appairé : le compagnon n'entend que « Jarvis ».");
        }
    }

    /** Échange le code contre le jeton dédié, hors du fil principal. */
    private void pair(String raw) {
        String code = raw == null ? "" : raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (code.length() != 8) {
            pairingState.setText("Le code fait 8 caractères.");
            return;
        }
        pairing = true;
        renderPairing();
        pairingState.setText("Appairage…");
        String gateway = prefs.gateway();
        String name = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        new Thread(() -> {
            String message;
            boolean ok = false;
            try {
                CompanionApi.Paired paired = CompanionApi.pair(gateway, code, name);
                prefs.setToken(paired.token);
                ok = true;
                message = "Appairé. Dis « Jarvis » quand tu veux ; Jarvis te parlera ici.";
            } catch (CompanionApi.Refused e) {
                message = "companion_limit".equals(e.code)
                        ? "Cinq téléphones sont déjà appairés : révoque-en un dans l'application."
                        : "Code refusé : il est faux, expiré (10 minutes) ou déjà utilisé.";
            } catch (IOException | RuntimeException e) {
                message = "La gateway ne répond pas (" + gateway + "). Vérifie l'adresse et le réseau.";
            }
            boolean done = ok;
            String shown = message;
            main.post(() -> {
                pairing = false;
                if (done) {
                    codeInput.setText("");
                    if (prefs.listening()) {
                        // Le service relit l'appairage et lance la réception des annonces.
                        startListening();
                    }
                }
                renderPairing();
                pairingState.setText(shown);
            });
        }, "jarvis-pair").start();
    }

    /** Oublie le jeton sur le téléphone. La révocation côté application reste à faire dans l'écran Jarvis. */
    private void forgetPairing() {
        prefs.clearPairing();
        if (prefs.listening()) {
            startListening();
        }
        renderPairing();
        pairingState.setText("Appairage oublié ici. Révoque aussi ce téléphone dans l'écran Jarvis de l'application.");
    }

    /** Fait dire une phrase par la synthèse vocale de l'appareil : la voix des annonces. */
    private void testVoice() {
        if (voiceTest != null) {
            voiceTest.shutdown();
        }
        voiceTest = new TextToSpeech(this, code -> {
            if (code == TextToSpeech.SUCCESS) {
                voiceTest.setLanguage(Locale.FRANCE);
                voiceTest.speak("Jarvis est prêt. Je te dirai ici quand une demande est finie.",
                        TextToSpeech.QUEUE_FLUSH, null, "test");
            } else {
                main.post(() -> say("Synthèse vocale indisponible : installe ou active « Services vocaux Google »."));
            }
        });
    }

    private void render() {
        renderPairing();
        boolean mic = hasMic();
        boolean learned = prefs.profile() != null;
        learn.setText(enrolling ? "Annuler l'apprentissage" : learned ? "Réapprendre « Jarvis »" : "Apprendre « Jarvis »");
        learn.setEnabled(mic);
        toggle.setText(prefs.listening() ? "Arrêter l'écoute" : "Démarrer l'écoute");
        toggle.setEnabled(prefs.listening() || (mic && learned && !enrolling));
        if (!enrolling) {
            if (!mic) {
                say("Le micro n'est pas autorisé.");
            } else if (!learned) {
                say("Apprends d'abord « Jarvis ».");
            } else {
                say(prefs.listening() ? "Jarvis écoute." : "Écoute arrêtée.");
            }
        }
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPermissions() {
        List<String> wanted = new ArrayList<>();
        wanted.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(wanted.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS && !hasMic()) {
            // Refus définitif : seuls les réglages d'Android peuvent le lever.
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
        render();
    }

    private void askBatteryExemption() {
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && power.isIgnoringBatteryOptimizations(getPackageName())) {
            say("Déjà exempté.");
            return;
        }
        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
    }

    private void startListening() {
        if (!hasMic() || prefs.profile() == null) {
            render();
            return;
        }
        prefs.setListening(true);
        // Démarré depuis l'écran ouvert : Android accorde l'accès au micro en arrière-plan ensuite.
        startForegroundService(new Intent(this, CompanionService.class).setAction(CompanionService.ACTION_START));
    }

    /** Trois « Jarvis » : le micro du service est rendu le temps de l'apprentissage. */
    private void learnWake() {
        if (enrolling) {
            enrolling = false;
            render();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            render();
            return;
        }
        boolean wasListening = prefs.listening();
        if (wasListening) {
            startService(new Intent(this, CompanionService.class).setAction(CompanionService.ACTION_QUIT));
        }
        enrolling = true;
        render();
        say("Dis « Jarvis »… (0 / " + WakeEngine.ENROLL_COUNT + ")");
        List<double[][]> words = new ArrayList<>();
        new Thread(() -> {
            AudioRecord record = null;
            try {
                int size = Math.max(AudioRecord.getMinBufferSize(WakeEngine.RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT), WakeEngine.RATE / 5 * 2);
                record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeEngine.RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
                WakeListener listener = new WakeListener(features -> {
                    if (features.length >= WakeEngine.MIN_WORD_FRAMES) {
                        words.add(features);
                        int count = words.size();
                        main.post(() -> say("Dis « Jarvis »… (" + count + " / " + WakeEngine.ENROLL_COUNT + ")"));
                    }
                });
                record.startRecording();
                short[] pcm = new short[WakeEngine.RATE / 10];
                while (enrolling && words.size() < WakeEngine.ENROLL_COUNT) {
                    int n = record.read(pcm, 0, pcm.length);
                    if (n < 0) {
                        break;
                    }
                    listener.push(pcm, n);
                }
            } catch (RuntimeException e) {
                main.post(() -> say("Micro indisponible pour l'apprentissage."));
            } finally {
                if (record != null) {
                    try {
                        record.stop();
                    } catch (RuntimeException ignored) {
                        // Jamais démarré.
                    }
                    record.release();
                }
            }
            boolean done = words.size() >= WakeEngine.ENROLL_COUNT;
            main.post(() -> {
                enrolling = false;
                if (done) {
                    prefs.setProfile(WakeProfile.learn(words.subList(0, WakeEngine.ENROLL_COUNT)
                            .toArray(new double[0][][])));
                }
                if (wasListening || done) {
                    startListening();
                }
                render();
                if (done) {
                    say("« Jarvis » appris. Jarvis écoute.");
                }
            });
        }, "jarvis-enroll").start();
    }

    @Override
    protected void onDestroy() {
        enrolling = false;
        if (voiceTest != null) {
            voiceTest.shutdown();
        }
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
