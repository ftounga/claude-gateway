package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.diag.RunnerDiag;
import fr.claudegateway.runner.diag.RunnerDiagLevel;

/**
 * <b>La boucle de mise en service de la Vigie</b> (F-122 / SF-122-06) : le câblage qui manquait.
 *
 * <p>Les briques de F-122 existaient — {@link ManagedChrome} (SF-122-01), {@link VigieBackground} /
 * {@link VigieReadinessReport} / {@link VigieReadinessUploader} / {@link TeamsSessionWatch}
 * (SF-122-03), {@link VigieServiceDiagnostic} (SF-122-04) — mais <b>rien ne les appelait au
 * runtime</b> : le runner ne lançait jamais le Chrome managé et ne remontait jamais d'état, si bien
 * que la check-list SF-122-02 restait « en attente du runner » et que {@code teams_meeting_join}
 * (F-128) échouait faute de Chrome managé. Cette boucle branche tout dans le cycle de vie du
 * runner.</p>
 *
 * <p>À chaque tick (planifié sur l'exécuteur du heartbeat, ~15–30 s) : elle <b>s'assure</b> du Chrome
 * managé (idempotent : ne relance pas s'il répond déjà), <b>sonde</b> l'état Teams, <b>assemble</b>
 * un {@link VigieReadinessReport} et le <b>remonte</b>. Un Chrome absent ({@code NO_BROWSER}) ou une
 * sonde en échec sont <b>remontés</b> — jamais fatals pour le runner ni pour ses autres outils.</p>
 *
 * <p><b>Best-effort strict.</b> Aucune exception ne remonte de la boucle : elle partage l'exécuteur
 * du heartbeat, qu'un jet non capturé arrêterait. Et elle ne <b>spamme</b> pas : un diagnostic ou un
 * échec de remontée n'est dit qu'à la <b>bascule</b>, jamais à chaque tick.</p>
 */
public final class VigieLoop {

    /** Période par défaut entre deux relevés d'état (dans la fourchette ~15–30 s du cadrage). */
    public static final long DEFAULT_PERIOD_SECONDS = 20L;

    /**
     * La sonde de mise en service : à partir d'un Chrome managé joignable, l'état de la session Teams
     * et la réussite d'un test de lecture. Injectée pour que la boucle s'éprouve sans navigateur.
     */
    @FunctionalInterface
    public interface Sonde {

        /** Sonde l'état Teams. Ne doit rien supposer d'autre que « le Chrome répond ». */
        Reading sense();
    }

    /**
     * <b>Le maintien de l'onglet Teams</b> (F-122 / SF-122-07) : à chaque relevé où le Chrome est
     * joignable, s'assurer qu'un onglet Teams reste ouvert (le rouvrir sinon), pour que « Teams
     * connecté » puisse passer au vert sans réunion et que le Radar observe. Injecté (défaut : no-op)
     * pour rester additif et éprouvable sans navigateur.
     */
    @FunctionalInterface
    public interface TabGuard {
        void ensureTeamsTab();
    }

    /**
     * Ce qu'un relevé a établi.
     *
     * @param sessionState l'état de la session Teams observée ({@link TeamsSessionWatch})
     * @param readTest     un test de lecture Teams de bout en bout a réussi
     */
    public record Reading(TeamsSessionState sessionState, boolean readTest) {
        public Reading {
            sessionState = sessionState == null ? TeamsSessionState.UNKNOWN : sessionState;
        }

        /** Rien de lisible : Chrome injoignable, ou sonde en échec. */
        public static Reading blank() {
            return new Reading(TeamsSessionState.UNKNOWN, false);
        }
    }

    private final ManagedChrome chrome;
    private final Sonde sonde;
    private final VigieReadinessUploader uploader;
    private final OperatingSystem system;
    private final Consumer<String> say;
    private final long periodSeconds;
    // SF-122-07 : maintien de l'onglet Teams. No-op par défaut (additif) ; la garde réelle est posée
    // par le runner (RunnerConnection). Best-effort : n'appartient jamais au chemin fatal du tick.
    private TabGuard tabGuard = () -> { };

    private ScheduledFuture<?> task;
    // Anti-spam : un diagnostic n'est redit qu'à la bascule (comme TeamsSessionWatch, SF-122-03).
    private VigieServiceDiagnostic.Fault lastFault = VigieServiceDiagnostic.Fault.NONE;
    private boolean uploadFailing;
    // Diagnostic F-132 : l'état Chrome et l'état de session ne sont émis qu'à la bascule (anti-spam) ;
    // le tick lui-même reste en DEBUG (visible seulement quand un diagnostic est activé, SF-132-05).
    private ManagedChrome.State lastDiagState;
    private TeamsSessionState lastDiagSession;

    public VigieLoop(ManagedChrome chrome, Sonde sonde, VigieReadinessUploader uploader,
            OperatingSystem system, Consumer<String> say) {
        this(chrome, sonde, uploader, system, say, DEFAULT_PERIOD_SECONDS);
    }

    VigieLoop(ManagedChrome chrome, Sonde sonde, VigieReadinessUploader uploader,
            OperatingSystem system, Consumer<String> say, long periodSeconds) {
        this.chrome = chrome;
        this.sonde = sonde;
        this.uploader = uploader;
        this.system = system;
        this.say = say;
        this.periodSeconds = periodSeconds;
    }

    /**
     * Pose la garde de maintien de l'onglet Teams (F-122 / SF-122-07). Fluide, additif : sans elle, la
     * boucle se comporte comme avant. Une garde {@code null} rétablit le no-op.
     */
    public VigieLoop withTabGuard(TabGuard guard) {
        this.tabGuard = guard == null ? () -> { } : guard;
        return this;
    }

    /**
     * Planifie la boucle sur l'exécuteur donné (celui du heartbeat, réutilisé — SF-122-06). Idempotent :
     * un second appel ne double pas l'ordonnancement.
     */
    public synchronized void start(ScheduledExecutorService executor) {
        if (task != null) {
            return;
        }
        task = executor.scheduleWithFixedDelay(this::safeTick, 0, periodSeconds, TimeUnit.SECONDS);
    }

    /**
     * Arrête proprement : annule la boucle <b>et</b> arrête le Chrome managé (pas d'orphelin, §6 du
     * cadrage). Idempotent.
     */
    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        chrome.stop();
    }

    /** Un tick blindé : la Vigie ne casse jamais le runner ni le heartbeat (best-effort strict). */
    void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            note("Vigie : incident non fatal ignoré (" + e.getMessage()
                    + ") — nouvel essai au prochain relevé.");
            // Diagnostic F-132 : le TYPE d'erreur + un message court expurgé, jamais une stacktrace.
            RunnerDiag.error("vigie", "tick_error",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    /**
     * Un relevé complet : assure le Chrome managé, sonde, assemble, remonte. Rend le rapport remonté
     * (pour les tests).
     */
    VigieReadinessReport tick() {
        ManagedChrome.State state = chrome.ensureRunning();
        boolean reachable = state == ManagedChrome.State.REACHABLE
                || state == ManagedChrome.State.LAUNCHED;
        announceFault(state);
        diagChromeState(state);

        // SF-122-07 : tant que le Chrome répond, on maintient l'onglet Teams ouvert (rouvert s'il a
        // été fermé) AVANT de sonder — ainsi « Teams connecté » peut passer au vert sans réunion et le
        // Radar observe. Best-effort strict : jamais fatal pour le relevé ni le heartbeat.
        if (reachable) {
            ensureTeamsTabSafely();
        }

        Reading reading = reachable ? senseSafely() : Reading.blank();
        diagSession(reachable, reading.sessionState());

        VigieReadinessReport report =
                VigieBackground.assemble(reachable, reading.sessionState(), reading.readTest());
        diagTick(reachable, reading, report);
        upload(report);
        return report;
    }

    /**
     * Diagnostic F-132 : l'état du Chrome managé, <b>à la bascule seulement</b> (anti-spam). On
     * remonte l'état, le port et le <b>nom</b> de l'exécutable — jamais son chemin complet (cadrage
     * §3–4). Best-effort : {@link RunnerDiag} n'échoue jamais.
     */
    private void diagChromeState(ManagedChrome.State state) {
        if (state == lastDiagState) {
            return;
        }
        lastDiagState = state;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("state", state);
        fields.put("port", chrome.port());
        chrome.executable().ifPresent(p -> fields.put("exe", p.getFileName().toString()));
        RunnerDiagLevel level = switch (state) {
            case REACHABLE, LAUNCHED -> RunnerDiagLevel.INFO;
            case UNREACHABLE -> RunnerDiagLevel.WARN;
            case NO_BROWSER -> RunnerDiagLevel.ERROR;
        };
        RunnerDiag.event(level, "chrome", "chrome_state", null, fields);
    }

    /**
     * Diagnostic F-132 : l'état de la session Teams observée, <b>à la bascule seulement</b>. Un
     * <b>état</b>, jamais un contenu ; une reconnexion requise est un {@code WARN}.
     */
    private void diagSession(boolean reachable, TeamsSessionState session) {
        if (!reachable || session == lastDiagSession) {
            return;
        }
        lastDiagSession = session;
        RunnerDiagLevel level = session == TeamsSessionState.RELOGIN_REQUIRED
                ? RunnerDiagLevel.WARN
                : RunnerDiagLevel.INFO;
        RunnerDiag.event(level, "teams", "session_state", null, Map.of("session", session));
    }

    /** Diagnostic F-132 : le battement de la boucle, en {@code DEBUG} (liveness d'un diagnostic actif). */
    private void diagTick(boolean reachable, Reading reading, VigieReadinessReport report) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("reachable", reachable);
        fields.put("session", reading.sessionState());
        fields.put("readTest", reading.readTest());
        fields.put("teamsConnected", report.teamsConnected());
        fields.put("signInRequired", report.teamsSignInRequired());
        RunnerDiag.debug("vigie", "tick", null, fields);
    }

    /** Maintien de l'onglet Teams (SF-122-07), blindé : une garde qui explose ne casse jamais le tick. */
    private void ensureTeamsTabSafely() {
        try {
            tabGuard.ensureTeamsTab();
        } catch (RuntimeException e) {
            // Best-effort strict : l'onglet sera retenté au prochain relevé, le tick continue.
            RunnerDiag.warn("chrome", "teams_tab",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private Reading senseSafely() {
        try {
            Reading reading = sonde.sense();
            return reading == null ? Reading.blank() : reading;
        } catch (RuntimeException e) {
            // Une sonde qui explose (liaison perdue, navigateur qui répond mal) n'est pas fatale :
            // on remonte « pas encore observé » et on réessaie au tick suivant.
            return Reading.blank();
        }
    }

    /** Nomme l'échec Chrome (SF-122-04), une seule fois par bascule, sans jamais bloquer le runner. */
    private void announceFault(ManagedChrome.State state) {
        VigieServiceDiagnostic.Diagnosis diagnosis =
                VigieServiceDiagnostic.fromChromeState(state, system);
        if (diagnosis.fault() == lastFault) {
            return;
        }
        lastFault = diagnosis.fault();
        if (diagnosis.isFault()) {
            note(diagnosis.title() + " — " + diagnosis.message());
        }
    }

    private void upload(VigieReadinessReport report) {
        try {
            uploader.upload(report);
            if (uploadFailing) {
                uploadFailing = false;
                note("Vigie : remontée de l'état de mise en service rétablie.");
            }
        } catch (IOException e) {
            if (!uploadFailing) {
                uploadFailing = true;
                note("Vigie : l'état n'a pas pu remonter (" + e.getMessage()
                        + ") — nouvel essai au prochain relevé.");
            }
        }
    }

    private void note(String line) {
        if (say != null) {
            say.accept(line);
        }
    }
}
