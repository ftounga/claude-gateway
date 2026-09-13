package fr.claudegateway.runner;

import java.time.Duration;
import java.util.UUID;

/**
 * Doublures de {@link RunnerLiveness} pour les tests qui ne portent pas sur le battement (F-97 /
 * SF-97-01) : sans elles, chaque test d'appel runner devrait fabriquer un jeton et son
 * {@code last_seen_at}.
 */
public final class RunnerLivenessStubs {

    private RunnerLivenessStubs() {
    }

    /** Un poste qui bat toujours : le comportement d'avant F-97 pour tout ce qui n'est pas le statut. */
    public static RunnerLiveness alwaysAlive() {
        return fixed(true);
    }

    /** Un poste muet, quel qu'il soit. */
    public static RunnerLiveness alwaysSilent() {
        return fixed(false);
    }

    private static RunnerLiveness fixed(boolean alive) {
        return new RunnerLiveness(null, Duration.ofSeconds(90)) {
            @Override
            public boolean isAlive(UUID userId, UUID hostId) {
                return alive;
            }

            @Override
            public boolean isAliveForRouting(UUID hostId) {
                return alive;
            }
        };
    }
}
