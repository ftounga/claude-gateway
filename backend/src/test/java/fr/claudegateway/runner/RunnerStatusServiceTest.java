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

import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.RunnerStatusService.RunnerStatus;
import fr.claudegateway.runner.channel.RunnerRegistry;

/**
 * Tests du calcul de l'état runner (F-38 / SF-38-02) : présence du registre OU fraîcheur du dernier
 * heartbeat, et vérification d'appartenance du workspace (isolation {@code user_id}).
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

    private RunnerToken tokenLastSeen(OffsetDateTime lastSeenAt) {
        return RunnerToken.builder()
                .userId(userId).workspaceId(workspaceId)
                .tokenHash("h").expiresAt(OffsetDateTime.now().plusDays(1))
                .lastSeenAt(lastSeenAt)
                .build();
    }

    @Test
    void connectedWhenRegistrySeesConnectionEvenWithoutHeartbeat() {
        when(registry.isConnected(workspaceId)).thenReturn(true);
        when(tokenRepository.findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId))
                .thenReturn(List.of());

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isTrue();
        assertThat(status.lastSeenAt()).isNull();
    }

    @Test
    void connectedWhenHeartbeatFreshThoughRegistryEmpty() {
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
        when(registry.isConnected(workspaceId)).thenReturn(false);
        when(tokenRepository.findByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId))
                .thenReturn(List.of(tokenLastSeen(null)));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isFalse();
        assertThat(status.lastSeenAt()).isNull();
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
