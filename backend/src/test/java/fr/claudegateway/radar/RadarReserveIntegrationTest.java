package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.radar.analysis.ConfiguredRadarReserve;
import fr.claudegateway.radar.analysis.RadarAnalysisBatch;
import fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus;
import fr.claudegateway.radar.analysis.RadarAnalysisIntake;
import fr.claudegateway.radar.analysis.RadarAnalysisProperties;
import fr.claudegateway.radar.analysis.RadarAnalysisQueue;
import fr.claudegateway.radar.analysis.RadarBatchAnalyzer;
import fr.claudegateway.radar.analysis.RadarExchangeAnalyzer;
import fr.claudegateway.radar.analysis.RadarExchangeBatch;
import fr.claudegateway.radar.analysis.RadarExtractionWriter;
import fr.claudegateway.radar.analysis.RadarExtractor;
import fr.claudegateway.radar.analysis.RadarReadingProperties;
import fr.claudegateway.radar.analysis.RadarRegistrySnapshot;
import fr.claudegateway.radar.analysis.RadarReserveProperties;
import fr.claudegateway.radar.analysis.RadarTriage;
import fr.claudegateway.teams.TeamsAccessService;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;

/**
 * F-101 / SF-101-05 — <b>la réserve et la mesure</b> : l'analyse s'arrête proprement à réserve
 * épuisée, sans perdre un lot ; la synchro dit ce qu'elle a coûté et combien de rattachements ont été
 * corrigés ; rien d'un autre poste n'entre dans le compte.
 */
class RadarReserveIntegrationTest extends RadarIntegrationTestBase {

    @Autowired private RadarAnalysisIntake intake;
    @Autowired private RadarSyncRepository syncRepository;
    @Autowired private RadarAnalysisProperties analysisProperties;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;
    @Autowired private ModelCatalog modelCatalog;
    @Autowired private ByokKeyService byokKeyService;
    @Autowired private RadarRegistrySnapshot snapshot;
    @Autowired private RadarExtractionWriter writer;

    private final AIProvider aiProvider = mock(AIProvider.class);
    private final TeamsAccessService teamsAccess = mock(TeamsAccessService.class);
    private static final OffsetDateTime AT = OffsetDateTime.now().minusHours(1).withNano(0);

    @BeforeEach
    void provider() {
        when(teamsAccess.hasAccess(any(UUID.class))).thenReturn(true);
        when(aiProvider.complete(any())).thenAnswer(invocation -> {
            ChatCompletionRequest request = invocation.getArgument(0);
            return request.system().contains("===TRI===")
                    ? new ChatCompletionResult("===TRI===\n{\"retenus\": [\"E1\"]}", "m", 40_000, 1_000, 0, 0)
                    : new ChatCompletionResult("===RADAR===\n{\"sujets\": [{\"sujet\": \"nouveau\", \"nom\": \"Licence\", \"preuves\": [\"M1\"]}]}",
                            "m", 50_000, 2_000, 0, 0);
        });
    }

    private RadarAnalysisQueue queue(RadarReserveProperties reserve) {
        RadarReadingProperties reading = RadarReadingProperties.defaults();
        RadarBatchAnalyzer analyzer = new RadarExchangeAnalyzer(teamsAccess,
                new RadarTriage(aiProvider, modelCatalog, byokKeyService, reading),
                new RadarExtractor(aiProvider, modelCatalog, byokKeyService, reading), snapshot, writer,
                new ConfiguredRadarReserve(syncRepository, reserve), clock);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("analyzer", analyzer);
        return new RadarAnalysisQueue(analysisBatches, analysisLeases, syncRepository,
                factory.getBeanProvider(RadarBatchAnalyzer.class), analysisProperties, objectMapper, transactionManager,
                clock);
    }

    private static RadarExchangeBatch batch(String key) {
        return new RadarExchangeBatch(key, List.of(new RadarExchangeBatch.Exchange(RadarEvidenceSource.TEAMS_MESSAGE,
                "19:" + key, null, null, List.of(new RadarExchangeBatch.Message(key + "/1", AT, "marc@client.fr",
                        "Marc", null, false, "La licence est signée, on démarre.", null)))));
    }

    private RadarAnalysisBatch reload(UUID id) {
        return analysisBatches.findById(id).orElseThrow();
    }

    /** Une synchro du poste, commencée à cet instant, ayant déjà consommé ces jetons. */
    private RadarSync spent(RadarScope scope, OffsetDateTime startedAt, long tokens) {
        RadarSync sync = registry.startSync(scope);
        sync.setConsumedTokens(tokens);
        syncRepository.save(sync);
        // started_at n'est pas modifiable par l'entité : on remonte le temps en base.
        jdbc.update("update radar_syncs set started_at = ? where id = ?", startedAt, sync.getId());
        return syncRepository.findById(sync.getId()).orElseThrow();
    }

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    @DisplayName("Réserve mensuelle épuisée : lots reportés au mois suivant, aucun appel, brut conservé")
    void monthlyReserveExhausted() {
        spent(aliceA, OffsetDateTime.now().minusMinutes(30), 3_000_000);
        RadarSync sync = registry.startSync(aliceA);
        UUID first = intake.submit(aliceA, sync.getId(), batch("lot-1")).batchId();
        UUID second = intake.submit(aliceA, sync.getId(), batch("lot-2")).batchId();

        queue(RadarReserveProperties.defaults()).runOnce();

        for (UUID id : List.of(first, second)) {
            RadarAnalysisBatch deferred = reload(id);
            assertThat(deferred.getStatus()).isEqualTo(RadarAnalysisBatchStatus.DEFERRED);
            assertThat(deferred.getFailureCode()).isEqualTo("RESERVE_EXHAUSTED");
            assertThat(deferred.getPayload()).isNotNull();
            assertThat(deferred.getNextAttemptAt()).isEqualTo(RadarReserveProperties.nextMonthStart(OffsetDateTime.now()));
        }
        verify(aiProvider, never()).complete(any());
    }

    @Test
    @DisplayName("Un autre poste, un autre utilisateur ou le mois dernier n'entament pas la réserve")
    void onlyThisHostThisMonth() {
        spent(aliceB, OffsetDateTime.now().minusMinutes(30), 3_000_000);
        spent(bobScope, OffsetDateTime.now().minusMinutes(30), 3_000_000);
        spent(aliceA, RadarReserveProperties.monthStart(OffsetDateTime.now()).minusDays(2), 3_000_000);
        RadarSync sync = registry.startSync(aliceA);
        UUID id = intake.submit(aliceA, sync.getId(), batch("lot-1")).batchId();

        queue(RadarReserveProperties.defaults()).runOnce();

        assertThat(reload(id).getStatus()).isEqualTo(RadarAnalysisBatchStatus.DONE);
        assertThat(syncRepository.findById(sync.getId()).orElseThrow().getConsumedTokens()).isEqualTo(93_000);
    }

    @Test
    @DisplayName("Réserve épuisée par le tri : report, tri compté, rien d'écrit ; plafond par synchro")
    void exhaustedByTriageAndPerSyncCeiling() {
        spent(aliceA, OffsetDateTime.now().minusMinutes(30), 3_000_000 - 30_000);
        RadarSync sync = registry.startSync(aliceA);
        UUID id = intake.submit(aliceA, sync.getId(), batch("lot-1")).batchId();

        queue(RadarReserveProperties.defaults()).runOnce();

        RadarAnalysisBatch deferred = reload(id);
        assertThat(deferred.getStatus()).isEqualTo(RadarAnalysisBatchStatus.DEFERRED);
        assertThat(deferred.getTriageInputTokens()).isEqualTo(40_000);
        assertThat(deferred.getExtractionInputTokens()).isZero();
        assertThat(subjects.count()).isZero();

        // Plafond par synchro : la synchro de Bob s'arrête à 50 000, une nouvelle synchro repart.
        RadarReserveProperties ceiling = new RadarReserveProperties(null, 50_000L);
        RadarSync bobSync = spent(bobScope, OffsetDateTime.now().minusMinutes(10), 60_000);
        UUID bobBatch = intake.submit(bobScope, bobSync.getId(), batch("lot-b1")).batchId();
        queue(ceiling).runOnce();
        assertThat(reload(bobBatch).getStatus()).isEqualTo(RadarAnalysisBatchStatus.DEFERRED);
        RadarSync fresh = registry.startSync(bobScope);
        UUID freshBatch = intake.submit(bobScope, fresh.getId(), batch("lot-b2")).batchId();
        queue(ceiling).runOnce();
        assertThat(reload(freshBatch).getStatus()).isEqualTo(RadarAnalysisBatchStatus.DONE);
    }

    @Test
    @DisplayName("La mesure par synchro : coût, arrêt sur réserve, lots non lus, corrections dans la fenêtre")
    void measure() throws Exception {
        OffsetDateTime t0 = OffsetDateTime.now().minusDays(3);
        RadarSync older = spent(aliceA, t0, 0);
        RadarSync newer = spent(aliceA, t0.plusDays(2), 0);
        analysisBatches.save(RadarAnalysisBatch.builder().userId(aliceA.userId()).hostId(aliceA.hostId())
                .syncId(older.getId()).batchKey("k1").status(RadarAnalysisBatchStatus.DONE).subjectsAttached(3)
                .subjectsCreated(1).extractionInputTokens(1_000_000).extractionOutputTokens(100_000)
                .receivedAt(t0).expiresAt(t0.plusDays(7)).build());
        analysisBatches.save(RadarAnalysisBatch.builder().userId(aliceA.userId()).hostId(aliceA.hostId())
                .syncId(older.getId()).batchKey("k2").status(RadarAnalysisBatchStatus.EXPIRED)
                .receivedAt(t0).expiresAt(t0.plusDays(7)).build());
        analysisBatches.save(RadarAnalysisBatch.builder().userId(aliceA.userId()).hostId(aliceA.hostId())
                .syncId(newer.getId()).batchKey("k3").status(RadarAnalysisBatchStatus.DEFERRED)
                .failureCode("RESERVE_EXHAUSTED").receivedAt(t0).expiresAt(t0.plusDays(7)).build());
        RadarSubject subject = registry.createSubject(aliceA, "Sujet", null, ids(proof(aliceA, "x")));
        correction(aliceA, subject, RadarCorrectionAction.MERGE, t0.plusHours(1), null);
        correction(aliceA, subject, RadarCorrectionAction.SPLIT, t0.plusDays(1), null);
        correction(aliceA, subject, RadarCorrectionAction.SPLIT, t0.plusHours(2), t0.plusHours(3)); // annulée
        correction(aliceA, subject, RadarCorrectionAction.RENAME, t0.plusHours(4), null); // pas un rattachement
        correction(aliceA, subject, RadarCorrectionAction.MERGE, t0.plusDays(2).plusHours(1), null); // synchro suivante
        RadarSubject bobs = registry.createSubject(bobScope, "Sujet", null, ids(proof(bobScope, "x")));
        correction(bobScope, bobs, RadarCorrectionAction.SPLIT, t0.plusHours(1), null); // autre compte

        mockMvc.perform(get(url(aliceA, "/syncs")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].id").value(older.getId().toString()))
                .andExpect(jsonPath("$[1].analysis.costUsd").isNumber())
                .andExpect(jsonPath("$[1].analysis.stoppedOnReserve").value(false))
                .andExpect(jsonPath("$[1].analysis.unreadBatches").value(1))
                .andExpect(jsonPath("$[1].analysis.attachmentCorrections").value(2))
                .andExpect(jsonPath("$[1].analysis.attachmentCorrectionRate").value(0.5))
                .andExpect(jsonPath("$[0].analysis.stoppedOnReserve").value(true))
                .andExpect(jsonPath("$[0].analysis.attachmentCorrections").value(1))
                .andExpect(jsonPath("$[0].analysis.attachmentCorrectionRate").doesNotExist());
    }

    private void correction(RadarScope scope, RadarSubject subject, RadarCorrectionAction action, OffsetDateTime at,
            OffsetDateTime undoneAt) {
        corrections.save(RadarCorrection.builder().userId(scope.userId()).hostId(scope.hostId())
                .subjectId(subject.getId()).targetKind(RadarCorrectionAction.Target.SUBJECT).targetId(subject.getId())
                .action(action).beforeValues("{}").afterValues("{}").createdAt(at).undoneAt(undoneAt).build());
    }

    @Test
    @DisplayName("GET /reserve : la réserve du poste ; 404 pour autrui ; 403 sans droit")
    void reserveEndpoint() throws Exception {
        spent(aliceA, OffsetDateTime.now().minusMinutes(5), 1_200_000);
        spent(aliceB, OffsetDateTime.now().minusMinutes(5), 500_000);

        mockMvc.perform(get(url(aliceA, "/reserve")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyTokens").value(3_000_000))
                .andExpect(jsonPath("$.consumedThisMonth").value(1_200_000))
                .andExpect(jsonPath("$.remainingThisMonth").value(1_800_000))
                .andExpect(jsonPath("$.perSyncTokens").value(0))
                .andExpect(jsonPath("$.resetsAt").isNotEmpty());

        mockMvc.perform(get(url(aliceA, "/reserve")).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        User carol = seedUser("carol-radar@example.com", UserRole.USER);
        RadarScope carolScope = new RadarScope(carol.getId(), seedHost(carol.getId(), "Poste de Carol"));
        mockMvc.perform(get(url(carolScope, "/reserve")).contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(carol)))
                .andExpect(status().isForbidden());
    }
}
