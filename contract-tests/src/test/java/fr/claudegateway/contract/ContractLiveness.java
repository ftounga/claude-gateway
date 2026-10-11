package fr.claudegateway.contract;

import java.time.Duration;
import java.util.UUID;

import fr.claudegateway.runner.RunnerLiveness;

/**
 * Un poste qui bat toujours (F-97 / SF-97-01) : les contrats de trames ne portent pas sur le
 * battement. Même doublure que {@code RunnerLivenessStubs} du backend, dont les classes de test ne
 * sont pas sur le chemin de ce module.
 */
final class ContractLiveness {

    private ContractLiveness() {
    }

    static RunnerLiveness alwaysAlive() {
        return new RunnerLiveness(null, Duration.ofSeconds(90)) {
            @Override
            public boolean isAlive(UUID userId, UUID hostId) {
                return true;
            }

            @Override
            public boolean isAliveForRouting(UUID hostId) {
                return true;
            }
        };
    }
}
