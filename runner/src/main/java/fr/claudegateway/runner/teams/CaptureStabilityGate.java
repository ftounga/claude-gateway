package fr.claudegateway.runner.teams;

import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Le portillon de stabilité de la capture</b> (F-128 / SF-128-14) — ce qui fait démarrer la
 * capture d'onglet <b>seulement une fois la réunion « in-call »</b>, c'est-à-dire une fois la page
 * de réunion stabilisée.
 *
 * <h2>Le défaut corrigé</h2>
 * <p>La capture démarrait <b>trop tôt</b>, juste après {@code teams_meeting_join}, pendant que Teams
 * <b>enchaîne plusieurs navigations</b> pour rejoindre la réunion (la cible CDP est <b>mouvante</b>).
 * Le capteur injecté meurt à chaque navigation ; le ré-armement (SF-128-12) et la re-résolution de
 * cible (SF-128-13) livrent alors une <b>course perdante</b> — {@code reattach:ok} puis
 * {@code reinject:browser_unreachable} en boucle → {@code stop:no_active_capture},
 * {@code audio_bytes=0}, {@code image_count=0} (prod CAGIP 2026-09-19, test 7).</p>
 *
 * <h2>Le remède : attaquer la racine</h2>
 * <p>On ne démarre la capture qu'une fois <b>plus aucune navigation depuis {@code quietMillis}</b> :
 * un <b>minuteur de silence</b> est <b>ré-armé à chaque navigation observée</b>
 * ({@code Page.frameNavigated} du cadre principal, {@code Page.loadEventFired}) et la page n'est
 * déclarée {@link State#STABLE stable} que lorsqu'il expire sans nouvelle navigation. Un
 * <b>plafond global</b> ({@code capMillis}) borne l'attente : s'il est atteint sans stabilité, on
 * démarre <b>quand même</b> en best-effort ({@link State#CAP_REACHED}) — le filet SF-128-12/13
 * reprend alors le relais. Une fois la page stable, il n'y a plus de navigation après le start :
 * le capteur <b>survit</b> jusqu'à l'arrêt, et il n'y a plus rien à ré-injecter.</p>
 *
 * <h2>Pure et déterministe</h2>
 * <p>La décision n'utilise <b>aucune horloge murale</b> : elle avance au rythme des créneaux de
 * scrutation ({@code pollMillis}), ce qui la rend déterministe en test et rapide en CI (un
 * {@link BrowserLink.Sleeper} no-op fait défiler les créneaux sans attente réelle). Les navigations
 * arrivent, elles, sur le fil de lecture CDP : {@link #navigations} est atomique.</p>
 *
 * <p><b>DRAPEAU « À VALIDER SUR CALL RÉEL ».</b> La séquence réelle des navigations Teams et le
 * comportement de {@code getDisplayMedia} après stabilisation ne se vérifient qu'en réunion réelle ;
 * ici, seules la détection de stabilité (ré-armement du minuteur, démarrage à expiration, plafond)
 * et le câblage CDP (contre un faux) sont éprouvés.</p>
 */
final class CaptureStabilityGate {

    /** Silence de navigation (ms) qui déclare la page stable — plus rien à ré-injecter après le start. */
    static final long DEFAULT_QUIET_MILLIS = 3_000L;

    /** Plafond global (ms) de l'attente : au-delà, on démarre quand même en best-effort. */
    static final long DEFAULT_CAP_MILLIS = 20_000L;

    /** Granularité de scrutation (ms), via le {@link BrowserLink.Sleeper} existant. */
    static final long DEFAULT_POLL_MILLIS = 250L;

    /** L'état de la décision à un créneau de scrutation. */
    enum State {
        /** Encore en train d'attendre la stabilité (le silence n'a pas duré assez). */
        WAITING,
        /** Plus aucune navigation depuis {@code quietMillis} : on peut démarrer la capture. */
        STABLE,
        /** Le plafond global est atteint sans stabilité : démarrage best-effort. */
        CAP_REACHED
    }

    private final long quietMillis;
    private final long capMillis;
    private final long pollMillis;

    /** Compte des navigations du cadre principal observées — écrites sur le fil CDP, lues au poll. */
    private final AtomicInteger navigations = new AtomicInteger();

    /** Une fois la décision prise, les navigations tardives ne comptent plus (le filet SF-128-12/13 gère). */
    private volatile boolean settled;

    /** Le compte de navigations vu au créneau précédent — pour détecter un nouveau mouvement. */
    private int navigationsAtLastPoll;
    /** Depuis combien de temps (ms de créneaux) aucune navigation n'a été observée. */
    private long quietElapsed;
    /** Temps total (ms de créneaux) écoulé depuis le début de l'attente — borné par {@link #capMillis}. */
    private long totalElapsed;

    CaptureStabilityGate(long quietMillis, long capMillis, long pollMillis) {
        if (quietMillis <= 0 || capMillis <= 0 || pollMillis <= 0) {
            throw new IllegalArgumentException("Les durées de stabilité doivent être strictement positives.");
        }
        this.quietMillis = quietMillis;
        this.capMillis = capMillis;
        this.pollMillis = pollMillis;
    }

    /** La granularité de scrutation — c'est de cette durée qu'avance chaque {@link #tick()}. */
    long pollMillis() {
        return pollMillis;
    }

    /** Le nombre de navigations du cadre principal observées pendant l'attente (diagnostic F-132). */
    int navigationsObserved() {
        return navigations.get();
    }

    /**
     * <b>Une navigation a été observée</b> — ré-arme le minuteur de silence. Appelée sur le fil de
     * lecture CDP à chaque {@code Page.loadEventFired} et {@code Page.frameNavigated} du cadre
     * principal. Après la décision ({@link #settled}), les navigations tardives sont ignorées : c'est
     * le ré-armement SF-128-12/13 qui prend alors le relais.
     */
    void onNavigation() {
        if (!settled) {
            navigations.incrementAndGet();
        }
    }

    /**
     * <b>Une navigation d'un cadre a été observée</b> — n'agit que sur le <b>cadre principal</b> (on
     * ignore les sous-cadres / iframes tierces, dont la navigation ne recharge pas le contexte de
     * capture). Miroir du filtre de {@link CaptureReinjector#onFrameNavigated(JsonNode)}.
     */
    void onFrameNavigated(JsonNode event) {
        if (event != null && event.path("frame").hasNonNull("parentId")) {
            return;
        }
        onNavigation();
    }

    /**
     * <b>Avance d'un créneau de scrutation</b> et rend l'état de la décision. Pur et éprouvable :
     * à appeler après avoir laissé passer {@link #pollMillis} (réel en prod, no-op en test). Si
     * aucune navigation n'est survenue depuis le dernier créneau, le silence s'allonge ; sinon il
     * repart de zéro. La stabilité l'emporte sur le plafond au même créneau.
     */
    State tick() {
        totalElapsed += pollMillis;
        int now = navigations.get();
        if (now == navigationsAtLastPoll) {
            quietElapsed += pollMillis;
        } else {
            quietElapsed = 0;
            navigationsAtLastPoll = now;
        }
        if (quietElapsed >= quietMillis) {
            settled = true;
            return State.STABLE;
        }
        if (totalElapsed >= capMillis) {
            settled = true;
            return State.CAP_REACHED;
        }
        return State.WAITING;
    }

    /**
     * <b>Attend la stabilité</b> puis rend l'issue ({@link State#STABLE} ou {@link State#CAP_REACHED}).
     * Fin driver au-dessus de {@link #tick()} : dort un créneau, avance, recommence tant qu'on
     * attend. La terminaison est garantie par le plafond ({@link #totalElapsed} croît de
     * {@link #pollMillis} à chaque tour).
     */
    State awaitStable(BrowserLink.Sleeper sleeper) {
        State state;
        do {
            sleeper.sleep(pollMillis);
            state = tick();
        } while (state == State.WAITING);
        return state;
    }
}
