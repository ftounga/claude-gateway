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
 * Les actions d'un terminal (F-151 / SF-151-01).
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

    private TerminalActionService service;

    @BeforeEach
    void setUp() {
        service = new TerminalActionService(repository, workspaces, clock);
        when(workspaces.requireOwned(userId, workspaceId)).thenReturn(new Workspace());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private TerminalAction open(String description) {
        return TerminalAction.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .workspaceId(workspaceId)
                .description(description)
                .kind(TerminalActionKind.ACTION)
                .status(TerminalActionStatus.OPEN)
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
        assertThat(action.getStatus()).isEqualTo(TerminalActionStatus.OPEN);
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
        when(repository.countByUserIdAndWorkspaceIdAndStatus(userId, workspaceId,
                TerminalActionStatus.OPEN))
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

        assertThat(closed.getStatus()).isEqualTo(TerminalActionStatus.DONE);
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
        assertThat(action.getStatus()).isEqualTo(TerminalActionStatus.CANCELLED);

        TerminalAction again = service.close(userId, workspaceId, action.getId(), "tardif");
        assertThat(again.getStatus()).isEqualTo(TerminalActionStatus.CANCELLED); // inchangée
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

        assertThat(reopened.getStatus()).isEqualTo(TerminalActionStatus.OPEN);
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
        when(repository.findByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtAsc(
                userId, workspaceId, TerminalActionStatus.OPEN))
                .thenReturn(List.of(open("la plus ancienne"), open("la suivante")));
        when(repository.countByUserIdAndWorkspaceIdAndStatus(
                userId, workspaceId, TerminalActionStatus.OPEN)).thenReturn(2);

        assertThat(service.list(userId, workspaceId, true))
                .extracting(TerminalAction::getDescription)
                .containsExactly("la plus ancienne", "la suivante");
        assertThat(service.countOpen(userId, workspaceId)).isEqualTo(2);
    }
}
