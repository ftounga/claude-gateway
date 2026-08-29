package fr.claudegateway.atelier.agent;

import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Défauts et garde-fous de la configuration de l'agent (F-28 / SF-28-17).
 */
class AtelierAgentPropertiesTest {

    private AtelierAgentProperties withEffort(String effort) {
        return new AtelierAgentProperties(true, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, effort, Duration.ZERO);
    }

    @Test
    void defaultsToOpus5AtHighestUsefulEffort() {
        AtelierAgentProperties props = withEffort(null);

        assertThat(props.model()).isEqualTo("claude-opus-5");
        assertThat(props.effort()).isEqualTo("xhigh");
    }

    @Test
    void acceptsEveryEffortLevelOfferedByTheProvider() {
        for (String level : java.util.List.of("low", "medium", "high", "xhigh", "max")) {
            assertThat(withEffort(level).effort()).isEqualTo(level);
        }
    }

    @Test
    void anUnknownEffortFallsBackInsteadOfBreakingRuns() {
        // Une faute de frappe en configuration ne doit pas faire échouer les exécutions.
        assertThat(withEffort("tres-fort").effort()).isEqualTo("xhigh");
        assertThat(withEffort("  ").effort()).isEqualTo("xhigh");
    }
}
