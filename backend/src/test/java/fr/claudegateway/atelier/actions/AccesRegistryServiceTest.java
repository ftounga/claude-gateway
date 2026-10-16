package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;

/**
 * Le registre des accès (F-180 / SF-180-04) : ajout sans secret, bornes et dates, proposition après Fait,
 * « pas besoin », la correspondance au système, l'expiration, le message du tour — et le dédoublonnage
 * « déjà obtenu » à l'inscription.
 */
class AccesRegistryServiceTest {

    private final AccesObtenuRepository repository = mock(AccesObtenuRepository.class);
    private final TerminalActionRepository actions = mock(TerminalActionRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final List<AccesObtenu> stored = new ArrayList<>();
    private Workspace terminal;
    private AccesRegistryService service;

    @BeforeEach
    void setUp() {
        service = new AccesRegistryService(repository, actions, workspaces, clock);
        terminal = new Workspace();
        terminal.setId(workspaceId);
        terminal.setHostId(hostId);
        terminal.setName("data-platform");
        when(workspaces.findByIdAndUserId(workspaceId, userId)).thenReturn(Optional.of(terminal));
        when(repository.save(any())).thenAnswer(i -> {
            AccesObtenu a = i.getArgument(0);
            if (a.getId() == null) {
                a.setId(UUID.randomUUID());
                stored.add(a);
            }
            return a;
        });
        when(repository.findByUserIdAndHostIdOrderByObtainedAtDesc(userId, hostId)).thenAnswer(i -> stored);
        when(repository.findByIdAndUserId(any(), eq(userId)))
                .thenAnswer(i -> stored.stream().filter(a -> a.getId().equals(i.getArgument(0))).findFirst());
    }

    private AccesRegistryService.AccesRequest req(String system, String account, LocalDate expires) {
        return new AccesRegistryService.AccesRequest(null, system, account, LocalDate.of(2026, 10, 2), "Rémi", expires, null);
    }

    @Test
    @DisplayName("ajout : système, compte, date, par qui, expiration")
    void create() {
        var view = service.create(userId, workspaceId, req("VPN Fortinet", "k.dupont", LocalDate.of(2026, 12, 31)));
        assertThat(view.system()).isEqualTo("VPN Fortinet");
        assertThat(view.account()).isEqualTo("k.dupont");
        assertThat(view.expired()).isFalse();
        assertThat(stored.get(0).getHostId()).isEqualTo(hostId);
        assertThat(stored.get(0).getNormalizedSystem()).isEqualTo("vpn fortinet");
    }

    @Test
    @DisplayName("un secret dans n'importe quel champ : refusé, rien d'écrit")
    void refusesSecrets() {
        assertThatThrownBy(() -> service.create(userId, workspaceId, req("VPN", "mdp Soleil2026!", null)))
                .isInstanceOf(InvalidTerminalActionException.class).hasMessageContaining("aucun secret");
        assertThatThrownBy(() -> service.create(userId, workspaceId,
                new AccesRegistryService.AccesRequest(null, "VPN", null, null, null, null, "token: abcdef123")))
                .isInstanceOf(InvalidTerminalActionException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("bornes et dates : système requis, expiration avant obtention refusée")
    void bounds() {
        assertThatThrownBy(() -> service.create(userId, workspaceId, req(" ", null, null)))
                .isInstanceOf(InvalidTerminalActionException.class);
        assertThatThrownBy(() -> service.create(userId, workspaceId, req("VPN", null, LocalDate.of(2026, 9, 1))))
                .isInstanceOf(InvalidTerminalActionException.class).hasMessageContaining("précéder");
        assertThatThrownBy(() -> service.create(userId, workspaceId, req("x".repeat(121), null, null)))
                .isInstanceOf(InvalidTerminalActionException.class);
    }

    @Test
    @DisplayName("proposition : une attente ACCES faite récemment, sans entrée ni refus")
    void proposals() {
        TerminalAction done = TerminalAction.builder().id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId)
                .hostId(hostId).description("Demander l'accès VPN à Karim").categorie(TerminalActionCategorie.ACCES)
                .status(TerminalActionStatus.FAIT).person("Karim").closedAt(OffsetDateTime.now(clock).minusDays(2))
                .createdAt(OffsetDateTime.now(clock).minusDays(5)).build();
        TerminalAction validation = TerminalAction.builder().id(UUID.randomUUID()).userId(userId)
                .workspaceId(workspaceId).hostId(hostId).description("Valider").categorie(TerminalActionCategorie.VALIDATION)
                .status(TerminalActionStatus.FAIT).closedAt(OffsetDateTime.now(clock)).build();
        when(actions.findByUserIdAndHostId(userId, hostId)).thenReturn(List.of(done, validation));

        var proposals = service.proposals(userId, workspaceId);

        assertThat(proposals).singleElement().satisfies(p -> {
            assertThat(p.system()).isEqualTo("VPN");
            assertThat(p.grantedBy()).isEqualTo("Karim");
            assertThat(p.obtainedAt()).isEqualTo(LocalDate.of(2026, 10, 14));
        });

        when(actions.findByIdAndUserId(done.getId(), userId)).thenReturn(Optional.of(done));
        service.dismissProposal(userId, workspaceId, done.getId());
        assertThat(service.proposals(userId, workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("système déduit de l'énoncé")
    void systemOf() {
        assertThat(AccesRegistryService.systemOf("Demander l'accès VPN à Karim")).isEqualTo("VPN");
        assertThat(AccesRegistryService.systemOf("Obtenir un compte sur la forge CAPFM")).isEqualTo("la forge CAPFM");
        assertThat(AccesRegistryService.systemOf("Jira")).isEqualTo("Jira");
    }

    @Test
    @DisplayName("correspondance : tous les mots du système dans l'énoncé, accès non expiré")
    void findObtained() {
        service.create(userId, workspaceId, req("VPN Fortinet", null, LocalDate.of(2026, 12, 31)));
        assertThat(service.findObtained(userId, terminal, "Demander l'accès au VPN Fortinet")).isPresent();
        assertThat(service.findObtained(userId, terminal, "Demander l'accès au VPN")).isEmpty();

        stored.get(0).setExpiresAt(LocalDate.of(2026, 10, 10));
        assertThat(service.findObtained(userId, terminal, "Demander l'accès au VPN Fortinet")).isEmpty();
    }

    @Test
    @DisplayName("message du tour : compact, expiré signalé")
    void turnBlock() {
        service.create(userId, workspaceId, req("VPN Fortinet", "k.dupont", LocalDate.of(2026, 10, 10)));
        String block = service.turnBlock(userId, terminal);
        assertThat(block).contains("- VPN Fortinet · compte k.dupont · obtenu le 02/10 par Rémi · EXPIRÉ le 10/10/2026");
        assertThat(block).startsWith("--- Accès déjà obtenus");
    }

    @Test
    @DisplayName("hors portée : introuvable")
    void isolation() {
        service.create(userId, workspaceId, req("VPN", null, null));
        UUID id = stored.get(0).getId();
        stored.get(0).setHostId(UUID.randomUUID()); // un autre poste
        assertThatThrownBy(() -> service.delete(userId, workspaceId, id))
                .isInstanceOf(TerminalActionNotFoundException.class);
        assertThatThrownBy(() -> service.list(UUID.randomUUID(), workspaceId))
                .isInstanceOf(TerminalActionNotFoundException.class);
    }

    @Test
    @DisplayName("inscription par l'agent : déjà au registre → rien d'inscrit, « déjà obtenu le … (expire le …) »")
    void recordAlreadyObtained() {
        service.create(userId, workspaceId, req("VPN Fortinet", "k.dupont", LocalDate.of(2026, 12, 31)));
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(terminal);
        TerminalActionRepository actionRepo = mock(TerminalActionRepository.class);
        when(actionRepo.findByUserIdAndWorkspaceIdAndDedupKey(any(), any(), anyString())).thenReturn(Optional.empty());
        TerminalActionService actionService = new TerminalActionService(actionRepo, workspaceService, clock);
        actionService.setRegistry(service);

        var recording = actionService.record(userId, workspaceId, null, "Demander l'accès VPN Fortinet à Rémi",
                null, "Rémi", TerminalActionKind.ACTION, "vpn-fortinet",
                new TerminalActionService.Qualification(TerminalActionCategorie.ACCES, TerminalActionQui.CLIENT));

        assertThat(recording.outcome()).isEqualTo(TerminalActionService.RecordingOutcome.ALREADY_OBTAINED);
        verify(actionRepo, never()).save(any());
        String message = TerminalActionToolExecutor.obtainedMessage(recording.acces());
        assertThat(message).contains("DÉJÀ OBTENU le 02/10/2026 (expire le 31/12/2026)");

        // Une VALIDATION ne se compare pas au registre ; elle est inscrite.
        when(actionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        var validation = actionService.record(userId, workspaceId, null, "Valider le VPN Fortinet", null, null,
                TerminalActionKind.ACTION, "valider-vpn",
                new TerminalActionService.Qualification(TerminalActionCategorie.VALIDATION, null));
        assertThat(validation.outcome()).isEqualTo(TerminalActionService.RecordingOutcome.RECORDED);
    }

    @Test
    @DisplayName("inscription : la même clé faite récemment ailleurs sur le poste → « déjà obtenu le … »")
    void recordRecentDoneOnHost() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(terminal);
        TerminalActionRepository actionRepo = mock(TerminalActionRepository.class);
        when(actionRepo.findByUserIdAndWorkspaceIdAndDedupKey(any(), any(), anyString())).thenReturn(Optional.empty());
        TerminalAction done = TerminalAction.builder().id(UUID.randomUUID()).userId(userId)
                .workspaceId(UUID.randomUUID()).hostId(hostId).description("Compte forge").dedupKey("compte-forge")
                .status(TerminalActionStatus.FAIT).closedAt(OffsetDateTime.now(clock).minusDays(3)).build();
        when(actionRepo.findByUserIdAndHostIdAndDedupKeyOrderByCreatedAtDesc(userId, hostId, "compte-forge"))
                .thenReturn(List.of(done));
        TerminalActionService actionService = new TerminalActionService(actionRepo, workspaceService, clock);

        var recording = actionService.record(userId, workspaceId, null, "Obtenir le compte forge", null, null,
                TerminalActionKind.ACTION, "compte-forge");

        assertThat(recording.outcome()).isEqualTo(TerminalActionService.RecordingOutcome.ALREADY_DONE);
        assertThat(TerminalActionToolExecutor.doneOn(recording.action())).isEqualTo(" (déjà obtenu le 13/10)");
    }
}
