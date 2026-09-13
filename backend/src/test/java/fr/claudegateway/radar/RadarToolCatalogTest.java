package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.runner.host.ClientSpace;
import fr.claudegateway.runner.host.HostSpaceService;
import fr.claudegateway.teams.TeamsAccessService;

/** F-104 / SF-104-01 — le catalogue des outils Radar et sa garde. */
class RadarToolCatalogTest {

    private final TeamsAccessService teamsAccess = mock(TeamsAccessService.class);
    private final HostSpaceService spaces = mock(HostSpaceService.class);
    private final RadarToolCatalog catalog = new RadarToolCatalog(teamsAccess, spaces);
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private Workspace teamsTerminal() {
        Workspace workspace = new Workspace();
        workspace.setTeamsTerminal(true);
        workspace.setHostId(hostId);
        return workspace;
    }

    @Test
    @DisplayName("les six outils, dans l'ordre, tous préfixés radar_, chacun avec son schéma")
    void definitions() {
        assertThat(RadarToolCatalog.definitions()).extracting(AgentTool::name)
                .containsExactlyElementsOf(RadarToolCatalog.CATALOG)
                .allMatch(RadarToolCatalog::isRadarTool);
        assertThat(RadarToolCatalog.definitions()).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).containsEntry("type", "object");
        });
        assertThat(RadarToolCatalog.isWrite(RadarToolCatalog.FIND_SUBJECT)).isFalse();
        assertThat(RadarToolCatalog.WRITE).hasSize(5);
    }

    @Test
    @DisplayName("garde : terminal Teams + poste + droit Vigie + poste activé dans la Vigie")
    void guard() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);
        when(spaces.isActive(userId, hostId, ClientSpace.VIGIE)).thenReturn(true);
        assertThat(catalog.toolsFor(userId, teamsTerminal())).hasSize(6);

        Workspace project = teamsTerminal();
        project.setTeamsTerminal(false);
        assertThat(catalog.toolsFor(userId, project)).isEmpty();

        Workspace hostless = teamsTerminal();
        hostless.setHostId(null);
        assertThat(catalog.toolsFor(userId, hostless)).isEmpty();

        when(spaces.isActive(userId, hostId, ClientSpace.VIGIE)).thenThrow(new IllegalStateException("poste d'autrui"));
        assertThat(catalog.toolsFor(userId, teamsTerminal())).isEmpty();
    }

    @Test
    @DisplayName("sans droit Vigie ou hors Vigie : vide ; le catalogue none() ne donne jamais rien")
    void closed() {
        when(teamsAccess.hasAccess(userId)).thenReturn(false);
        when(spaces.isActive(userId, hostId, ClientSpace.VIGIE)).thenReturn(true);
        assertThat(catalog.toolsFor(userId, teamsTerminal())).isEmpty();

        when(teamsAccess.hasAccess(userId)).thenReturn(true);
        when(spaces.isActive(userId, hostId, ClientSpace.VIGIE)).thenReturn(false);
        assertThat(catalog.toolsFor(userId, teamsTerminal())).isEmpty();

        assertThat(RadarToolCatalog.none().toolsFor(userId, teamsTerminal())).isEmpty();
    }
}
