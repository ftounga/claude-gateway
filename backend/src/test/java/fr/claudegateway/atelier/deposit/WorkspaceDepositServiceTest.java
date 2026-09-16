package fr.claudegateway.atelier.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.deposit.WorkspaceDepositService.IncomingFile;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Réception d'un dépôt côté serveur (F-115 / SF-115-01) : aiguillage hébergé/poste, bornes, nom
 * assaini, coupe-circuit, isolation, transfert découpé et enregistrement.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkspaceDepositServiceTest {

    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private RunnerToolGateway runnerToolGateway;
    @Mock
    private RunnerLiveness runnerLiveness;
    @Mock
    private AtelierDepositedFileRepository depositedFileRepository;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    /** chunk-bytes = 4 pour observer le découpage sur un petit fichier. */
    private WorkspaceDepositService service() {
        return new WorkspaceDepositService(workspaceService, runnerToolGateway, runnerLiveness,
                depositedFileRepository, 8_388_608L, 104_857_600L, 20, 4);
    }

    private Workspace hosted() {
        Workspace w = new Workspace();
        w.setUserId(userId);
        return w; // executionTarget par défaut != RUNNER
    }

    private Workspace runnerTarget() {
        Workspace w = new Workspace();
        w.setUserId(userId);
        w.setHostId(hostId);
        w.setProjectPath("projet");
        w.setExecutionTarget(fr.claudegateway.atelier.WorkspaceExecutionTarget.RUNNER);
        return w;
    }

    private static IncomingFile file(String name, byte[] bytes) {
        return new IncomingFile(name, "application/octet-stream", bytes);
    }

    private RunnerCallResult ok() {
        return new RunnerCallResult(true, "ok", false, null, 1L, 4L, null, null, "", false);
    }

    // ---------------------------------------------------------------- hébergé

    @Test
    void deposeUnFichierHebergeSousEntreesEtEnregistre() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(hosted());
        when(workspaceService.depositHostedFile(eq(userId), eq(workspaceId), eq("capture.png"),
                any(), anyString())).thenReturn("entrees/capture.png");

        DepositResponse response = service().deposit(userId, workspaceId,
                List.of(file("capture.png", new byte[] {1, 2, 3})));

        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).path()).isEqualTo("entrees/capture.png");
        assertThat(response.files().get(0).target()).isEqualTo("HOSTED");
        assertThat(response.files().get(0).size()).isEqualTo(3);
        verify(depositedFileRepository).save(any(AtelierDepositedFile.class));
        verify(runnerToolGateway, never()).writeFileBytes(any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void assainitLeNomAvantDEcrire() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(hosted());
        when(workspaceService.depositHostedFile(eq(userId), eq(workspaceId), eq("evil.png"),
                any(), anyString())).thenReturn("entrees/evil.png");

        service().deposit(userId, workspaceId, List.of(file("../../evil.png", new byte[] {1})));

        verify(workspaceService).depositHostedFile(eq(userId), eq(workspaceId), eq("evil.png"),
                any(), anyString());
    }

    @Test
    void refuseUnNomVideOuDeTraversee() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(hosted());

        assertThatThrownBy(() -> service().deposit(userId, workspaceId, List.of(file("..", new byte[] {1}))))
                .isInstanceOf(WorkspaceDepositException.class)
                .satisfies(e -> assertThat(((WorkspaceDepositException) e).code()).isEqualTo("invalid_name"));
    }

    @Test
    void refuseUnFichierHebergeTropGros() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(hosted());
        byte[] tooBig = new byte[8_388_608 + 1];

        assertThatThrownBy(() -> service().deposit(userId, workspaceId, List.of(file("x.bin", tooBig))))
                .isInstanceOf(WorkspaceDepositException.class)
                .satisfies(e -> assertThat(((WorkspaceDepositException) e).code()).isEqualTo("file_too_large"));
        verify(workspaceService, never()).depositHostedFile(any(), any(), anyString(), any(), anyString());
    }

    @Test
    void refuseUnDepotSansFichier() {
        assertThatThrownBy(() -> service().deposit(userId, workspaceId, List.of()))
                .isInstanceOf(WorkspaceDepositException.class)
                .satisfies(e -> assertThat(((WorkspaceDepositException) e).code()).isEqualTo("no_file"));
    }

    @Test
    void refuseTropDeFichiers() {
        List<IncomingFile> many = java.util.stream.IntStream.rangeClosed(0, 20)
                .mapToObj(i -> file("f" + i + ".txt", new byte[] {1}))
                .toList();

        assertThatThrownBy(() -> service().deposit(userId, workspaceId, many))
                .isInstanceOf(WorkspaceDepositException.class)
                .satisfies(e -> assertThat(((WorkspaceDepositException) e).code()).isEqualTo("too_many_files"));
    }

    // ------------------------------------------------------------------ poste

    @Test
    void deposeVersUnPosteParTranchesAOffsetCroissant() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runnerTarget());
        when(runnerLiveness.isAlive(userId, hostId)).thenReturn(true);
        when(runnerToolGateway.writeFileBytes(any(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(ok());

        DepositResponse response = service().deposit(userId, workspaceId,
                List.of(file("log.bin", new byte[] {1, 2, 3, 4, 5, 6}))); // 6 octets, chunk = 4 → 2 tranches

        assertThat(response.files().get(0).path()).isEqualTo(".atelier/entrees/log.bin");
        assertThat(response.files().get(0).target()).isEqualTo("RUNNER");

        ArgumentCaptor<Long> offsets = ArgumentCaptor.forClass(Long.class);
        verify(runnerToolGateway, org.mockito.Mockito.times(2))
                .writeFileBytes(any(), anyString(), eq(".atelier/entrees/log.bin"), anyString(), offsets.capture());
        assertThat(offsets.getAllValues()).containsExactly(0L, 4L);
        verify(depositedFileRepository).save(any(AtelierDepositedFile.class));
    }

    @Test
    void refuseUnDepotVersUnPosteHorsLigne() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runnerTarget());
        when(runnerLiveness.isAlive(userId, hostId)).thenReturn(false);

        assertThatThrownBy(() -> service().deposit(userId, workspaceId, List.of(file("x.bin", new byte[] {1}))))
                .isInstanceOf(WorkspaceDepositException.class)
                .satisfies(e -> assertThat(((WorkspaceDepositException) e).code()).isEqualTo("runner_offline"));
        verify(runnerToolGateway, never()).writeFileBytes(any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void nommeLEchecQuandLeDossierNestPasInscriptible() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runnerTarget());
        when(runnerLiveness.isAlive(userId, hostId)).thenReturn(true);
        when(runnerToolGateway.writeFileBytes(any(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(RunnerCallResult.backendError("io_error", "Écriture impossible."));

        assertThatThrownBy(() -> service().deposit(userId, workspaceId, List.of(file("x.bin", new byte[] {1}))))
                .isInstanceOf(WorkspaceDepositException.class)
                .satisfies(e -> assertThat(((WorkspaceDepositException) e).code()).isEqualTo("deposit_failed"));
    }

    @Test
    void nommeLePosteHorsLigneSiLeTransfertEchoueEnTransport() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runnerTarget());
        when(runnerLiveness.isAlive(userId, hostId)).thenReturn(true);
        when(runnerToolGateway.writeFileBytes(any(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));

        assertThatThrownBy(() -> service().deposit(userId, workspaceId, List.of(file("x.bin", new byte[] {1}))))
                .isInstanceOf(WorkspaceDepositException.class)
                .satisfies(e -> assertThat(((WorkspaceDepositException) e).code()).isEqualTo("runner_offline"));
    }
}
