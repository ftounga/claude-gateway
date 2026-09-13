package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.radar.analysis.RadarAnalysisIntake;
import fr.claudegateway.radar.analysis.RadarAnalysisQueue;
import fr.claudegateway.radar.analysis.RadarExchangeBatch;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * F-106 / SF-106-06 — <b>l'analyse propose le lien</b> : un échange lu qui nomme un projet du poste pose une
 * question (ligne {@code PROPOSED}), jamais un lien ; une paire refusée n'est jamais reproposée.
 */
class RadarProjectProposalIntegrationTest extends RadarIntegrationTestBase {

    @MockitoBean private AIProvider aiProvider;
    @MockitoBean private TeamsAccessService teamsAccess;

    @Autowired private RadarAnalysisIntake intake;
    @Autowired private RadarAnalysisQueue queue;
    @Autowired private RadarSubjectProjectRepository subjectProjects;
    @Autowired private WorkspaceRepository workspaceRepository;

    private static final OffsetDateTime AT = OffsetDateTime.now().minusHours(3).withNano(0);

    private static final String TRIAGE = "===TRI===\n{\"retenus\": [\"E1\"]}";
    private static final String EXTRACTION = """
            ===RADAR===
            {"sujets": [{"sujet": "nouveau", "nom": "Mise en production facturation", "preuves": ["M1"]}]}
            """;

    @Override
    protected void cleanRadarTables() {
        subjectProjects.deleteAll();
        workspaceRepository.deleteAll();
        super.cleanRadarTables();
    }

    @BeforeEach
    void provider() {
        when(teamsAccess.hasAccess(any(UUID.class))).thenReturn(true);
        when(aiProvider.complete(any())).thenAnswer(invocation -> {
            boolean triage = invocation.<fr.claudegateway.ai.ChatCompletionRequest>getArgument(0).system()
                    .contains("===TRI===");
            return new ChatCompletionResult(triage ? TRIAGE : EXTRACTION, "m", 100, 10, 0, 0);
        });
    }

    private Workspace project(RadarScope scope, String name, String path) {
        return workspaceRepository.save(Workspace.builder().userId(scope.userId()).hostId(scope.hostId())
                .name(name).projectPath(path).executionTarget(WorkspaceExecutionTarget.RUNNER).build());
    }

    private void analyze(RadarScope scope, String key, String text) {
        RadarSync sync = registry.startSync(scope);
        intake.submit(scope, sync.getId(), new RadarExchangeBatch(key, List.of(
                new RadarExchangeBatch.Exchange(RadarEvidenceSource.TEAMS_MESSAGE, "19:prod", "Prod", null, List.of(
                        new RadarExchangeBatch.Message(key + "-1", AT, "marc@client.fr", "Marc Durand", null, false,
                                text, "https://teams.microsoft.com/l/message/" + key)))))).batchId();
        queue.runOnce();
    }

    @Test
    @DisplayName("un échange qui nomme le dossier d'un projet propose le lien, sans le poser")
    void mentionProposes() {
        Workspace billing = project(aliceA, "facturation", "clients/Billing-API");
        project(aliceA, "infra", "infra");
        project(aliceA, "EDENRED", ""); // le projet racine porte le nom du client : jamais proposé
        project(aliceB, "billing-api", "billing-api"); // l'autre poste : jamais proposé

        analyze(aliceA, "lot-1", "La MEP de billing-api chez EDENRED est prévue jeudi.");

        assertThat(subjectProjects.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getWorkspaceId()).isEqualTo(billing.getId());
            assertThat(row.getHostId()).isEqualTo(aliceA.hostId());
            assertThat(row.getOrigin()).isEqualTo(RadarSubjectProjectOrigin.PROPOSED);
            assertThat(row.getState()).isEqualTo(RadarSubjectProjectState.PROPOSED);
        });
    }

    @Test
    @DisplayName("une paire refusée n'est jamais reproposée ; un texte sans nom de projet ne propose rien")
    void refusalIsRemembered() {
        Workspace billing = project(aliceA, "facturation", "billing-api");
        analyze(aliceA, "lot-1", "La MEP de billing-api est prévue jeudi.");
        RadarSubjectProject proposed = subjectProjects.findAll().get(0);
        proposed.setState(RadarSubjectProjectState.REFUSED);
        subjectProjects.save(proposed);

        analyze(aliceA, "lot-2", "Toujours rien sur billing-api, relance jeudi.");
        analyze(aliceA, "lot-3", "Le comité se tient mardi.");

        assertThat(subjectProjects.findAll()).singleElement().satisfies(row -> {
            assertThat(row.getWorkspaceId()).isEqualTo(billing.getId());
            assertThat(row.getState()).isEqualTo(RadarSubjectProjectState.REFUSED);
        });
    }
}
