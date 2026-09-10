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
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * Calcul de l'état runner (F-38 / SF-38-02) : présence du registre OU fraîcheur du dernier
 * heartbeat, sous double vérification d'appartenance (isolation {@code user_id}).
 *
 * <p>Depuis F-48 / SF-48-01, l'état est celui d'un <b>poste</b>, et un projet en hérite de celui de
 * la machine à laquelle il est rattaché. Un projet rattaché à rien répond « déconnecté » — c'est
 * l'état d'un projet neuf, pas une erreur.</p>
 *
 * <p>Le genre d'interpréteur élu (SF-38-27) sort <b>normalisé</b> : la valeur est alimentée par une
 * trame venue d'un client, et une valeur hors liste blanche ne doit pas sortir de la gateway.</p>
 */
@ExtendWith(MockitoExtension.class)
class RunnerStatusServiceTest {

    @Mock
    private RunnerTokenRepository tokenRepository;
    @Mock
    private RunnerRegistry registry;
    @Mock
    private WorkspaceService workspaceService;
    @Mock
    private RunnerHostService hostService;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private RunnerStatusService service() {
        return new RunnerStatusService(tokenRepository, registry, workspaceService, hostService,
                Duration.ofSeconds(90));
    }

    /** Projet rattaché à un poste dont le runner a déclaré (ou non) son interpréteur. */
    private void givenAttachedProject(String declaredShell) {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(Workspace.builder()
                .id(workspaceId).userId(userId).name("Projet").hostId(hostId).projectPath("app")
                .build());
        when(hostService.requireOwned(userId, hostId)).thenReturn(RunnerHost.builder()
                .id(hostId).userId(userId).name("Poste").shell(declaredShell)
                .rootName("dev").elevated(true).build());
    }

    @Test
    void statusCarriesWhatTheMachineDeclaredOfItself() {
        // F-48 / SF-48-03 : la racine et les droits décrivent une MACHINE. Ils voyagent donc avec
        // l'état du poste, et non plus avec le détail du projet. L'élévation est lue là où l'on
        // autorise une commande — le seul endroit où elle change une décision (SF-38-18).
        givenAttachedProject("posix");
        when(registry.isConnected(hostId)).thenReturn(true);
        givenTokens();

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.hostName()).isEqualTo("Poste");
        assertThat(status.rootName()).isEqualTo("dev");
        assertThat(status.elevated()).isTrue();
    }

    private RunnerToken tokenLastSeen(OffsetDateTime lastSeenAt) {
        return RunnerToken.builder()
                .userId(userId).hostId(hostId)
                .tokenHash("h").expiresAt(OffsetDateTime.now().plusDays(1))
                .lastSeenAt(lastSeenAt)
                .build();
    }

    private void givenTokens(RunnerToken... tokens) {
        when(tokenRepository.findByUserIdAndHostIdOrderByCreatedAtDesc(userId, hostId))
                .thenReturn(List.of(tokens));
    }

    @Test
    void connectedWhenRegistrySeesConnectionEvenWithoutHeartbeat() {
        givenAttachedProject(null);
        when(registry.isConnected(hostId)).thenReturn(true);
        givenTokens();

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isTrue();
        assertThat(status.lastSeenAt()).isNull();
        assertThat(status.hostId()).isEqualTo(hostId);
    }

    @Test
    void connectedWhenHeartbeatFreshThoughRegistryEmpty() {
        givenAttachedProject(null);
        // Cas cross-replica : la socket vit sur l'autre pod, le registre local ne la voit pas, mais
        // le heartbeat a rafraichi last_seen_at dans la base partagee.
        when(registry.isConnected(hostId)).thenReturn(false);
        OffsetDateTime fresh = OffsetDateTime.now().minusSeconds(10);
        givenTokens(tokenLastSeen(fresh));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isTrue();
        assertThat(status.lastSeenAt()).isEqualTo(fresh);
    }

    @Test
    void disconnectedWhenRegistryEmptyAndHeartbeatStale() {
        givenAttachedProject(null);
        when(registry.isConnected(hostId)).thenReturn(false);
        OffsetDateTime stale = OffsetDateTime.now().minusMinutes(5);
        givenTokens(tokenLastSeen(stale));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isFalse();
        assertThat(status.lastSeenAt()).isEqualTo(stale);
    }

    @Test
    void disconnectedWhenNeverSeen() {
        givenAttachedProject(null);
        when(registry.isConnected(hostId)).thenReturn(false);
        givenTokens(tokenLastSeen(null));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isFalse();
        assertThat(status.lastSeenAt()).isNull();
    }

    @Test
    void aProjectAttachedToNoHostIsSimplyDisconnected() {
        // F-48 / SF-48-01 : l'état d'un projet qu'on vient de créer. L'écran doit pouvoir le dire,
        // donc ce n'est ni une erreur ni un 404 — et aucun jeton n'est lu pour rien.
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(Workspace.builder()
                .id(workspaceId).userId(userId).name("Projet").build());

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isFalse();
        assertThat(status.hostId()).isNull();
        verify(tokenRepository, never()).findByUserIdAndHostIdOrderByCreatedAtDesc(any(), any());
    }

    // ---------- Interpréteur élu (F-45 / SF-45-05), désormais porté par le POSTE ----------

    @Test
    void statusCarriesTheShellDeclaredByTheMachine() {
        givenAttachedProject("powershell");
        when(registry.isConnected(hostId)).thenReturn(true);
        givenTokens();

        assertThat(service().status(userId, workspaceId).shell()).isEqualTo("powershell");
    }

    @Test
    void statusHasNoShellWhenNoRunnerEverDeclaredOne() {
        // Runner anterieur a SF-38-27, ou machine jamais connectee : l'ecran doit OMETTRE la ligne,
        // pas afficher un defaut.
        givenAttachedProject(null);
        when(registry.isConnected(hostId)).thenReturn(false);
        givenTokens();

        assertThat(service().status(userId, workspaceId).shell()).isNull();
    }

    @Test
    void statusDropsAShellOutsideTheWhitelist() {
        // La colonne est alimentee par une trame client : une valeur inconnue ne sort pas d'ici.
        givenAttachedProject("zsh-maison");
        when(registry.isConnected(hostId)).thenReturn(false);
        givenTokens();

        assertThat(service().status(userId, workspaceId).shell()).isNull();
    }

    @Test
    void statusRequiresWorkspaceOwnership() {
        lenient().when(registry.isConnected(any())).thenReturn(true);
        when(workspaceService.requireOwned(userId, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("introuvable"));

        assertThatThrownBy(() -> service().status(userId, workspaceId))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(tokenRepository, never()).findByUserIdAndHostIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void hostStatusRequiresHostOwnership() {
        // Le poste d'autrui est traité comme introuvable : jamais d'oracle d'existence.
        when(hostService.requireOwned(userId, hostId))
                .thenThrow(new fr.claudegateway.runner.host.RunnerHostNotFoundException("introuvable"));

        assertThatThrownBy(() -> service().hostStatus(userId, hostId))
                .isInstanceOf(fr.claudegateway.runner.host.RunnerHostNotFoundException.class);
        verify(tokenRepository, never()).findByUserIdAndHostIdOrderByCreatedAtDesc(any(), any());
    }
}
