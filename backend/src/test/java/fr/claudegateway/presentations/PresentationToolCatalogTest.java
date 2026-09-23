package fr.claudegateway.presentations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.billing.EntitlementSpace;
import fr.claudegateway.billing.SpaceEntitlementService;

/** La garde de {@code presentation_publish} (F-129 / SF-129-02) : donné sous droit d'espace, vide sinon. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresentationToolCatalogTest {

    @Mock private SpaceEntitlementService entitlements;
    @Mock private Workspace workspace;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("terminal de projet (Forge) avec droit → l'outil est donné")
    void givenUnderForgeEntitlement() {
        when(workspace.isTeamsTerminal()).thenReturn(false);
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        PresentationToolCatalog catalog = new PresentationToolCatalog(entitlements);

        assertThat(catalog.isOpenFor(userId, workspace)).isTrue();
        assertThat(catalog.toolsFor(userId, workspace)).singleElement()
                .extracting(fr.claudegateway.agent.AgentTool::name)
                .isEqualTo(PresentationToolCatalog.PUBLISH);
        assertThat(PresentationToolCatalog.spaceOf(workspace)).isEqualTo(PresentationSpace.FORGE);
    }

    @Test
    @DisplayName("terminal Teams (Vigie) sans droit → aucun outil")
    void emptyWithoutVigieEntitlement() {
        when(workspace.isTeamsTerminal()).thenReturn(true);
        when(entitlements.isEntitled(userId, EntitlementSpace.VIGIE)).thenReturn(false);
        PresentationToolCatalog catalog = new PresentationToolCatalog(entitlements);

        assertThat(catalog.isOpenFor(userId, workspace)).isFalse();
        assertThat(catalog.toolsFor(userId, workspace)).isEmpty();
        assertThat(PresentationToolCatalog.spaceOf(workspace)).isEqualTo(PresentationSpace.VIGIE);
    }

    @Test
    @DisplayName("SF-142-02 : le guide apprend le diagramme-as-code inséré en slide (renvoi skill pptx)")
    void guideTeachesDiagramInSlide() {
        assertThat(PresentationToolCatalog.GUIDE)
                .containsIgnoringCase("diagramme")
                .containsIgnoringCase("mermaid")
                .contains("add_picture")
                .containsIgnoringCase("sandbox")
                .containsIgnoringCase("factuel");
    }

    @Test
    @DisplayName("SF-142-07 : le guide apprend les icônes cloud officielles, rendues PAR LA GATEWAY")
    void guideTeachesOfficialCloudIconsViaDiagrams() {
        assertThat(PresentationToolCatalog.GUIDE)
                .contains("render_diagram")
                .contains("engine=cloud")
                .containsIgnoringCase("officielles")
                .contains("aws.rds")
                // Ce que le guide ne doit PLUS dire : installer un moteur sur la machine du client.
                .doesNotContain("graphviz")
                .doesNotContain("mmdc");
    }

    @Test
    @DisplayName("SF-142-04 : le guide renvoie à generate_image pour le DÉCORATIF, jamais l'architecture")
    void guideMentionsDecorativeImage() {
        assertThat(PresentationToolCatalog.GUIDE)
                .contains("generate_image")
                .containsIgnoringCase("décorati")
                .containsIgnoringCase("jamais");
    }

    @Test
    @DisplayName("catalogue vide (none) → jamais d'outil")
    void noneGivesNothing() {
        assertThat(PresentationToolCatalog.none().isOpenFor(userId, workspace)).isFalse();
        assertThat(PresentationToolCatalog.none().toolsFor(userId, workspace)).isEmpty();
    }

    @Test
    @DisplayName("abonnement illisible → fermé, comme toute garde")
    void closedOnEntitlementError() {
        when(workspace.isTeamsTerminal()).thenReturn(false);
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE))
                .thenThrow(new IllegalStateException("illisible"));
        PresentationToolCatalog catalog = new PresentationToolCatalog(entitlements);

        assertThat(catalog.isOpenFor(userId, workspace)).isFalse();
    }
}
