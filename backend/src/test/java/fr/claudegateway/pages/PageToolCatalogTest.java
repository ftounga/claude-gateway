package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.access.AccessGrantService;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.billing.AdministratorEntitlement;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;
import fr.claudegateway.billing.SubscriptionService;

/** La garde, le schéma et le guide de {@code page_publish} (F-109 / SF-109-02). */
class PageToolCatalogTest {

    private final UUID userId = UUID.randomUUID();

    private static Workspace terminal(WorkspaceExecutionTarget target, boolean teams) {
        Workspace workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        workspace.setExecutionTarget(target);
        workspace.setTeamsTerminal(teams);
        return workspace;
    }

    @Test
    @DisplayName("la garde lit le droit de l'espace du terminal, et seulement sur un poste")
    void guard() {
        SpaceEntitlementService entitlements = mock(SpaceEntitlementService.class);
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        when(entitlements.isEntitled(userId, EntitlementSpace.VIGIE)).thenReturn(false);
        PageToolCatalog catalog = new PageToolCatalog(entitlements);

        assertThat(catalog.isOpenFor(userId, terminal(WorkspaceExecutionTarget.RUNNER, false))).isTrue();
        assertThat(catalog.isOpenFor(userId, terminal(WorkspaceExecutionTarget.RUNNER, true))).isFalse();
        assertThat(catalog.isOpenFor(userId, terminal(WorkspaceExecutionTarget.SANDBOX, false))).isFalse();
        assertThat(catalog.isOpenFor(null, terminal(WorkspaceExecutionTarget.RUNNER, false))).isFalse();
        assertThat(PageToolCatalog.none().toolsFor(userId, terminal(WorkspaceExecutionTarget.RUNNER, false))).isEmpty();
    }

    @Test
    @DisplayName("sur un projet hébergé, le droit n'est même pas lu")
    void sandboxDoesNotReadTheRight() {
        SpaceEntitlementService entitlements = mock(SpaceEntitlementService.class);
        new PageToolCatalog(entitlements).isOpenFor(userId, terminal(WorkspaceExecutionTarget.SANDBOX, false));
        verify(entitlements, never()).isEntitled(userId, EntitlementSpace.FORGE);
    }

    @Test
    @DisplayName("CA3 — un ADMIN sans abonnement reçoit l'outil (règle AdministratorEntitlement)")
    void administratorHasTheTool() {
        AdministratorEntitlement administrator = mock(AdministratorEntitlement.class);
        when(administrator.isAdministrator(userId)).thenReturn(true);
        SpaceEntitlementService entitlements = new SpaceEntitlementService(mock(SubscriptionService.class),
                mock(AccessGrantService.class), administrator);
        PageToolCatalog catalog = new PageToolCatalog(entitlements);

        assertThat(catalog.toolsFor(userId, terminal(WorkspaceExecutionTarget.RUNNER, false)))
                .extracting(AgentTool::name).containsExactly(PageToolCatalog.PUBLISH);
        assertThat(catalog.toolsFor(userId, terminal(WorkspaceExecutionTarget.RUNNER, true)))
                .extracting(AgentTool::name).containsExactly(PageToolCatalog.PUBLISH);
    }

    @Test
    @DisplayName("l'espace de rangement suit le terminal")
    void spaceOfTheTerminal() {
        assertThat(PageToolCatalog.spaceOf(terminal(WorkspaceExecutionTarget.RUNNER, true))).isEqualTo(PageSpace.VIGIE);
        assertThat(PageToolCatalog.spaceOf(terminal(WorkspaceExecutionTarget.RUNNER, false))).isEqualTo(PageSpace.FORGE);
    }

    @Test
    @DisplayName("le schéma : exactement ces champs, le titre seul obligatoire")
    @SuppressWarnings("unchecked")
    void schema() {
        Map<String, Object> schema = PageToolCatalog.definition().inputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties.keySet()).containsExactlyInAnyOrder("title", "description", "html", "path", "page_id",
                "attachments");
        assertThat(schema.get("required")).isEqualTo(java.util.List.of("title"));
    }

    @Test
    @DisplayName("CA4 — le guide dit l'accord, la charte, les deux thèmes, le téléphone, le contenu réel, les CDN, le réseau bloqué")
    void designGuide() {
        assertThat(PageToolCatalog.DESIGN_GUIDE)
                .contains("PROPOSE")
                .contains("charte du client")
                .contains("#0B1020").contains("#E07B39")
                .contains("prefers-color-scheme: dark")
                .contains("400 px")
                .contains("Contenu réel")
                .contains("Titre court et distinctif")
                .contains("https://cdnjs.cloudflare.com").contains("https://cdn.jsdelivr.net").contains("Google Fonts")
                .contains("AUCUN appel réseau")
                .contains("transcription brute")
                .contains("page_id");
    }
}
