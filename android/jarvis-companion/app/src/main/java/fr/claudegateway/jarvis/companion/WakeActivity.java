package fr.claudegateway.jarvis.companion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fr.claudegateway.jarvis.companion.core.JarvisScreenPolicy;
import fr.claudegateway.jarvis.companion.core.WakeLaunchPolicy;

/**
 * <b>L'écran Jarvis, dans le compagnon</b> (F-232 / SF-232-08, amendement du 2026-11-04, D5 à D8).
 *
 * <p>Au mot « Jarvis », cette activité s'affiche <b>par-dessus l'écran verrouillé</b> (sans demander le déverrouillage),
 * allume l'écran, le garde allumé, et charge dans une WebView l'écran {@code /jarvis} de la gateway — même conversation,
 * même voix, même 3D — avec {@code ecoute=1} : la commande s'enchaîne sur le mot (D6).</p>
 *
 * <ul>
 *   <li><b>Session</b> (D7) : le jeton dédié du compagnon ne quitte jamais ce code ; il est échangé contre une session
 *   courte à portée {@code jarvis-compagnon} ({@code POST /api/runner/jarvis/session}), remise à la page par le pont
 *   {@code window.JarvisCompanion} → {@code window.jarvisCompanionSession(...)}, exécuté dans le cadre principal
 *   seulement. Aucune méthode du pont ne rend de donnée (un cadre inclus ne peut rien lire).</li>
 *   <li><b>Micro</b> : accordé à la page de la gateway seulement ; le service du mot d'éveil l'a rendu à l'ouverture
 *   et le reprend à la fermeture (jamais deux captures).</li>
 *   <li><b>Verrouillé</b> (D8) : au « oui » d'une action engageante, la page appelle {@code requestUnlock()} — la
 *   demande de déverrouillage s'affiche au-dessus de l'écran Jarvis visible ; réussie, une session déverrouillée est
 *   émise et la page reprend.</li>
 *   <li><b>Fermeture</b> (SF-232-10) : la page la demande (fin de conversation), l'écran s'éteint
 *   ({@code ACTION_SCREEN_OFF}), bouton accueil ({@code onUserLeaveHint}), retour, {@link
 *   JarvisScreenPolicy#IDLE_CLOSE_MS} sans signe de vie, ou restée invisible plus de {@link
 *   WakeLaunchPolicy#STOPPED_CLOSE_MS}. <b>Jamais à {@code onStop}</b> : la transition du verrou et de l'allumage y fait
 *   passer l'activité, qui se fermait aussitôt (écran noir qui clignote, constat du 2026-11-06).</li>
 * </ul>
 */
public final class WakeActivity extends Activity {

    private static final String TAG = "JarvisScreen";
    private static final long IDLE_CHECK_MS = 30_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private Prefs prefs;
    private String gateway;
    private WebView web;
    private volatile long lastActivity = SystemClock.elapsedRealtime();
    private long sessionExpiresAt;
    private boolean unlocking;
    private boolean closed;
    /** SF-232-10 — l'instant où l'activité est passée « arrêtée » (0 : visible). */
    private long stoppedAt;
    private boolean screenOffRegistered;

    /** SF-232-10 — l'écran s'éteint (bouton) : la conversation s'arrête et le micro revient au mot d'éveil. */
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WakeLaunchPolicy.closeOnLeave(unlocking)) {
                Log.i(TAG, "Écran Jarvis fermé : écran éteint");
                finish();
            }
        }
    };

    /** SF-232-10 — filet : un écran Jarvis resté invisible ne garde pas le micro. */
    private final Runnable stoppedCheck = () -> {
        if (WakeLaunchPolicy.closeWhileStopped(SystemClock.elapsedRealtime(), stoppedAt, unlocking)) {
            Log.i(TAG, "Écran Jarvis fermé : resté invisible");
            finish();
        }
    };

    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (JarvisScreenPolicy.idleExpired(SystemClock.elapsedRealtime(), lastActivity) && !unlocking) {
                Log.i(TAG, "Écran Jarvis fermé : sans signe de vie");
                finish();
                return;
            }
            main.postDelayed(this, IDLE_CHECK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        // L'échange dure : l'écran reste allumé tant que l'activité est là (elle se ferme seule, voir idleCheck).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = new Prefs(this);
        gateway = prefs.gateway();
        tellService(CompanionService.ACTION_JARVIS_OPENED);
        registerScreenOff();
        if (prefs.token() == null) {
            showMessage("Appairez d'abord le compagnon : ouvrez l'application Jarvis, étape « Les annonces ».");
            main.postDelayed(this::finish, 6_000L);
            return;
        }
        buildWebView();
        web.loadUrl(JarvisScreenPolicy.screenUrl(gateway, BuildConfig.VERSION_CODE));
        main.postDelayed(idleCheck, IDLE_CHECK_MS);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void buildWebView() {
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // D5 : la voix de Jarvis part sans geste (le PO ne touche pas le téléphone).
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (JarvisScreenPolicy.sameOrigin(gateway, url)) {
                    return false;
                }
                // Hors de la gateway : jamais dans cet écran. Déverrouillé, on l'ouvre dans le navigateur.
                if (!locked() && request.isForMainFrame()) {
                    openOutside(url);
                }
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.w(TAG, "Moteur de la page arrêté");
                finish();
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                main.post(() -> answerPermission(request));
            }
        });
        web.addJavascriptInterface(new Bridge(), "JarvisCompanion");
        setContentView(web);
    }

    /** Le micro, à la seule page de la gateway, et seulement s'il est accordé à l'application. */
    private void answerPermission(PermissionRequest request) {
        boolean origin = request.getOrigin() != null
                && JarvisScreenPolicy.sameOrigin(gateway, request.getOrigin().toString());
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean asksAudio = false;
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                asksAudio = true;
            }
        }
        if (origin && mic && asksAudio) {
            request.grant(new String[] {PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            request.deny();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        lastActivity = SystemClock.elapsedRealtime();
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onStart() {
        super.onStart();
        stoppedAt = 0;
        main.removeCallbacks(stoppedCheck);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // SF-232-10 — JAMAIS fermer ici : par-dessus le verrou, l'allumage et le keyguard font passer l'activité par
        // onPause/onStop, ce qui la fermait avant même que la page ne charge. Restée invisible trop longtemps, elle se
        // ferme (stoppedCheck) ; l'écran éteint et le bouton accueil la ferment tout de suite.
        if (!isChangingConfigurations()) {
            stoppedAt = SystemClock.elapsedRealtime();
            main.removeCallbacks(stoppedCheck);
            main.postDelayed(stoppedCheck, WakeLaunchPolicy.STOPPED_CLOSE_MS);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // SF-232-10 — bouton accueil (ou une autre application ouverte par le PO) : la conversation s'arrête.
        if (WakeLaunchPolicy.closeOnLeave(unlocking)) {
            finish();
        }
    }

    private void registerScreenOff() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOff, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOff, filter);
        }
        screenOffRegistered = true;
    }

    @Override
    protected void onDestroy() {
        if (screenOffRegistered) {
            screenOffRegistered = false;
            unregisterReceiver(screenOff);
        }
        main.removeCallbacksAndMessages(null);
        network.shutdownNow();
        if (web != null) {
            web.stopLoading();
            web.removeJavascriptInterface("JarvisCompanion");
            web.destroy();
            web = null;
        }
        if (!closed) {
            closed = true;
            tellService(CompanionService.ACTION_JARVIS_CLOSED);
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ session (D7)

    /** Émet (ou renouvelle) la session et la remet à la page ; {@code null} à la page si elle est refusée. */
    private void deliverSession(String purpose, boolean forceNew, Runnable then) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        boolean isLocked = locked();
        if (!forceNew && !JarvisScreenPolicy.needsRenewal(System.currentTimeMillis(), sessionExpiresAt)
                && lastSession != null && lastSessionLocked == isLocked) {
            pushSession(lastSession);
            then.run();
            return;
        }
        String token = prefs.token();
        if (token == null) {
            pushSession(null);
            then.run();
            return;
        }
        network.execute(() -> {
            try {
                JSONObject session = AnnouncementClient.session(gateway, token, isLocked, purpose);
                main.post(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    lastSession = session;
                    lastSessionLocked = session.optBoolean("locked", true);
                    sessionExpiresAt = parseInstant(session.optString("expiresAt", null));
                    pushSession(session);
                    then.run();
                });
            } catch (AnnouncementClient.Unauthorized e) {
                main.post(() -> {
                    prefs.setToken(null);
                    if (isDestroyed()) {
                        return;
                    }
                    pushSession(null);
                    then.run();
                    showMessage("Le compagnon n'est plus appairé : réappairez-le depuis l'application.");
                    main.postDelayed(this::finish, 6_000L);
                });
            } catch (IOException | RuntimeException e) {
                // Jamais le jeton dans le journal.
                Log.w(TAG, "Session Jarvis impossible : " + e.getClass().getSimpleName());
                main.post(() -> {
                    pushSession(null);
                    then.run();
                });
            }
        });
    }

    private JSONObject lastSession;
    private boolean lastSessionLocked = true;

    private void pushSession(JSONObject session) {
        if (web == null) {
            return;
        }
        // evaluateJavascript s'exécute dans le cadre principal : un cadre inclus ne reçoit jamais la session.
        String payload = session == null ? "null" : session.toString();
        web.evaluateJavascript("window.jarvisCompanionSession&&window.jarvisCompanionSession(" + payload + ")", null);
    }

    private static long parseInstant(String iso) {
        if (iso == null) {
            return 0;
        }
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ déverrouiller (D8)

    private void requestUnlock() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard == null || !keyguard.isKeyguardLocked()) {
            afterUnlock(true);
            return;
        }
        if (unlocking) {
            return;
        }
        unlocking = true;
        lastActivity = SystemClock.elapsedRealtime();
        // L'écran Jarvis reste affiché derrière la demande : plus de clignotement (le constat du 2026-11-04).
        keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() {
                unlocking = false;
                afterUnlock(true);
            }

            @Override
            public void onDismissCancelled() {
                unlocking = false;
                afterUnlock(false);
            }

            @Override
            public void onDismissError() {
                unlocking = false;
                afterUnlock(false);
            }
        });
    }

    private void afterUnlock(boolean unlocked) {
        if (unlocked) {
            // Une session DÉVERROUILLÉE d'abord (la passerelle n'exécute rien sans elle), puis l'issue.
            deliverSession("UNLOCK", true, () -> evaluate(JarvisScreenPolicy.unlockCallback(true)));
        } else {
            evaluate(JarvisScreenPolicy.unlockCallback(false));
        }
    }

    private void evaluate(String script) {
        if (web != null) {
            web.evaluateJavascript(script, null);
        }
    }

    // ------------------------------------------------------------------ outils

    private boolean locked() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return keyguard == null || keyguard.isKeyguardLocked();
    }

    private void openOutside(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Aucun navigateur");
        }
    }

    private void showMessage(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setBackgroundColor(Color.BLACK);
        view.setTextSize(18);
        view.setGravity(Gravity.CENTER);
        view.setPadding(48, 48, 48, 48);
        setContentView(view);
    }

    private void tellService(String action) {
        try {
            startService(new Intent(this, CompanionService.class).setAction(action));
        } catch (RuntimeException e) {
            // Service arrêté (Quitter) : rien à rendre ni à reprendre.
            Log.i(TAG, "Service du mot d'éveil absent");
        }
    }

    /**
     * Le pont exposé à la page sous {@code window.JarvisCompanion}. Appelé sur un fil du moteur : tout repasse sur le
     * fil principal. <b>Aucune méthode ne rend de donnée</b> hormis l'état verrouillé ; la session part par
     * {@code evaluateJavascript} (cadre principal).
     */
    private final class Bridge {

        @JavascriptInterface
        public void requestSession(String purpose) {
            lastActivity = SystemClock.elapsedRealtime();
            String why = "RENEW".equals(purpose) ? "RENEW" : "OPEN";
            main.post(() -> deliverSession(why, "RENEW".equals(why), () -> { }));
        }

        @JavascriptInterface
        public boolean isLocked() {
            return locked();
        }

        @JavascriptInterface
        public void requestUnlock() {
            main.post(WakeActivity.this::requestUnlock);
        }

        @JavascriptInterface
        public void keepAlive() {
            lastActivity = SystemClock.elapsedRealtime();
        }

        @JavascriptInterface
        public void close() {
            main.post(WakeActivity.this::finish);
        }

        @JavascriptInterface
        public int version() {
            return BuildConfig.VERSION_CODE;
        }
    }
}
