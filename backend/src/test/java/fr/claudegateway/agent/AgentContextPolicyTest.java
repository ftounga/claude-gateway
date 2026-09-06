package fr.claudegateway.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Politique de contexte d'un tour (F-39 / SF-39-12) : une <b>intention</b>, neutre vis-à-vis du
 * fournisseur. Le nom du mécanisme n'appartient qu'à {@link AnthropicAgentProvider}.
 */
class AgentContextPolicyTest {

    @Test
    void noneAsksForNothing() {
        assertThat(AgentContextPolicy.none().pruneToolResults()).isFalse();
    }

    @Test
    void aTurnWithoutAnExplicitPolicyBehavesAsBefore() {
        AgentTurnRequest request = new AgentTurnRequest("m", "s", java.util.List.of(),
                java.util.List.of(), null);
        assertThat(request.contextPolicy()).isEqualTo(AgentContextPolicy.none());

        AgentTurnRequest nullPolicy = new AgentTurnRequest("m", "s", java.util.List.of(),
                java.util.List.of(), null, AgentReasoning.none(), null);
        assertThat(nullPolicy.contextPolicy()).isEqualTo(AgentContextPolicy.none());
    }

    @Test
    void carriesItsThreeBounds() {
        AgentContextPolicy policy = new AgentContextPolicy(true, 200_000, 3, 20_000);
        assertThat(policy.pruneToolResults()).isTrue();
        assertThat(policy.triggerInputTokens()).isEqualTo(200_000);
        assertThat(policy.keepRecentToolResults()).isEqualTo(3);
        assertThat(policy.clearAtLeastInputTokens()).isEqualTo(20_000);
    }
}
