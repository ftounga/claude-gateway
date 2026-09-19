package fr.claudegateway.runner.teams;

/**
 * <b>Le portillon d'entrée en réunion</b> (F-128 / SF-128-16) — ce qui fait attendre le <b>vrai</b>
 * état « in-call » avant de rendre la main, plutôt que le simple calme de navigation de SF-128-14.
 *
 * <h2>Le défaut corrigé</h2>
 * <p>« Rejoindre » navigue vers l'URL de réunion et tombe sur l'écran de <b>pré-jonction</b> Teams
 * (aperçu + bouton « Rejoindre maintenant »). Cet écran est <b>stable</b> (aucune navigation) : le
 * portillon de stabilité (SF-128-14) le prenait pour un « in-call » et laissait démarrer la capture
 * <b>trop tôt</b> ; la transition pré-join → in-call navigue alors et tue le capteur
 * ({@code reinject:browser_unreachable}, {@code stop:no_active_capture}, {@code audio_bytes=0}).</p>
 *
 * <h2>Le remède : attendre un signal RÉEL</h2>
 * <p>On n'entre pas « en réunion » sur un faux calme : on attend un <b>signal réel</b> que la réunion
 * est en cours — la présence des contrôles d'appel (« Quitter »/« Raccrocher »/hangup) et/ou un média
 * actif (voir {@link MeetingPresence#IN_CALL_PROBE_SCRIPT}). Le portillon interroge ce signal à chaque
 * créneau ; dès qu'il est présent, l'état devient {@link State#IN_CALL}. Un <b>plafond global</b>
 * ({@code capMillis}) borne l'attente : atteint sans in-call, on rend {@link State#CAP_REACHED}
 * (best-effort — l'utilisateur décidera, la capture reste possible et le filet SF-128-12/13/14 tient).</p>
 *
 * <h2>Pure et déterministe</h2>
 * <p>La décision n'utilise <b>aucune horloge murale</b> : elle avance au rythme des créneaux
 * ({@code pollMillis}), ce qui la rend déterministe en test et rapide en CI (un
 * {@link BrowserLink.Sleeper} no-op fait défiler les créneaux sans attente réelle).</p>
 *
 * <p><b>DRAPEAU « À VALIDER SUR CALL RÉEL ».</b> Le signal réel (sélecteurs DOM des contrôles d'appel,
 * média actif) ne se vérifie qu'en réunion réelle ; ici, seules la décision (signal → in-call, plafond
 * → best-effort) et le câblage CDP (contre un faux) sont éprouvés.</p>
 */
final class InCallGate {

    /** Plafond global (ms) de l'attente d'in-call : au-delà, on rend la main en best-effort. */
    static final long DEFAULT_CAP_MILLIS = 25_000L;

    /** Granularité de scrutation (ms), via le {@link BrowserLink.Sleeper} existant. */
    static final long DEFAULT_POLL_MILLIS = 500L;

    /** L'état de la décision à un créneau de scrutation. */
    enum State {
        /** Pas encore de signal in-call, et le plafond n'est pas atteint. */
        WAITING,
        /** Un signal réel d'in-call a été observé : on peut entrer en réunion. */
        IN_CALL,
        /** Le plafond global est atteint sans signal : on rend la main en best-effort. */
        CAP_REACHED
    }

    /** Le signal réel d'in-call, lu à chaque créneau (DOM / média via CDP en prod, faux en test). */
    @FunctionalInterface
    interface Signal {
        /** Vrai si la réunion est <b>réellement</b> en cours (pas le pré-join). */
        boolean inCall();
    }

    private final long capMillis;
    private final long pollMillis;

    /** Nombre de sondes effectuées (diagnostic F-132). */
    private int probes;
    /** Temps total (ms de créneaux) écoulé — borné par {@link #capMillis}, garantit la terminaison. */
    private long totalElapsed;
    /** Une fois la décision prise, on ne rebascule pas. */
    private boolean settled;

    InCallGate(long capMillis, long pollMillis) {
        if (capMillis <= 0 || pollMillis <= 0) {
            throw new IllegalArgumentException("Les durées d'attente in-call doivent être strictement positives.");
        }
        this.capMillis = capMillis;
        this.pollMillis = pollMillis;
    }

    /** La granularité de scrutation — c'est de cette durée qu'avance chaque {@link #tick(boolean)}. */
    long pollMillis() {
        return pollMillis;
    }

    /** Le nombre de sondes du signal effectuées pendant l'attente (diagnostic F-132). */
    int probesObserved() {
        return probes;
    }

    /**
     * <b>Avance d'un créneau de scrutation</b> à partir d'une lecture du signal, et rend l'état. Pur et
     * éprouvable : {@code signalPresent} est le résultat d'une sonde (DOM/média) faite juste avant. Un
     * signal présent l'emporte immédiatement ({@link State#IN_CALL}) ; sinon le temps s'écoule jusqu'au
     * plafond ({@link State#CAP_REACHED}).
     */
    State tick(boolean signalPresent) {
        if (settled) {
            return signalPresent ? State.IN_CALL : State.CAP_REACHED;
        }
        probes++;
        totalElapsed += pollMillis;
        if (signalPresent) {
            settled = true;
            return State.IN_CALL;
        }
        if (totalElapsed >= capMillis) {
            settled = true;
            return State.CAP_REACHED;
        }
        return State.WAITING;
    }

    /**
     * <b>Attend l'entrée réelle en réunion</b> puis rend l'issue ({@link State#IN_CALL} ou
     * {@link State#CAP_REACHED}). Fin driver au-dessus de {@link #tick(boolean)} : dort un créneau,
     * sonde le signal, avance, recommence tant qu'on attend. La terminaison est garantie par le
     * plafond ({@link #totalElapsed} croît de {@link #pollMillis} à chaque tour).
     */
    State awaitInCall(BrowserLink.Sleeper sleeper, Signal signal) {
        State state;
        do {
            sleeper.sleep(pollMillis);
            state = tick(signal.inCall());
        } while (state == State.WAITING);
        return state;
    }
}
