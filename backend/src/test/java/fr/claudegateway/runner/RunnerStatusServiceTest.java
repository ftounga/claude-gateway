package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.RunnerStatusService.RunnerStatus;
import fr.claudegateway.runner.channel.RunnerRegistry;

/**
 * Tests du calcul de l'état runner (F-38 / SF-38-02) : présence du registre OU fraîcheur du dernier
 * heartbeat, et vérification d'appartenance du workspace (isolation {@code user_id}).
 *
 * <p>Depuis F-45 / SF-45-05, le statut porte aussi le genre d'interpréteur élu par le runner
 * (SF-38-27), <b>normalisé</b> : la colonne est alimentée par une trame venue d'un client, et une
 * valeur hors liste blanche ne doit pas sortir de la gateway.</p>
 */
@ExtendWith(MockitoExtension.class)
class RunnerStatusServiceTest {

    @Mock
    private RunnerTokenRepository tokenRepository;
    @Mock
    private RunnerRegistry registry;
    @Mock
    private WorkspaceService workspaceService;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private RunnerStatusService service() {
        return new RunnerStatusService(tokenRepository, registry, workspaceService,
                Duration.ofSeconds(90));
    }

    /**
     * Le service lit désormais le workspace <b>retourné</b> par {@code requireOwned} : c'est le même
     * appel d'isolation qu'avant, dont la valeur de retour cessait simplement d'être utilisée.
     */
    private void givenWorkspace(String runnerShell) {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(Workspace.builder()
                .id(workspaceId).userId(userId).name("Projet").runnerShell(runnerShell).build());
    }

    private RunnerToken tokenLastSeen(OffsetDateTime lastSeenAt) {
        return RunnerToken.builder()
                .userId(userId).workspaceId(workspaceId)
                .tokenHash("h").expiresAt(OffsetDateTime.now().plusDays(1))
                .lastSeenAt(lastSeenAt)
                .build();
    }

    @Test
    void connectedWhenRegistrySeesConnectionEvenWithoutHeartbeat() {
        givenWorkspace(null);
        when(registry.isConnected(workspaceId)).thenReturn(true);
        when(tokenRepository.findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId))
                .thenReturn(List.of());

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isTrue();
        assertThat(status.lastSeenAt()).isNull();
    }

    @Test
    void connectedWhenHeartbeatFreshThoughRegistryEmpty() {
        givenWorkspace(null);
        // Cas cross-replica : la socket vit sur l'autre pod, le registre local ne la voit pas, mais
        // le heartbeat a rafraichi last_seen_at dans la base partagee.
        when(registry.isConnected(workspaceId)).thenReturn(false);
        OffsetDateTime fresh = OffsetDateTime.now().minusSeconds(10);
        when(tokenRepository.findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId))
                .thenReturn(List.of(tokenLastSeen(fresh)));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isTrue();
        assertThat(status.lastSeenAt()).isEqualTo(fresh);
    }

    @Test
    void disconnectedWhenRegistryEmptyAndHeartbeatStale() {
        givenWorkspace(null);
        when(registry.isConnected(workspaceId)).thenReturn(false);
        OffsetDateTime stale = OffsetDateTime.now().minusMinutes(5);
        when(tokenRepository.findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId))
                .thenReturn(List.of(tokenLastSeen(stale)));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isFalse();
        assertThat(status.lastSeenAt()).isEqualTo(stale);
    }

    @Test
    void disconnectedWhenNeverSeen() {
        givenWorkspace(null);
        when(registry.isConnected(workspaceId)).thenReturn(false);
        when(tokenRepository.findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId))
                .thenReturn(List.of(tokenLastSeen(null)));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isFalse();
        assertThat(status.lastSeenAt()).isNull();
    }

    // ---------- Interpréteur élu (F-45 / SF-45-05) ----------

    @Test
    void statusCarriesDeclaredShell() {
        when(registry.isConnected(workspaceId)).thenReturn(true);
        when(tokenRepository.findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId))
                .thenReturn(List.of());
        givenWorkspace("powershell");

        assertThat(service().status(userId, workspaceId).shell()).isEqualTo("powershell");
    }

    @Test
    void statusHasNoShellWhenNoRunnerEverDeclaredOne() {
        // Runner anterieur a SF-38-27, ou machine jamais connectee : l'ecran doit OMETTRE la ligne,
        // pas afficher un defaut.
        when(registry.isConnected(workspaceId)).thenReturn(false);
        when(tokenRepository.findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId))
                .thenReturn(List.of());
        givenWorkspace(null);

        assertThat(service().status(userId, workspaceId).shell()).isNull();
    }

    @Test
    void statusDropsAShellOutsideTheWhitelist() {
        // La colonne est alimentee par une trame client : une valeur inconnue ne sort pas d'ici.
        when(registry.isConnected(workspaceId)).thenReturn(false);
        when(tokenRepository.findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId))
                .thenReturn(List.of());
        givenWorkspace("zsh-maison");

        assertThat(service().status(userId, workspaceId).shell()).isNull();
    }

    @Test
    void statusRequiresWorkspaceOwnership() {
        lenient().when(registry.isConnected(any())).thenReturn(true);
        when(workspaceService.requireOwned(userId, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("introuvable"));

        assertThatThrownBy(() -> service().status(userId, workspaceId))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(tokenRepository, never()).findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(any(), any());
    }
}
