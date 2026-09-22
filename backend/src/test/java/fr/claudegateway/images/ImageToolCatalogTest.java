package fr.claudegateway.images;

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

/** La garde de {@code generate_image} (F-142 / SF-142-04) : donné sous droit d'espace, vide sinon. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageToolCatalogTest {

    @Mock private SpaceEntitlementService entitlements;
    @Mock private Workspace workspace;

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("terminal de projet (Forge) avec droit → l'outil est donné")
    void givenUnderForgeEntitlement() {
        when(workspace.isTeamsTerminal()).thenReturn(false);
        when(entitlements.isEntitled(userId, EntitlementSpace.FORGE)).thenReturn(true);
        ImageToolCatalog catalog = new ImageToolCatalog(entitlements);

        assertThat(catalog.isOpenFor(userId, workspace)).isTrue();
        assertThat(catalog.toolsFor(userId, workspace)).singleElement()
                .extracting(fr.claudegateway.agent.AgentTool::name)
                .isEqualTo(ImageToolCatalog.GENERATE);
        assertThat(ImageToolCatalog.spaceOf(workspace)).isEqualTo(ImageSpace.FORGE);
    }

    @Test
    @DisplayName("terminal Teams (Vigie) sans droit → aucun outil")
    void emptyWithoutVigieEntitlement() {
        when(workspace.isTeamsTerminal()).thenReturn(true);
        when(entitlements.isEntitled(userId, EntitlementSpace.VIGIE)).thenReturn(false);
        ImageToolCatalog catalog = new ImageToolCatalog(entitlements);

        assertThat(catalog.isOpenFor(userId, workspace)).isFalse();
        assertThat(catalog.toolsFor(userId, workspace)).isEmpty();
        assertThat(ImageToolCatalog.spaceOf(workspace)).isEqualTo(ImageSpace.VIGIE);
    }

    @Test
    @DisplayName("catalogue vide (none) : jamais d'outil")
    void noneGivesNothing() {
        ImageToolCatalog none = ImageToolCatalog.none();
        assertThat(none.isOpenFor(userId, workspace)).isFalse();
        assertThat(none.toolsFor(userId, workspace)).isEmpty();
    }

    @Test
    @DisplayName("CA6 — le guide enseigne la frontière : décoratif seulement, JAMAIS l'architecture")
    void guideTeachesDecorativeOnly() {
        assertThat(ImageToolCatalog.GUIDE)
                .containsIgnoringCase("décorati")
                .containsIgnoringCase("couverture")
                .containsIgnoringCase("jamais")
                .containsIgnoringCase("architecture")
                .containsIgnoringCase("diagramme-as-code");
        // La définition de l'outil porte aussi le garde-fou.
        assertThat(ImageToolCatalog.definition().description())
                .containsIgnoringCase("décorati").containsIgnoringCase("jamais");
    }
}
