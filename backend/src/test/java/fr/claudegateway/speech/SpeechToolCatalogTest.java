package fr.claudegateway.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.billing.SpaceEntitlementService;

/** La garde de {@code voix_off} (F-215 / SF-215-01) : coupe-circuit, clé, droit de l'espace, cible poste. */
class SpeechToolCatalogTest {

    private final SpaceEntitlementService entitlements = mock(SpaceEntitlementService.class);
    private final UUID userId = UUID.randomUUID();

    private static Workspace workspace(WorkspaceExecutionTarget target) {
        return Workspace.builder().id(UUID.randomUUID()).name("w").projectPath("").hostId(UUID.randomUUID())
                .executionTarget(target).build();
    }

    private SpeechToolCatalog catalog(String key, boolean enabled) {
        return new SpeechToolCatalog(entitlements,
                OpenAiSpeechProviderTest.props("https://api.openai.com/v1", key, enabled));
    }

    @Test
    @DisplayName("Ouvert : voix allumée et configurée, droit de l'espace, cible poste — définition fixe")
    void openWhenAllConditionsHold() {
        when(entitlements.isEntitled(any(UUID.class), any(fr.claudegateway.billing.EntitlementSpace.class))).thenReturn(true);

        var tools = catalog("sk", true).toolsFor(userId, workspace(WorkspaceExecutionTarget.RUNNER));

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("voix_off");
        // Préfixe stable : deux constructions rendent exactement la même définition.
        assertThat(catalog("sk", true).toolsFor(userId, workspace(WorkspaceExecutionTarget.RUNNER)))
                .isEqualTo(tools);
    }

    @Test
    @DisplayName("Fermé : coupe-circuit, clé absente, sans droit, projet hébergé, catalogue vide")
    void closedOtherwise() {
        when(entitlements.isEntitled(any(UUID.class), any(fr.claudegateway.billing.EntitlementSpace.class))).thenReturn(true);
        Workspace runner = workspace(WorkspaceExecutionTarget.RUNNER);
        assertThat(catalog("sk", false).toolsFor(userId, runner)).isEmpty();
        assertThat(catalog("", true).toolsFor(userId, runner)).isEmpty();
        assertThat(catalog("sk", true).toolsFor(userId, workspace(WorkspaceExecutionTarget.SANDBOX))).isEmpty();
        assertThat(SpeechToolCatalog.none().toolsFor(userId, runner)).isEmpty();
        when(entitlements.isEntitled(any(UUID.class), any(fr.claudegateway.billing.EntitlementSpace.class))).thenReturn(false);
        assertThat(catalog("sk", true).toolsFor(userId, runner)).isEmpty();
    }
}
