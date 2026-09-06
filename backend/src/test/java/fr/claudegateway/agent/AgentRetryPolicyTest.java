package fr.claudegateway.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Règle de réessai d'un appel de la boucle d'agent (F-39 / SF-39-11). Logique pure : elle se vérifie
 * sans horloge et sans serveur.
 */
class AgentRetryPolicyTest {

    private final AgentRetryPolicy policy = new AgentRetryPolicy(3);

    @Test
    void treatsOnlyTheTemporaryRefusalsAsRetryable() {
        assertThat(AgentRetryPolicy.retryableStatus(429)).isTrue();
        assertThat(AgentRetryPolicy.retryableStatus(529)).isTrue();
    }

    @Test
    void neverRetriesTheOtherStatuses() {
        // 500 compris : la création de message a peut-être été traitée, et la rejouer ferait
        // exécuter deux fois la même série d'outils sur la machine de l'utilisateur (D-L6-2).
        assertThat(AgentRetryPolicy.retryableStatus(400)).isFalse();
        assertThat(AgentRetryPolicy.retryableStatus(401)).isFalse();
        assertThat(AgentRetryPolicy.retryableStatus(404)).isFalse();
        assertThat(AgentRetryPolicy.retryableStatus(500)).isFalse();
        assertThat(AgentRetryPolicy.retryableStatus(503)).isFalse();
    }

    @Test
    void countsTheAttemptsAllowedByTheConfiguration() {
        assertThat(policy.hasAttemptLeft(1)).isTrue();
        assertThat(policy.hasAttemptLeft(2)).isTrue();
        assertThat(policy.hasAttemptLeft(3)).isFalse();
    }

    @Test
    void honoursTheRetryAfterHeaderInSeconds() {
        assertThat(policy.delayMs(1, "2", 0)).isEqualTo(2_000L);
    }

    @Test
    void capsAnOutlandishRetryAfterHeader() {
        assertThat(policy.delayMs(1, "999", 0)).isEqualTo(AgentRetryPolicy.MAX_DELAY_MS);
    }

    @Test
    void ignoresARetryAfterHeaderItCannotRead() {
        // Date HTTP, valeur vide, valeur négative : aucune n'est interprétée — une horloge décalée
        // produirait une attente absurde. Le repli exponentiel, lui, ne dépend de rien.
        for (String header : new String[] { "Wed, 21 Oct 2026 07:28:00 GMT", "", "  ", "-5", null }) {
            assertThat(policy.delayMs(1, header, 0))
                    .isBetween(AgentRetryPolicy.INITIAL_DELAY_MS / 2, AgentRetryPolicy.INITIAL_DELAY_MS);
        }
    }

    @Test
    void growsTheBackoffExponentiallyWithinItsJitterBounds() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            long base = Math.min(AgentRetryPolicy.INITIAL_DELAY_MS << (attempt - 1),
                    AgentRetryPolicy.MAX_DELAY_MS);
            assertThat(policy.delayMs(attempt, null, 0)).isBetween(base / 2, base);
        }
    }

    @Test
    void neverExceedsTheCumulativeWaitBudget() {
        // Le budget de tour n'est vérifié qu'entre deux itérations, jamais pendant un appel : sans
        // cette borne, rien ne rattraperait une attente décidée par un Retry-After généreux.
        long remaining = 1_500L;
        assertThat(policy.delayMs(1, "30", AgentRetryPolicy.MAX_TOTAL_WAIT_MS - remaining))
                .isEqualTo(remaining);
        assertThat(policy.delayMs(1, "30", AgentRetryPolicy.MAX_TOTAL_WAIT_MS))
                .isEqualTo(AgentRetryPolicy.NO_DELAY);
        assertThat(policy.delayMs(1, null, AgentRetryPolicy.MAX_TOTAL_WAIT_MS + 1))
                .isEqualTo(AgentRetryPolicy.NO_DELAY);
    }
}
