package fr.claudegateway.runner.launcher;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Ce que le lanceur fait quand le runner s'arrête (F-111 / SF-111-02) — <b>logique pure</b>, sans
 * processus ni fichier : c'est elle que les tests exercent sur les trois systèmes.
 *
 * <table>
 *   <caption>Code de sortie du runner → geste du lanceur (cadrage §2)</caption>
 *   <tr><td>{@code 75}</td><td>une mise à jour est prête : démarrer la version de {@code next-version}</td></tr>
 *   <tr><td>{@code 0}, {@code 2} à {@code 6}</td><td>arrêt demandé ou erreur que relancer ne réparerait
 *       pas (usage, appairage, jeton, réseau, transport) : s'arrêter aussi</td></tr>
 *   <tr><td>autre</td><td>plantage : relancer la même version, 3 fois au plus en 5 minutes</td></tr>
 * </table>
 */
public final class LauncherPolicy {

    /** Le runner a préparé une mise à jour et attend d'être remplacé. */
    public static final int EXIT_UPDATE = 75;

    /** Codes d'arrêt existants de {@code RunnerMain} : relancer n'y changerait rien. */
    static final Set<Integer> STOP_CODES = Set.of(0, 2, 3, 4, 5, 6);

    /** Nombre de relances permises dans la fenêtre. */
    public static final int MAX_RESTARTS = 3;

    /** Plantages d'une version à l'essai qui déclenchent le retour à la précédente (SF-111-05). */
    public static final int TRIAL_CRASHES = 3;

    /** Fenêtre de comptage des plantages. */
    public static final Duration CRASH_WINDOW = Duration.ofMinutes(5);

    /** Le geste décidé. */
    public enum Action {
        /** S'arrêter avec le code du runner. */
        STOP,
        /** Démarrer la version annoncée par {@code next-version}. */
        UPDATE,
        /** Relancer la même version. */
        RESTART,
        /** Trop de plantages : s'arrêter avec le code du runner. */
        GIVE_UP
    }

    private final Deque<Instant> crashes = new ArrayDeque<>();

    /** Décide du geste pour une sortie du runner à l'instant {@code now}. */
    public Action onExit(int exitCode, Instant now) {
        if (exitCode == EXIT_UPDATE) {
            return Action.UPDATE;
        }
        if (STOP_CODES.contains(exitCode)) {
            return Action.STOP;
        }
        Instant horizon = now.minus(CRASH_WINDOW);
        while (!crashes.isEmpty() && crashes.peekFirst().isBefore(horizon)) {
            crashes.pollFirst();
        }
        crashes.addLast(now);
        return crashes.size() > MAX_RESTARTS ? Action.GIVE_UP : Action.RESTART;
    }

    /** Nombre de plantages comptés dans la fenêtre courante. */
    public int recentCrashes() {
        return crashes.size();
    }

    /** Oublie les plantages : une nouvelle version repart d'un compte vierge. */
    public void resetCrashes() {
        crashes.clear();
    }
}
