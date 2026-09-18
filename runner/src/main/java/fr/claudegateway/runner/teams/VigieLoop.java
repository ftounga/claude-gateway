package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import fr.claudegateway.runner.OperatingSystem;

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

    private ScheduledFuture<?> task;
    // Anti-spam : un diagnostic n'est redit qu'à la bascule (comme TeamsSessionWatch, SF-122-03).
    private VigieServiceDiagnostic.Fault lastFault = VigieServiceDiagnostic.Fault.NONE;
    private boolean uploadFailing;

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

        Reading reading = reachable ? senseSafely() : Reading.blank();

        VigieReadinessReport report =
                VigieBackground.assemble(reachable, reading.sessionState(), reading.readTest());
        upload(report);
        return report;
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
