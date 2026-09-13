package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * Calcul de l'état runner (F-38 / SF-38-02) : fraîcheur du dernier heartbeat — seule preuve de vie
 * depuis F-97 / SF-97-01, le registre n'est plus lu —, sous double vérification d'appartenance
 * (isolation {@code user_id}).
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
    private WorkspaceService workspaceService;
    @Mock
    private RunnerHostService hostService;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private RunnerStatusService service() {
        return new RunnerStatusService(tokenRepository, workspaceService, hostService,
                new RunnerLiveness(tokenRepository, Duration.ofSeconds(90)));
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
    void aHeartbeatOlderThanTheWindowIsOfflineWhateverTheSocket() {
        // F-97 / SF-97-01 : le battement fait foi. 91 s sans battement = hors ligne, même si une
        // socket à moitié ouverte est encore enregistrée (le service ne lit plus le registre).
        givenAttachedProject(null);
        OffsetDateTime justStale = OffsetDateTime.now().minusSeconds(91);
        givenTokens(tokenLastSeen(justStale));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isFalse();
        assertThat(status.lastSeenAt()).isEqualTo(justStale);
        assertThat(status.hostId()).isEqualTo(hostId);
    }

    @Test
    void aHeartbeatJustInsideTheWindowIsOnline() {
        givenAttachedProject(null);
        givenTokens(tokenLastSeen(OffsetDateTime.now().minusSeconds(85)));

        assertThat(service().status(userId, workspaceId).connected()).isTrue();
    }

    @Test
    void theMostRecentHeartbeatAmongTokensDecides() {
        givenAttachedProject(null);
        OffsetDateTime fresh = OffsetDateTime.now().minusSeconds(5);
        givenTokens(tokenLastSeen(OffsetDateTime.now().minusHours(2)), tokenLastSeen(fresh),
                tokenLastSeen(null));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isTrue();
        assertThat(status.lastSeenAt()).isEqualTo(fresh);
    }

    @Test
    void connectedWhenHeartbeatFreshThoughRegistryEmpty() {
        givenAttachedProject(null);
        // Cas cross-replica : la socket vit sur l'autre pod, le registre local ne la voit pas, mais
        // le heartbeat a rafraichi last_seen_at dans la base partagee.
        OffsetDateTime fresh = OffsetDateTime.now().minusSeconds(10);
        givenTokens(tokenLastSeen(fresh));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isTrue();
        assertThat(status.lastSeenAt()).isEqualTo(fresh);
    }

    @Test
    void disconnectedWhenRegistryEmptyAndHeartbeatStale() {
        givenAttachedProject(null);
        OffsetDateTime stale = OffsetDateTime.now().minusMinutes(5);
        givenTokens(tokenLastSeen(stale));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isFalse();
        assertThat(status.lastSeenAt()).isEqualTo(stale);
    }

    @Test
    void disconnectedWhenNeverSeen() {
        givenAttachedProject(null);
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
        // F-82 / SF-82-04 : pas de poste, donc pas de jeton, donc rien à reprendre. L'écran doit
        // ouvrir le parcours complet et non proposer une relance qui n'irait nulle part.
        assertThat(status.paired()).isFalse();
        assertThat(status.hostId()).isNull();
        verify(tokenRepository, never()).findByUserIdAndHostIdOrderByCreatedAtDesc(any(), any());
    }

    // ---------- Le poste porte-t-il encore un jeton utilisable ? (F-82 / SF-82-04) ----------

    /** Jeton dont on choisit l'échéance et la révocation — les deux seuls critères de `paired`. */
    private RunnerToken token(OffsetDateTime expiresAt, OffsetDateTime revokedAt) {
        return RunnerToken.builder()
                .userId(userId).hostId(hostId)
                .tokenHash("h").expiresAt(expiresAt).revokedAt(revokedAt)
                .build();
    }

    @Test
    void pairedWhenTheHostStillCarriesALiveToken() {
        // LE cas courant : la machine est éteinte, mais son jeton est sur son disque. Aucun code
        // d'appairage n'est nécessaire — seulement relancer le runner.
        givenAttachedProject(null);
        givenTokens(token(OffsetDateTime.now().plusDays(10), null));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.connected()).isFalse();
        assertThat(status.paired()).isTrue();
    }

    @Test
    void notPairedWhenEveryTokenHasBeenRevoked() {
        // Après le coupe-circuit (SF-38-08) : la reprise échouerait, et proposer un geste voué à
        // l'échec est pire que ne rien proposer. L'écran redemande donc un code.
        givenAttachedProject(null);
        OffsetDateTime later = OffsetDateTime.now().plusDays(10);
        givenTokens(token(later, OffsetDateTime.now().minusMinutes(1)),
                token(later, OffsetDateTime.now().minusHours(3)));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.paired()).isFalse();
    }

    @Test
    void notPairedWhenTheOnlyTokenHasExpired() {
        givenAttachedProject(null);
        givenTokens(token(OffsetDateTime.now().minusMinutes(1), null));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.paired()).isFalse();
    }

    @Test
    void pairedWhenOneTokenSurvivesAmongRevokedOnes() {
        // Un seul jeton utilisable suffit : c'est celui que le runner présentera.
        givenAttachedProject(null);
        OffsetDateTime later = OffsetDateTime.now().plusDays(10);
        givenTokens(token(later, OffsetDateTime.now().minusMinutes(1)), token(later, null));

        RunnerStatus status = service().status(userId, workspaceId);

        assertThat(status.paired()).isTrue();
    }

    @Test
    void pairedIsReadOnlyFromTheOwnersTokens() {
        // Isolation : la lecture passe par user_id ET host_id. Aucune autre lecture n'existe.
        givenAttachedProject(null);
        givenTokens(token(OffsetDateTime.now().plusDays(10), null));

        service().status(userId, workspaceId);

        verify(tokenRepository).findByUserIdAndHostIdOrderByCreatedAtDesc(userId, hostId);
    }

    // ---------- Interpréteur élu (F-45 / SF-45-05), désormais porté par le POSTE ----------

    @Test
    void statusCarriesTheShellDeclaredByTheMachine() {
        givenAttachedProject("powershell");
        givenTokens();

        assertThat(service().status(userId, workspaceId).shell()).isEqualTo("powershell");
    }

    @Test
    void statusHasNoShellWhenNoRunnerEverDeclaredOne() {
        // Runner anterieur a SF-38-27, ou machine jamais connectee : l'ecran doit OMETTRE la ligne,
        // pas afficher un defaut.
        givenAttachedProject(null);
        givenTokens();

        assertThat(service().status(userId, workspaceId).shell()).isNull();
    }

    @Test
    void statusDropsAShellOutsideTheWhitelist() {
        // La colonne est alimentee par une trame client : une valeur inconnue ne sort pas d'ici.
        givenAttachedProject("zsh-maison");
        givenTokens();

        assertThat(service().status(userId, workspaceId).shell()).isNull();
    }

    @Test
    void statusRequiresWorkspaceOwnership() {
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
