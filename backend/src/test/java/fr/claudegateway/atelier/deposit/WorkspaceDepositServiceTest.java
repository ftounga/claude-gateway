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

    /** 2026-10-16 08:05:09 UTC = 10:05:09 à Paris : le nom horodaté d'une capture. */
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(
            java.time.Instant.parse("2026-10-16T08:05:09Z"), java.time.ZoneOffset.UTC);
    private static final String STAMP = "capture-20261016-100509";

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    /**
     * Le dépôt renvoie désormais l'id persisté (F-169 / SF-169-02) : le repository (mock) doit rendre
     * l'entité avec un id, comme le ferait JPA. LENIENT : les tests de cas d'erreur n'atteignent pas
     * l'enregistrement et n'utilisent pas ce stub.
     */
    @org.junit.jupiter.api.BeforeEach
    void stubSaveReturnsPersistedEntity() {
        when(depositedFileRepository.save(any(AtelierDepositedFile.class))).thenAnswer(invocation -> {
            AtelierDepositedFile file = invocation.getArgument(0);
            if (file.getId() == null) {
                file.setId(UUID.randomUUID());
            }
            return file;
        });
    }

    /** chunk-bytes = 4 pour observer le découpage sur un petit fichier. */
    private WorkspaceDepositService service() {
        return new WorkspaceDepositService(workspaceService, runnerToolGateway, runnerLiveness,
                depositedFileRepository, 8_388_608L, 104_857_600L, 20, 4, CLOCK);
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
        // F-169 / SF-169-02 : la réponse porte l'id du dépôt (renvoyé plus tard dans attachedDepositIds).
        assertThat(response.files().get(0).id()).isNotNull();
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

    // ------------------------------------------------- F-201 / SF-201-01 : jamais d'écrasement

    @Test
    void deuxCapturesHebergeesNeSEcrasentPas() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(hosted());
        when(workspaceService.depositHostedFile(eq(userId), eq(workspaceId), anyString(), any(), anyString()))
                .thenAnswer(inv -> "entrees/" + inv.getArgument(2));
        // Le premier collage est déjà là (base des dépôts + stockage).
        when(depositedFileRepository.findPathsByUserIdAndWorkspaceId(userId, workspaceId))
                .thenReturn(List.of("entrees/" + STAMP + ".png"));
        when(workspaceService.depositedHostedNames(userId, workspaceId)).thenReturn(List.of(STAMP + ".png"));

        DepositResponse response = service().deposit(userId, workspaceId,
                List.of(file("image.png", new byte[] {1})));

        assertThat(response.files().get(0).path()).isEqualTo("entrees/" + STAMP + "-2.png");
        verify(workspaceService, never()).depositHostedFile(eq(userId), eq(workspaceId), eq("image.png"),
                any(), anyString());
    }

    @Test
    void unFichierHebergeEcritParLAgentCompteCommePris() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(hosted());
        when(workspaceService.depositHostedFile(eq(userId), eq(workspaceId), anyString(), any(), anyString()))
                .thenAnswer(inv -> "entrees/" + inv.getArgument(2));
        when(workspaceService.depositedHostedNames(userId, workspaceId)).thenReturn(List.of("notes.md"));

        DepositResponse response = service().deposit(userId, workspaceId,
                List.of(file("notes.md", new byte[] {1})));

        assertThat(response.files().get(0).path()).isEqualTo("entrees/notes-2.md");
    }

    @Test
    void unLotDeTroisCapturesSurLePosteDonneTroisCheminsDistincts() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runnerTarget());
        when(runnerLiveness.isAlive(userId, hostId)).thenReturn(true);
        when(runnerToolGateway.writeFileBytes(any(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(ok());

        DepositResponse response = service().deposit(userId, workspaceId, List.of(
                file("image.png", new byte[] {1}),
                file("image.png", new byte[] {2}),
                file("image.png", new byte[] {3})));

        assertThat(response.files()).extracting(DepositResponse.DepositedFile::path).containsExactly(
                ".atelier/entrees/" + STAMP + ".png",
                ".atelier/entrees/" + STAMP + "-2.png",
                ".atelier/entrees/" + STAMP + "-3.png");
        // Le poste ne liste pas le stockage objet : seule la base des dépôts compte.
        verify(workspaceService, never()).depositedHostedNames(any(), any());
    }

    @Test
    void unNomLibreDejaDeposeRecoitUnSuffixe() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runnerTarget());
        when(runnerLiveness.isAlive(userId, hostId)).thenReturn(true);
        when(runnerToolGateway.writeFileBytes(any(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(ok());
        when(depositedFileRepository.findPathsByUserIdAndWorkspaceId(userId, workspaceId))
                .thenReturn(List.of(".atelier/entrees/Rapport.pdf", "entrees/autre.pdf"));

        DepositResponse response = service().deposit(userId, workspaceId,
                List.of(file("rapport.pdf", new byte[] {1}), file("autre.pdf", new byte[] {1})));

        assertThat(response.files()).extracting(DepositResponse.DepositedFile::path).containsExactly(
                ".atelier/entrees/rapport-2.pdf", ".atelier/entrees/autre.pdf");
        // Isolation : seuls les dépôts de CE couple (utilisateur, terminal) sont consultés.
        verify(depositedFileRepository).findPathsByUserIdAndWorkspaceId(userId, workspaceId);
    }
}
