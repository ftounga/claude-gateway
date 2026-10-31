package fr.claudegateway.atelier.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fr.claudegateway.atelier.ProjectFileRead;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * F-228 / SF-228-04 — identifiant d'appel aléatoire, téléchargement en flux depuis le poste (tranche par tranche),
 * poste muet AVANT tout octet → exception lisible, plafond de téléchargement.
 */
class DepositContentServiceTest {

    private final AtelierDepositedFileRepository deposits = mock(AtelierDepositedFileRepository.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final ProjectFileRead fileRead = mock(ProjectFileRead.class);
    private final DepositContentService service = new DepositContentService(deposits, workspaces, fileRead);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID depositId = UUID.randomUUID();
    private final Workspace workspace = mock(Workspace.class);
    private final AtelierDepositedFile deposit = mock(AtelierDepositedFile.class);

    @BeforeEach
    void setUp() {
        when(workspaces.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(workspace.isRunnerTarget()).thenReturn(true);
        when(deposits.findByIdAndUserIdAndWorkspaceId(depositId, userId, workspaceId)).thenReturn(Optional.of(deposit));
        when(deposit.getPath()).thenReturn(".atelier/entrees/film.mp4");
        when(deposit.getSizeBytes()).thenReturn(30L * 1024 * 1024);
    }

    @Test
    @DisplayName("deux lectures de la même pièce n'ont jamais le même identifiant d'appel")
    void randomCallIds() {
        when(deposit.getSizeBytes()).thenReturn(10L);
        when(fileRead.read(any(), any(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(ProjectFileRead.Read.ok(new byte[] {1}));
        service.content(userId, workspaceId, depositId);
        service.content(userId, workspaceId, depositId);
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(fileRead, org.mockito.Mockito.times(2))
                .read(eq(userId), eq(workspace), ids.capture(), anyString(), anyLong(), anyString());
        assertThat(ids.getAllValues().get(0)).startsWith("apercu-").isNotEqualTo(ids.getAllValues().get(1))
                .doesNotContain(depositId.toString());
    }

    @Test
    @DisplayName("au-delà de 20 Mo, le téléchargement passe : les tranches du poste sont écrites au fil de l'eau")
    void streamsFromTheRunnerChunkByChunk() throws Exception {
        RunnerToolGateway gateway = mock(RunnerToolGateway.class);
        AtomicInteger calls = new AtomicInteger();
        when(gateway.readFileBytes(any(), anyString(), anyString(), anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(inv -> {
            int n = calls.getAndIncrement();
            String part = Base64.getEncoder().encodeToString(("part" + n).getBytes(StandardCharsets.UTF_8));
            return new RunnerCallResult(true, part, n < 2, null, 0L, null, null, null, null, false);
        });
        DepositContentService real = new DepositContentService(deposits, workspaces,
                new ProjectFileRead(workspaces, gateway, mock(RunnerAuditService.class)));
        when(workspace.getId()).thenReturn(workspaceId);
        List<String> opened = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        real.download(userId, workspaceId, depositId, name -> {
            opened.add(name);
            return out;
        });
        assertThat(opened).containsExactly("film.mp4");
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("part0part1part2");
    }

    @Test
    @DisplayName("poste muet dès la première tranche : exception lisible, rien n'est ouvert")
    void runnerSilentBeforeAnyByte() {
        try {
            when(fileRead.stream(any(), any(), anyString(), anyString(), anyLong(), anyString(), any()))
                    .thenReturn(ProjectFileRead.Read.error("Fichier illisible sur la machine (x)."));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        List<String> opened = new ArrayList<>();
        assertThatThrownBy(() -> service.download(userId, workspaceId, depositId, name -> {
            opened.add(name);
            return new ByteArrayOutputStream();
        })).isInstanceOf(DepositUnreadableException.class).hasMessageContaining("Le poste ne répond pas");
        assertThat(opened).isEmpty();
    }

    @Test
    @DisplayName("plus de 100 Mo : refus dit, aucune lecture")
    void downloadCap() {
        when(deposit.getSizeBytes()).thenReturn(DepositContentService.MAX_DOWNLOAD_BYTES + 1);
        assertThatThrownBy(() -> service.download(userId, workspaceId, depositId, name -> new ByteArrayOutputStream()))
                .isInstanceOf(DepositUnreadableException.class).hasMessageContaining("100 Mo");
    }
}
