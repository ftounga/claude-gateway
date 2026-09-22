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
        // La lecture du transcript n'écrit rien : elle reste hors de la liste des écritures.
        assertThat(RadarToolCatalog.isWrite(RadarToolCatalog.MEETING_TRANSCRIPT)).isFalse();
        assertThat(RadarToolCatalog.isRead(RadarToolCatalog.MEETING_TRANSCRIPT)).isTrue();
        assertThat(RadarToolCatalog.WRITE).hasSize(5);
    }

    @Test
    @DisplayName("garde : terminal Teams + poste + droit Vigie + poste activé dans la Vigie")
    void guard() {
        when(teamsAccess.hasAccess(userId)).thenReturn(true);
        when(spaces.isActive(userId, hostId, ClientSpace.VIGIE)).thenReturn(true);
        // F-147 / SF-147-04 : sept outils — les six du registre, plus la LECTURE du texte d'une réunion.
        assertThat(catalog.toolsFor(userId, teamsTerminal())).hasSize(7);

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

    @Test
    @DisplayName("SF-104-03 — cibles lisibles des six outils : jamais d'identifiant, paramètres absents tolérés")
    void stepTargets() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String id = UUID.randomUUID().toString();
        assertThat(RadarToolCatalog.stepTarget(RadarToolCatalog.FIND_SUBJECT, mapper.readTree("{\"query\":\"MFA\"}")))
                .isEqualTo("Radar · recherche « MFA »");
        assertThat(RadarToolCatalog.stepTarget(RadarToolCatalog.FIND_SUBJECT, null)).isEqualTo("Radar · sujets ouverts");
        assertThat(RadarToolCatalog.stepTarget(RadarToolCatalog.UPDATE_SUBJECT,
                mapper.readTree("{\"new_subject_name\":\"Accès Sophie\"}"))).isEqualTo("Radar · nouveau sujet « Accès Sophie »");
        assertThat(RadarToolCatalog.stepTarget(RadarToolCatalog.UPDATE_SUBJECT,
                mapper.readTree("{\"subject_id\":\"" + id + "\",\"state\":\"WAITING\",\"next_step\":\"x\"}")))
                .isEqualTo("Radar · sujet : état, prochaine étape");
        assertThat(RadarToolCatalog.stepTarget(RadarToolCatalog.CLOSE_SUBJECT,
                mapper.readTree("{\"subject_id\":\"" + id + "\"}"))).isEqualTo("Radar · clôture d'un sujet");
        assertThat(RadarToolCatalog.stepTarget(RadarToolCatalog.ADD_ENGAGEMENT,
                mapper.readTree("{\"subject_id\":\"" + id + "\",\"description\":\"Présenter Sophie à Karim\"}")))
                .isEqualTo("Radar · engagement « Présenter Sophie à Karim »");
        assertThat(RadarToolCatalog.stepTarget(RadarToolCatalog.MARK_ENGAGEMENT,
                mapper.readTree("{\"commitment_id\":\"" + id + "\",\"status\":\"done\"}"))).isEqualTo("Radar · engagement tenu");
        assertThat(RadarToolCatalog.stepTarget(RadarToolCatalog.MERGE_SUBJECTS, mapper.readTree("{}")))
                .isEqualTo("Radar · fusion de deux sujets");
        for (String tool : RadarToolCatalog.CATALOG) {
            assertThat(RadarToolCatalog.stepTarget(tool, mapper.readTree("{\"subject_id\":\"" + id + "\",\"commitment_id\":\""
                    + id + "\"}"))).doesNotContain(id).startsWith("Radar");
        }
    }
}
