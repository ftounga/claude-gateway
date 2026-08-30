package fr.claudegateway.runner.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerConnection;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerRegistry;

/**
 * Journal d'audit du runner (F-38 / SF-38-08, décision D11) : ce qui est écrit, ce qui ne l'est
 * jamais, et le fait qu'une écriture impossible <b>n'interrompt pas</b> le tour en cours.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunnerAuditServiceTest {

    @Mock private RunnerAuditRepository repository;
    @Mock private RunnerRegistry registry;
    @Mock private WorkspaceService workspaceService;

    private RunnerAuditService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RunnerAuditService(repository, registry, workspaceService);
        when(registry.findLocal(workspaceId)).thenReturn(Optional.of(new RunnerConnection(
                workspaceId, userId, tokenId, "node-1", java.time.OffsetDateTime.now())));
    }

    private RunnerAudit captureSaved() {
        ArgumentCaptor<RunnerAudit> captor = ArgumentCaptor.forClass(RunnerAudit.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void aSuccessfulCallIsRecordedWithItsMeasures() {
        service.recordCall(userId, workspaceId, "toolu_1", "bash", "npm test",
                new RunnerCallResult(true, "", false, 0, 42L, 128L, null, null, "ok\n", false));

        RunnerAudit saved = captureSaved();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getWorkspaceId()).isEqualTo(workspaceId);
        // Le jeton vient du registre local, jamais d'un champ de message (isolation).
        assertThat(saved.getTokenId()).isEqualTo(tokenId);
        assertThat(saved.getOutcome()).isEqualTo("OK");
        assertThat(saved.getExitCode()).isZero();
        assertThat(saved.getDurationMs()).isEqualTo(42L);
        assertThat(saved.getBytes()).isEqualTo(128L);
        assertThat(saved.getErrorCode()).isNull();
    }

    @Test
    void theRunnerErrorMessageIsNeverStoredOnlyItsCode() {
        service.recordCall(userId, workspaceId, "toolu_2", "read_file", "src/a.ts",
                new RunnerCallResult(false, "", false, null, 3L, null, "not_found",
                        "Fichier introuvable : src/a.ts", "", false));

        RunnerAudit saved = captureSaved();
        assertThat(saved.getOutcome()).isEqualTo("ERROR");
        assertThat(saved.getErrorCode()).isEqualTo("not_found");
        // Un message peut porter un fragment de chemin de la machine ; un code, jamais.
        assertThat(saved.getTarget()).isEqualTo("src/a.ts");
    }

    @Test
    void timeoutsAndCancellationsHaveTheirOwnOutcome() {
        assertThat(RunnerAuditService.outcomeOf(
                RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_TIMEOUT)))
                .isEqualTo(RunnerAuditOutcome.TIMEOUT);
        assertThat(RunnerAuditService.outcomeOf(RunnerCallResult.backendError("timeout")))
                .isEqualTo(RunnerAuditOutcome.TIMEOUT);
        assertThat(RunnerAuditService.outcomeOf(RunnerCallResult.backendError("cancelled")))
                .isEqualTo(RunnerAuditOutcome.CANCELLED);
        assertThat(RunnerAuditService.outcomeOf(RunnerCallResult.backendError("denied")))
                .isEqualTo(RunnerAuditOutcome.DENIED);
        assertThat(RunnerAuditService.outcomeOf(
                RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE)))
                .isEqualTo(RunnerAuditOutcome.ERROR);
    }

    @Test
    void aTargetTooLongIsTruncatedRatherThanRefused() {
        String command = "echo " + "x".repeat(2_000);

        service.recordCall(userId, workspaceId, "toolu_3", "bash", command,
                new RunnerCallResult(true, "", false, 0, 1L, null, null, null, "", false));

        assertThat(captureSaved().getTarget()).hasSize(1_000);
    }

    @Test
    void aRefusedCallIsRecordedAsDenied() {
        service.recordDenied(userId, workspaceId, "toolu_4", "bash", "rm -rf /",
                RunnerAuditOutcome.DENIED);

        RunnerAudit saved = captureSaved();
        assertThat(saved.getOutcome()).isEqualTo("DENIED");
        assertThat(saved.getErrorCode()).isEqualTo("denied");
    }

    @Test
    void bootstrapReadsProduceASingleLineAndNothingWhenNoneWereRead() {
        service.recordBootstrap(userId, workspaceId, "boot-1", 4, 1_234L);
        RunnerAudit saved = captureSaved();
        assertThat(saved.getTool()).isEqualTo("bootstrap");
        assertThat(saved.getTarget()).isEqualTo("consigne système (4 lecture(s))");
        assertThat(saved.getBytes()).isEqualTo(1_234L);

        service.recordBootstrap(userId, workspaceId, "boot-2", 0, 0L);
        verify(repository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void anImpossibleWriteNeverInterruptsTheTurn() {
        when(repository.save(any())).thenThrow(new RuntimeException("base indisponible"));

        assertThatCode(() -> service.recordCall(userId, workspaceId, "toolu_5", "bash", "ls",
                new RunnerCallResult(true, "", false, 0, 1L, null, null, null, "", false)))
                .doesNotThrowAnyException();
    }

    @Test
    void readingTheJournalIsIsolatedAndBounded() {
        when(repository.findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(List.of());

        service.list(userId, workspaceId, null);
        service.list(userId, workspaceId, 0);
        service.list(userId, workspaceId, 10_000);

        // Isolation : le workspace est vérifié possédé AVANT toute lecture.
        verify(workspaceService, org.mockito.Mockito.times(3)).requireOwned(userId, workspaceId);
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, org.mockito.Mockito.times(3))
                .findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(any(), any(), pages.capture());
        assertThat(pages.getAllValues()).containsExactly(
                PageRequest.of(0, RunnerAuditService.DEFAULT_LIMIT),
                PageRequest.of(0, 1),
                PageRequest.of(0, RunnerAuditService.MAX_LIMIT));
    }

    @Test
    void noLocalRunnerMeansNoTokenRatherThanNoLine() {
        when(registry.findLocal(workspaceId)).thenReturn(Optional.empty());

        service.recordDenied(userId, workspaceId, "toolu_6", "bash", "ls", RunnerAuditOutcome.TIMEOUT);

        RunnerAudit saved = captureSaved();
        assertThat(saved.getTokenId()).isNull();
        assertThat(saved.getOutcome()).isEqualTo("TIMEOUT");
    }
}
