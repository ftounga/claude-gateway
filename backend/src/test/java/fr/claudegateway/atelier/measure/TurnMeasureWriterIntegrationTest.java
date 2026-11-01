package fr.claudegateway.atelier.measure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * La mesure par tour en base (F-188 / SF-188-03) : écriture, purge d'un compte, purge par âge. Et la
 * divergence du ruban (F-229 / SF-229-01) : deux tours réels de la boucle, sur fournisseur bouchon, avec
 * la vraie base — nominal, troncature, compaction, deux chantiers, isolation entre utilisateurs.
 */
@SpringBootTest
@ActiveProfiles("test")
class TurnMeasureWriterIntegrationTest {

    @Autowired private TurnMeasureWriter writer;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static TurnMeasure measure(UUID userId, Instant at) {
        return new TurnMeasure(userId, UUID.randomUUID(), null, "claude-opus-5-5", at, 42_000L, 3,
                "réponse finale", 150_000L, 400_000L, 12_000L, 3_000L, 450_000L, 2_000L, 380_000L,
                15_000L, 1, 2, 1, null, null);
    }

    @Test
    void aTurnIsWrittenWithItsNumbersOnly() {
        UUID userId = UUID.randomUUID();
        writer.write(measure(userId, Instant.now()));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM atelier_turn_measures WHERE user_id = ?", userId);
        assertThat(row.get("iterations")).isEqualTo(3);
        assertThat(row.get("stop_cause")).isEqualTo("réponse finale");
        assertThat(((Number) row.get("max_context_tokens")).longValue()).isEqualTo(150_000L);
        assertThat(((Number) row.get("first_pass_cache_write")).longValue()).isEqualTo(12_000L);
        assertThat(((Number) row.get("later_cache_write")).longValue()).isEqualTo(3_000L);
        assertThat(row.get("runner_refusals")).isEqualTo(2);
        assertThat(row.get("runner_busy_waits")).isEqualTo(1);
    }

    @Test
    void deletingAnAccountPurgesOnlyItsMeasures() {
        UUID leaving = UUID.randomUUID();
        UUID staying = UUID.randomUUID();
        writer.write(measure(leaving, Instant.now()));
        writer.write(measure(staying, Instant.now()));

        writer.deleteByUserId(leaving);

        assertThat(count(leaving)).isZero();
        assertThat(count(staying)).isEqualTo(1);
    }

    @Test
    void measuresOlderThanTheRetentionArePurged() {
        UUID userId = UUID.randomUUID();
        writer.write(measure(userId, Instant.now().minus(Duration.ofDays(200))));
        writer.write(measure(userId, Instant.now().minus(Duration.ofDays(10))));

        writer.purgeOlderThan(Instant.now().minus(Duration.ofDays(TurnMeasureWriter.RETENTION_DAYS)));

        assertThat(count(userId)).isEqualTo(1);
    }

    @Test
    void aWriteFailureNeverReachesTheCaller() {
        TurnMeasureWriter broken = new TurnMeasureWriter(new JdbcTemplate()); // sans source : échoue
        broken.write(measure(UUID.randomUUID(), Instant.now()));
    }

    // ------------------------------------------------------------------ F-229 / SF-229-01

    @Test
    void theRibbonColumnsHoldOnlyHashesAndNumbers() {
        UUID userId = UUID.randomUUID();
        String fingerprint = "[[\"0123456789abcdef\",12],[\"fedcba9876543210\",3]]";
        writer.write(withRibbon(measure(userId, Instant.now()), new TurnMeasure.Ribbon(fingerprint,
                new RibbonDivergence(1, 3L, RibbonDivergence.Cause.RESULT_TRUNCATED), 42L)));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM atelier_turn_measures WHERE user_id = ?", userId);
        assertThat(row.get("ribbon_fingerprint")).hasToString(fingerprint);
        assertThat(row.get("prefix_divergence_index")).isEqualTo(1);
        assertThat(((Number) row.get("prefix_lost_chars")).longValue()).isEqualTo(3L);
        assertThat(row.get("prefix_divergence_cause")).isEqualTo("RESULT_TRUNCATED");
        assertThat(((Number) row.get("replay_cut_chars")).longValue()).isEqualTo(42L);
    }

    @Test
    void aFingerprintOutOfContractIsNotStored() {
        UUID userId = UUID.randomUUID();
        writer.write(withRibbon(measure(userId, Instant.now()),
                new TurnMeasure.Ribbon("[[\"du texte de conversation\",3]]", null, -5L)));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM atelier_turn_measures WHERE user_id = ?", userId);
        assertThat(row.get("ribbon_fingerprint")).isNull();
        assertThat(((Number) row.get("replay_cut_chars")).longValue()).isZero();
    }

    @Test
    void twoTurnsWithoutLongOutputProlongTheRibbon() {
        Thread thread = new Thread();
        thread.provider.enqueueToolCall("bash", "command", "ls");
        thread.provider.enqueueFinal("Premier tour fini.");
        thread.bashReturns("court");
        thread.chat(null, "premier");
        thread.provider.enqueueFinal("Second tour fini.");
        thread.chat(null, "second");

        List<Map<String, Object>> rows = thread.rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("prefix_divergence_cause")).isEqualTo("NO_PREVIOUS");
        assertThat(rows.get(0).get("prefix_divergence_index")).isNull();
        assertThat(((Number) rows.get(0).get("replay_cut_chars")).longValue()).isZero();
        assertThat(rows.get(1).get("prefix_divergence_index")).isEqualTo(-1);
        assertThat(((Number) rows.get(1).get("prefix_lost_chars")).longValue()).isZero();
        assertThat(rows.get(1).get("ribbon_fingerprint")).isNotNull();
    }

    @Test
    void aLongBashResultTruncatedAtStorageIsTheCause() {
        Thread thread = new Thread();
        thread.provider.enqueueToolCall("bash", "command", "cat gros.log");
        thread.provider.enqueueFinal("Lu.");
        thread.bashReturns("x".repeat(20_000));
        thread.chat(null, "lis le journal");
        long surplus = thread.sentToolResultLength(0) - 8_000L;
        assertThat(surplus).isPositive();
        thread.provider.enqueueFinal("Suite.");
        thread.chat(null, "et ensuite ?");

        List<Map<String, Object>> rows = thread.rows();
        assertThat(((Number) rows.get(0).get("replay_cut_chars")).longValue()).isEqualTo(surplus);
        assertThat(rows.get(1).get("prefix_divergence_cause")).isEqualTo("RESULT_TRUNCATED");
        assertThat(((Number) rows.get(1).get("prefix_lost_chars")).longValue()).isGreaterThanOrEqualTo(surplus);
        assertThat(((Number) rows.get(1).get("replay_cut_chars")).longValue()).isZero();
    }

    @Test
    void aCompactionBetweenTwoTurnsIsTheCause() {
        Thread thread = new Thread();
        thread.provider.enqueueFinal("Premier.");
        thread.chat(null, "premier");
        thread.workspace.setChatThreadSummary("## Objectif\nreprendre");
        thread.provider.enqueueFinal("Second.");
        thread.chat(null, "second");

        List<Map<String, Object>> rows = thread.rows();
        assertThat(rows.get(1).get("prefix_divergence_cause")).isEqualTo("COMPACTION");
        assertThat(rows.get(1).get("prefix_divergence_index")).isEqualTo(0);
    }

    @Test
    void twoChantiersOfOneTerminalEachCompareToTheirOwnPreviousTurn() {
        Thread thread = new Thread();
        thread.provider.enqueueFinal("Chantier 1, premier.");
        thread.chat(1, "un");
        thread.provider.enqueueFinal("Chantier 2, premier.");
        thread.chat(2, "deux");
        thread.provider.enqueueFinal("Chantier 1, second.");
        thread.chat(1, "un encore");
        thread.provider.enqueueFinal("Chantier 2, second.");
        thread.chat(2, "deux encore");

        List<Map<String, Object>> rows = thread.rows();
        assertThat(rows).extracting(r -> r.get("chantier_number")).containsExactly(1, 2, 1, 2);
        assertThat(rows.get(0).get("prefix_divergence_cause")).isEqualTo("NO_PREVIOUS");
        // Le premier tour du chantier 2 ne se compare pas au chantier 1 : il n'a pas de précédent.
        assertThat(rows.get(1).get("prefix_divergence_cause")).isEqualTo("NO_PREVIOUS");
        // Chaque second tour est comparé au ruban de SON chantier : ce qu'il perd se compte sur ce ruban-là.
        // (La consigne d'un tour de chantier porte la note de son fil, que l'historique rejoué ne porte
        // pas : le ruban diverge dès la consigne précédente — c'est ce que la mesure doit révéler.)
        assertThat(rows.get(2).get("prefix_divergence_index")).isEqualTo(0);
        assertThat(((Number) rows.get(2).get("prefix_lost_chars")).longValue())
                .isEqualTo(totalChars(rows.get(0))).isNotEqualTo(totalChars(rows.get(1)));
        assertThat(((Number) rows.get(3).get("prefix_lost_chars")).longValue())
                .isEqualTo(totalChars(rows.get(1)));
        assertThat(writer.previousFingerprint(thread.userId, thread.workspaceId, 1))
                .isEqualTo(rows.get(2).get("ribbon_fingerprint"));
        assertThat(writer.previousFingerprint(thread.userId, thread.workspaceId, null)).isNull();
    }

    private static long totalChars(Map<String, Object> row) {
        return RibbonFingerprint.parse(String.valueOf(row.get("ribbon_fingerprint"))).charsFrom(0);
    }

    @Test
    void bobIsNeverComparedToAlice() {
        UUID sharedWorkspace = UUID.randomUUID();
        Thread alice = new Thread(UUID.randomUUID(), sharedWorkspace);
        alice.provider.enqueueFinal("Pour Alice.");
        alice.chat(null, "alice");
        Thread bob = new Thread(UUID.randomUUID(), sharedWorkspace);
        bob.provider.enqueueFinal("Pour Bob.");
        bob.chat(null, "bob");

        assertThat(writer.previousFingerprint(bob.userId, sharedWorkspace, null))
                .isEqualTo(bob.rows().get(0).get("ribbon_fingerprint"))
                .isNotEqualTo(alice.rows().get(0).get("ribbon_fingerprint"));
        assertThat(bob.rows().get(0).get("prefix_divergence_cause")).isEqualTo("NO_PREVIOUS");
    }

    @Test
    void theRequestSentIsIdenticalWithAndWithoutTheMeasure() {
        Thread measured = new Thread();
        Thread plain = new Thread();
        plain.service.setTurnMeasureWriter(null);
        for (Thread thread : List.of(measured, plain)) {
            thread.provider.enqueueToolCall("bash", "command", "cat gros.log");
            thread.provider.enqueueFinal("Lu.");
            thread.bashReturns("y".repeat(12_000));
            thread.chat(null, "lis");
            thread.provider.enqueueFinal("Suite.");
            thread.chat(null, "ensuite");
        }

        assertThat(measured.provider.requestSnapshots).hasSize(3);
        assertThat(rendered(measured.provider.requestSnapshots))
                .isEqualTo(rendered(plain.provider.requestSnapshots));
    }

    /** Le corps tel que le fournisseur le reçoit, sérialisé : rôles, blocs et champs, dans l'ordre. */
    private static List<String> rendered(List<List<fr.claudegateway.agent.AgentMessage>> requests) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return requests.stream().map(messages -> {
            try {
                return mapper.writeValueAsString(messages);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private static TurnMeasure withRibbon(TurnMeasure m, TurnMeasure.Ribbon ribbon) {
        return new TurnMeasure(m.userId(), m.workspaceId(), m.hostId(), m.model(), m.occurredAt(),
                m.durationMs(), m.iterations(), m.stopCause(), m.maxContextTokens(), m.totalContextTokens(),
                m.firstPassCacheWrite(), m.laterCacheWrite(), m.inputTokens(), m.outputTokens(),
                m.cacheReadTokens(), m.cacheWriteTokens(), m.webSearchRequests(), m.runnerRefusals(),
                m.runnerBusyWaits(), m.chantierNumber(), ribbon);
    }

    /**
     * Un fil d'atelier réel — la vraie boucle, le vrai rejeu, la vraie base de mesure — dont seuls le
     * fournisseur (bouchon), le poste et l'historique (en mémoire) sont simulés.
     */
    private final class Thread {

        final UUID userId;
        final UUID workspaceId;
        final UUID hostId = UUID.randomUUID();
        final fr.claudegateway.agent.StubAiAgentProvider provider = new fr.claudegateway.agent.StubAiAgentProvider();
        final fr.claudegateway.atelier.Workspace workspace = new fr.claudegateway.atelier.Workspace();
        final fr.claudegateway.atelier.AtelierChatService service;
        private final List<fr.claudegateway.atelier.AtelierMessage> history = new java.util.ArrayList<>();
        private final fr.claudegateway.runner.exec.RunnerToolGateway runner =
                org.mockito.Mockito.mock(fr.claudegateway.runner.exec.RunnerToolGateway.class);
        private final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();

        Thread() {
            this(UUID.randomUUID(), UUID.randomUUID());
        }

        Thread(UUID userId, UUID workspaceId) {
            this.userId = userId;
            this.workspaceId = workspaceId;
            fr.claudegateway.atelier.WorkspaceService workspaceService =
                    org.mockito.Mockito.mock(fr.claudegateway.atelier.WorkspaceService.class);
            fr.claudegateway.atelier.AtelierMessageRepository repository =
                    org.mockito.Mockito.mock(fr.claudegateway.atelier.AtelierMessageRepository.class);
            fr.claudegateway.byok.ByokKeyService byok = org.mockito.Mockito.mock(fr.claudegateway.byok.ByokKeyService.class);
            fr.claudegateway.quota.QuotaService quota = org.mockito.Mockito.mock(fr.claudegateway.quota.QuotaService.class);
            fr.claudegateway.runner.exec.RunnerConfirmationGate gate =
                    org.mockito.Mockito.mock(fr.claudegateway.runner.exec.RunnerConfirmationGate.class);
            fr.claudegateway.runner.host.RunnerHostService hosts =
                    org.mockito.Mockito.mock(fr.claudegateway.runner.host.RunnerHostService.class);
            fr.claudegateway.git.GitTokenService gitTokens = org.mockito.Mockito.mock(fr.claudegateway.git.GitTokenService.class);
            fr.claudegateway.git.GitHubClient gitHub = org.mockito.Mockito.mock(fr.claudegateway.git.GitHubClient.class);
            service = new fr.claudegateway.atelier.AtelierChatService(workspaceService, repository,
                    (fr.claudegateway.agent.AiAgentProvider) provider, byok, quota,
                    new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokens, gitHub,
                            new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                    runner, org.mockito.Mockito.mock(fr.claudegateway.runner.channel.RunnerCallDispatcher.class),
                    gate, org.mockito.Mockito.mock(fr.claudegateway.runner.audit.RunnerAuditService.class),
                    fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), hosts,
                    new fr.claudegateway.atelier.AtelierProperties(null, null, null, null, null, null, null, null,
                            null, null, null, null, true));
            service.setTurnMeasureWriter(writer);

            workspace.setId(workspaceId);
            workspace.setUserId(userId);
            workspace.setHostId(hostId);
            workspace.setProjectPath("projet");
            workspace.setSource(fr.claudegateway.atelier.WorkspaceSource.ARCHIVE);
            workspace.setExecutionTarget(fr.claudegateway.atelier.WorkspaceExecutionTarget.RUNNER);
            workspace.setAgentAskBeforeBash(false);
            org.mockito.Mockito.when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
            org.mockito.Mockito.when(hosts.declaredCapabilities(hostId)).thenReturn(java.util.Set.of("files", "bash"));
            org.mockito.Mockito.when(gate.await(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
                        inv.getArgument(3, Runnable.class).run();
                        return new fr.claudegateway.runner.exec.RunnerConfirmationGate.Outcome(
                                fr.claudegateway.runner.exec.RunnerConfirmationGate.Decision.ALLOW, null);
                    });
            org.mockito.Mockito.when(byok.resolveActiveApiKey(userId)).thenReturn(java.util.Optional.empty());
            org.mockito.Mockito.when(quota.currentUsage(userId)).thenReturn(
                    new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
            org.mockito.Mockito.when(repository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                    .thenAnswer(inv -> List.copyOf(history));
            org.mockito.Mockito.when(repository.save(org.mockito.ArgumentMatchers.any(
                    fr.claudegateway.atelier.AtelierMessage.class))).thenAnswer(inv -> {
                        fr.claudegateway.atelier.AtelierMessage saved = inv.getArgument(0);
                        if (saved.getId() == null) {
                            saved.setId(UUID.randomUUID());
                            history.add(saved);
                        }
                        return saved;
                    });
        }

        void bashReturns(String output) {
            org.mockito.Mockito.when(runner.bash(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new fr.claudegateway.runner.channel.RunnerCallResult(true, "", false, 0, 12L, null,
                            null, null, output, false));
        }

        void chat(Integer chantier, String text) {
            service.chat(userId, workspaceId, chantier, text, null, false, List.of());
            // Les mesures se suivent dans le temps, même au sein d'une milliseconde.
            jdbcTemplate.update("UPDATE atelier_turn_measures SET occurred_at = ? WHERE user_id = ? "
                    + "AND occurred_at > ?", java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")
                            .plusSeconds(clock.incrementAndGet())), userId,
                    java.sql.Timestamp.from(Instant.parse("2026-06-01T00:00:00Z")));
        }

        /** Longueur du {@code n}-ième résultat d'outil tel que le tour l'a envoyé. */
        long sentToolResultLength(int n) {
            int seen = 0;
            for (List<fr.claudegateway.agent.AgentMessage> request : provider.requestSnapshots) {
                for (fr.claudegateway.agent.AgentMessage message : request) {
                    for (fr.claudegateway.agent.AgentContentBlock block : message.content()) {
                        if (block instanceof fr.claudegateway.agent.AgentContentBlock.ToolResult result
                                && seen++ == n) {
                            return result.content().length();
                        }
                    }
                }
            }
            throw new AssertionError("aucun résultat d'outil n°" + n);
        }

        List<Map<String, Object>> rows() {
            return jdbcTemplate.queryForList("SELECT * FROM atelier_turn_measures WHERE user_id = ? "
                    + "AND workspace_id = ? ORDER BY occurred_at", userId, workspaceId);
        }
    }

    private int count(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM atelier_turn_measures WHERE user_id = ?", Integer.class, userId);
    }
}
