package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;

/**
 * Les actions d'un terminal (F-154 / SF-154-01).
 *
 * <p>Ce que ces tests tiennent : une action survit au tour, sa fermeture garde sa raison, ses
 * bornes sont réelles, et <b>le terminal d'un autre est introuvable</b>.</p>
 */
class TerminalActionServiceTest {

    private final TerminalActionRepository repository = mock(TerminalActionRepository.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private TerminalActionService service;

    @BeforeEach
    void setUp() {
        service = new TerminalActionService(repository, workspaces, clock);
        Workspace terminal = new Workspace();
        terminal.setId(workspaceId);
        terminal.setHostId(hostId);
        when(workspaces.requireOwned(userId, workspaceId)).thenReturn(terminal);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private TerminalAction open(String description) {
        return TerminalAction.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .workspaceId(workspaceId)
                .description(description)
                .kind(TerminalActionKind.ACTION)
                .status(TerminalActionStatus.A_FAIRE)
                .createdAt(clock.instant().atOffset(ZoneOffset.UTC))
                .updatedAt(clock.instant().atOffset(ZoneOffset.UTC))
                .build();
    }

    @Test
    @DisplayName("une action naît ouverte, avec ce qu'elle débloque et qui elle concerne")
    void createsAnOpenAction() {
        TerminalAction action = service.create(userId, workspaceId, null,
                "  Demander l'accès VPN à Karim  ", "le déploiement du connecteur",
                "Karim", TerminalActionKind.MESSAGE);

        assertThat(action.getDescription()).isEqualTo("Demander l'accès VPN à Karim"); // nettoyée
        assertThat(action.getBlocks()).isEqualTo("le déploiement du connecteur");
        assertThat(action.getPerson()).isEqualTo("Karim");
        assertThat(action.getKind()).isEqualTo(TerminalActionKind.MESSAGE);
        assertThat(action.getStatus()).isEqualTo(TerminalActionStatus.A_FAIRE);
        assertThat(action.getUserId()).isEqualTo(userId);
        assertThat(action.getWorkspaceId()).isEqualTo(workspaceId);
        verify(repository).save(any());
    }

    @Test
    @DisplayName("une action sans énoncé n'est pas une action")
    void refusesAnEmptyDescription() {
        assertThatThrownBy(() -> service.create(userId, workspaceId, null, "   ", null, null, null))
                .isInstanceOf(InvalidTerminalActionException.class)
                .hasMessageContaining("ce qu'il faut faire");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("les bornes sont réelles : description, ce que ça débloque, la personne")
    void refusesOverlongFields() {
        assertThatThrownBy(() -> service.create(userId, workspaceId, null,
                "x".repeat(TerminalActionService.MAX_DESCRIPTION + 1), null, null, null))
                .isInstanceOf(InvalidTerminalActionException.class);

        assertThatThrownBy(() -> service.create(userId, workspaceId, null, "faire",
                "y".repeat(TerminalActionService.MAX_BLOCKS + 1), null, null))
                .isInstanceOf(InvalidTerminalActionException.class);

        assertThatThrownBy(() -> service.create(userId, workspaceId, null, "faire", null,
                "z".repeat(TerminalActionService.MAX_PERSON + 1), null))
                .isInstanceOf(InvalidTerminalActionException.class);
    }

    @Test
    @DisplayName("au-delà de la limite d'actions ouvertes, on le dit plutôt que d'empiler")
    void refusesWhenTheListIsSaturated() {
        when(repository.countByUserIdAndWorkspaceIdAndStatusIn(userId, workspaceId,
                TerminalActionStatus.OPEN_STATES))
                .thenReturn(TerminalActionService.MAX_OPEN_PER_WORKSPACE);

        assertThatThrownBy(() -> service.create(userId, workspaceId, null, "encore une", null, null, null))
                .isInstanceOf(InvalidTerminalActionException.class)
                .hasMessageContaining("actions ouvertes");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("fermer garde la raison — sans elle, on ne sait plus pourquoi l'action a disparu")
    void closingKeepsItsReason() {
        TerminalAction action = open("Demander l'accès VPN");
        when(repository.findByIdAndUserIdAndWorkspaceId(action.getId(), userId, workspaceId))
                .thenReturn(Optional.of(action));

        TerminalAction closed = service.close(userId, workspaceId, action.getId(),
                "Karim a ouvert l'accès ce matin.");

        assertThat(closed.getStatus()).isEqualTo(TerminalActionStatus.FAIT);
        assertThat(closed.getClosedReason()).isEqualTo("Karim a ouvert l'accès ce matin.");
        assertThat(closed.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("annuler est un droit de l'utilisateur ; refermer une action close est sans effet")
    void cancellingAndClosingTwice() {
        TerminalAction action = open("Relancer le support");
        when(repository.findByIdAndUserIdAndWorkspaceId(action.getId(), userId, workspaceId))
                .thenReturn(Optional.of(action));

        service.cancel(userId, workspaceId, action.getId(), "Plus nécessaire.");
        assertThat(action.getStatus()).isEqualTo(TerminalActionStatus.ANNULE);

        TerminalAction again = service.close(userId, workspaceId, action.getId(), "tardif");
        assertThat(again.getStatus()).isEqualTo(TerminalActionStatus.ANNULE); // inchangée
        assertThat(again.getClosedReason()).isEqualTo("Plus nécessaire.");
    }

    @Test
    @DisplayName("« Rétablir » rouvre une fermeture qui s'était trompée")
    void reopensAMistakenClosure() {
        TerminalAction action = open("Obtenir la validation du RSSI");
        when(repository.findByIdAndUserIdAndWorkspaceId(action.getId(), userId, workspaceId))
                .thenReturn(Optional.of(action));
        service.close(userId, workspaceId, action.getId(), "cru fait");

        TerminalAction reopened = service.reopen(userId, workspaceId, action.getId());

        assertThat(reopened.getStatus()).isEqualTo(TerminalActionStatus.A_FAIRE);
        assertThat(reopened.getClosedReason()).isNull();
        assertThat(reopened.getClosedAt()).isNull();
    }

    @Test
    @DisplayName("ISOLATION — le terminal d'un autre compte est introuvable, et rien n'est lu")
    void anotherAccountSeesNothing() {
        UUID intruder = UUID.randomUUID();
        when(workspaces.requireOwned(intruder, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable"));

        assertThatThrownBy(() -> service.list(intruder, workspaceId, true))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThatThrownBy(() -> service.create(intruder, workspaceId, null, "voir", null, null, null))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThatThrownBy(() -> service.close(intruder, workspaceId, UUID.randomUUID(), null))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verify(repository, never()).save(any());
        verify(repository, never())
                .findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(eq(intruder), any());
    }

    @Test
    @DisplayName("ISOLATION — l'action d'un AUTRE projet du même compte est introuvable")
    void anotherWorkspaceOfTheSameAccountIsNotFound() {
        UUID other = UUID.randomUUID();
        when(repository.findByIdAndUserIdAndWorkspaceId(other, userId, workspaceId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close(userId, workspaceId, other, null))
                .isInstanceOf(TerminalActionNotFoundException.class);
    }

    @Test
    @DisplayName("le menu liste les plus anciennes d'abord ; la pastille compte les ouvertes")
    void listsOldestFirstAndCounts() {
        when(repository.findByUserIdAndWorkspaceIdAndStatusInOrderByCreatedAtAsc(
                userId, workspaceId, TerminalActionStatus.OPEN_STATES))
                .thenReturn(List.of(open("la plus ancienne"), open("la suivante")));
        when(repository.countByUserIdAndWorkspaceIdAndStatusIn(
                userId, workspaceId, TerminalActionStatus.OPEN_STATES)).thenReturn(2);

        assertThat(service.list(userId, workspaceId, true))
                .extracting(TerminalAction::getDescription)
                .containsExactly("la plus ancienne", "la suivante");
        assertThat(service.countOpen(userId, workspaceId)).isEqualTo(2);
    }

    // ---- F-175 / SF-175-01 : trois états et la portée poste ----

    private TerminalAction stored(String description) {
        TerminalAction action = open(description);
        when(repository.findByIdAndUserIdAndWorkspaceId(action.getId(), userId, workspaceId))
                .thenReturn(Optional.of(action));
        return action;
    }

    @Test
    @DisplayName("SF-175-01 : une attente naît « À faire », sur le poste de son terminal")
    void createdOnTheHostOfItsTerminal() {
        TerminalAction action = service.create(userId, workspaceId, null, "Demander l'accès",
                null, null, null);
        assertThat(action.getStatus()).isEqualTo(TerminalActionStatus.A_FAIRE);
        assertThat(action.getHostId()).isEqualTo(hostId);
    }

    @Test
    @DisplayName("SF-175-01 : À faire → Demandé pose la date du serveur, à qui et par où")
    void requestStampsDateRecipientAndChannel() {
        TerminalAction action = stored("Demander le compte forge");
        action.setPerson("Zahi");

        TerminalAction requested = service.changeStatus(userId, workspaceId, action.getId(),
                TerminalActionStatus.DEMANDE, null, null, "Teams");

        assertThat(requested.getStatus()).isEqualTo(TerminalActionStatus.DEMANDE);
        assertThat(requested.getRequestedAt()).isEqualTo(clock.instant().atOffset(ZoneOffset.UTC));
        assertThat(requested.getRequestedTo()).isEqualTo("Zahi"); // repris de la personne
        assertThat(requested.getChannel()).isEqualTo("Teams");
        assertThat(requested.isOpen()).isTrue();
    }

    @Test
    @DisplayName("SF-175-01 : Demandé → À faire garde la trace ; → Fait pose la raison ; même état = sans effet")
    void transitionsBackAndClose() {
        TerminalAction action = stored("Obtenir la dérogation SCP");
        service.changeStatus(userId, workspaceId, action.getId(), TerminalActionStatus.DEMANDE,
                null, "Habib", "courriel");

        TerminalAction back = service.changeStatus(userId, workspaceId, action.getId(),
                TerminalActionStatus.A_FAIRE, null, null, null);
        assertThat(back.getStatus()).isEqualTo(TerminalActionStatus.A_FAIRE);
        assertThat(back.getRequestedAt()).isNotNull();

        TerminalAction done = service.changeStatus(userId, workspaceId, action.getId(),
                TerminalActionStatus.FAIT, "Habib a signé", null, null);
        assertThat(done.getStatus()).isEqualTo(TerminalActionStatus.FAIT);
        assertThat(done.getClosedReason()).isEqualTo("Habib a signé");
        assertThat(done.getClosedAt()).isNotNull();

        TerminalAction again = service.changeStatus(userId, workspaceId, action.getId(),
                TerminalActionStatus.FAIT, "autre", null, null);
        assertThat(again.getClosedReason()).isEqualTo("Habib a signé");
    }

    @Test
    @DisplayName("SF-175-01 : « Rétablir » rend l'état d'avant — Demandé si une demande était partie")
    void reopenRestoresThePreviousState() {
        TerminalAction asked = stored("Relancer Zahi");
        service.changeStatus(userId, workspaceId, asked.getId(), TerminalActionStatus.DEMANDE,
                null, "Zahi", null);
        service.close(userId, workspaceId, asked.getId(), "c'est bon");
        assertThat(service.reopen(userId, workspaceId, asked.getId()).getStatus())
                .isEqualTo(TerminalActionStatus.DEMANDE);

        TerminalAction plain = stored("Vérifier le droit");
        service.cancel(userId, workspaceId, plain.getId(), null);
        assertThat(service.reopen(userId, workspaceId, plain.getId()).getStatus())
                .isEqualTo(TerminalActionStatus.A_FAIRE);
    }

    @Test
    @DisplayName("SF-175-01 : statut absent ou bornes dépassées sont refusés")
    void refusesMissingStatusAndOversizedFields() {
        TerminalAction action = stored("Demander");
        assertThatThrownBy(() -> service.changeStatus(userId, workspaceId, action.getId(),
                null, null, null, null)).isInstanceOf(InvalidTerminalActionException.class);
        assertThatThrownBy(() -> service.changeStatus(userId, workspaceId, action.getId(),
                TerminalActionStatus.DEMANDE, null, null,
                "c".repeat(TerminalActionService.MAX_CHANNEL + 1)))
                .isInstanceOf(InvalidTerminalActionException.class);
        assertThatThrownBy(() -> service.changeStatus(userId, workspaceId, action.getId(),
                TerminalActionStatus.DEMANDE, null,
                "p".repeat(TerminalActionService.MAX_REQUESTED_TO + 1), null))
                .isInstanceOf(InvalidTerminalActionException.class);
    }

    @Test
    @DisplayName("SF-175-01 : l'édition change ce qui est donné ; une attente fermée ne s'édite pas")
    void editsOpenOnly() {
        TerminalAction action = stored("Demander l'accès");
        TerminalAction edited = service.edit(userId, workspaceId, action.getId(),
                "Demander l'accès VPN à Karim", null, "Karim", TerminalActionKind.MESSAGE);
        assertThat(edited.getDescription()).isEqualTo("Demander l'accès VPN à Karim");
        assertThat(edited.getPerson()).isEqualTo("Karim");
        assertThat(edited.getKind()).isEqualTo(TerminalActionKind.MESSAGE);

        service.close(userId, workspaceId, action.getId(), null);
        assertThatThrownBy(() -> service.edit(userId, workspaceId, action.getId(),
                "autre", null, null, null))
                .isInstanceOf(InvalidTerminalActionException.class)
                .hasMessageContaining("rétablissez");
    }

    @Test
    @DisplayName("SF-175-01 : une clé déjà « Demandé » rend ALREADY_REQUESTED")
    void recordingAnAlreadyRequestedKey() {
        assertThat(TerminalActionService.outcomeOf(TerminalActionStatus.DEMANDE))
                .isEqualTo(TerminalActionService.RecordingOutcome.ALREADY_REQUESTED);
        assertThat(TerminalActionService.outcomeOf(TerminalActionStatus.A_FAIRE))
                .isEqualTo(TerminalActionService.RecordingOutcome.ALREADY_OPEN);
    }

    @Test
    @DisplayName("SF-175-01 : l'état d'un terminal d'autrui reste introuvable")
    void statusChangeOnSomeoneElsesTerminalIsNotFound() {
        UUID intruder = UUID.randomUUID();
        when(workspaces.requireOwned(intruder, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable"));
        assertThatThrownBy(() -> service.changeStatus(intruder, workspaceId, UUID.randomUUID(),
                TerminalActionStatus.FAIT, null, null, null))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }
}
