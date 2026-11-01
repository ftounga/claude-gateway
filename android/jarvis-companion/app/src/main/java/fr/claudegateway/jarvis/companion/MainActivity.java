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

import java.util.ArrayList;
import java.util.List;

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
    private Button pair;
    private TextView pairing;
    private volatile boolean enrolling;

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
                + "rien n'est enregistré, rien ne sort avant le mot. Au mot, il ouvre l'écran Jarvis de "
                + "l'application, avec ta session habituelle. Appairé (étape 5), il dit aussi les annonces.", 15));

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

        column.addView(text("2. Les autorisations", 17));
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

        column.addView(text("3. Apprendre ta voix", 17));
        column.addView(text("Dis « Jarvis » trois fois, en marquant une petite pause entre chaque.", 14));
        learn = new Button(this);
        learn.setOnClickListener(v -> learnWake());
        column.addView(learn);

        column.addView(text("4. L'écoute", 17));
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

        // SF-232-03 — les annonces téléphone verrouillé : appairage par le code de l'écran Jarvis.
        column.addView(text("5. Les annonces, téléphone verrouillé", 17));
        column.addView(text("Dans l'écran Jarvis de l'application : « Annonces téléphone verrouillé » puis "
                + "« Appairer le compagnon ». Saisis ici le code affiché. Le compagnon dira alors à voix haute "
                + "la fin d'un tour lancé à la voix et les alertes de veille.", 14));
        EditText code = new EditText(this);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setHint("Code d'appairage");
        column.addView(code);
        pair = new Button(this);
        pair.setOnClickListener(v -> {
            if (prefs.token() != null) {
                prefs.setToken(null);
                renderPairing();
                return;
            }
            pairWith(code.getText().toString());
        });
        column.addView(pair);
        pairing = text("", 14);
        column.addView(pairing);

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

    private void render() {
        boolean mic = hasMic();
        boolean learned = prefs.profile() != null;
        renderPairing();
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

    /** SF-232-03 — l'état de l'appairage des annonces. */
    private void renderPairing() {
        boolean paired = prefs.token() != null;
        pair.setText(paired ? "Oublier l'appairage" : "Appairer");
        pairing.setText(paired ? "Appairé : les annonces sont dites téléphone verrouillé."
                : "Non appairé : aucune annonce.");
    }

    /** Échange le code contre le jeton des annonces, hors du fil principal. Le jeton n'est jamais affiché. */
    private void pairWith(String raw) {
        String code = raw == null ? "" : raw.trim();
        if (code.isEmpty()) {
            pairing.setText("Saisis le code affiché dans l'écran Jarvis.");
            return;
        }
        pair.setEnabled(false);
        pairing.setText("Appairage…");
        String gateway = prefs.gateway();
        String label = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        new Thread(() -> {
            String message;
            try {
                prefs.setToken(AnnouncementClient.pair(gateway, code, label));
                message = null;
            } catch (AnnouncementClient.Unauthorized e) {
                message = "Code refusé ou expiré : génère-en un nouveau.";
            } catch (java.io.IOException | RuntimeException e) {
                message = "L'application est injoignable. Vérifie l'adresse et réessaie.";
            }
            String result = message;
            main.post(() -> {
                pair.setEnabled(true);
                renderPairing();
                if (result != null) {
                    pairing.setText(result);
                }
            });
        }, "jarvis-pair").start();
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
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
