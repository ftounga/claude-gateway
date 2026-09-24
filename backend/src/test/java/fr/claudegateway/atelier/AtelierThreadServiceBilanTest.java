package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fr.claudegateway.auth.AuthenticatedUser;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.bilan.BilanTrigger;
import fr.claudegateway.bilan.SessionBilanTriggerService;
import fr.claudegateway.user.UserRole;

/**
 * Le bilan au nouveau départ (F-155 / SF-155-03).
 *
 * <p>Ce que ces tests tiennent : la session relevée est bien <b>celle qui se ferme</b> (la fenêtre
 * est prise <b>avant</b> que la frontière ne bouge), le rôle vient du contexte de sécurité, et le
 * nouveau départ <b>réussit toujours</b> — avec ou sans bilan.</p>
 */
class AtelierThreadServiceBilanTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
    private final AtelierMessageRepository messageRepository = mock(AtelierMessageRepository.class);
    private final SessionBilanTriggerService bilan = mock(SessionBilanTriggerService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final OffsetDateTime previousBoundary = OffsetDateTime.parse("2026-09-24T08:00:00Z");

    private AtelierThreadService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new AtelierThreadService(workspaceService, workspaceRepository, messageRepository);
        service.setBilan(bilan, currentUser);
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setChatThreadStartedAt(previousBoundary);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(currentUser.principal())
                .thenReturn(Optional.of(new AuthenticatedUser(userId, "po@ex.com", UserRole.ADMIN)));
    }

    private void decides(BilanTrigger trigger) {
        when(bilan.decide(eq(userId), eq(workspaceId), any(), any(), any()))
                .thenReturn(new SessionBilanTriggerService.Decision(trigger, null, null));
    }

    @Test
    @DisplayName("la fenêtre relevée part de la frontière PRÉCÉDENTE — sinon il n'y aurait rien à relever")
    void theWindowStartsAtThePreviousBoundary() {
        decides(BilanTrigger.AUTOMATIQUE);

        service.restart(userId, workspaceId);

        ArgumentCaptor<OffsetDateTime> from = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> to = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(bilan).decide(eq(userId), eq(workspaceId), eq(UserRole.ADMIN),
                from.capture(), to.capture());

        assertThat(from.getValue()).isEqualTo(previousBoundary);
        assertThat(to.getValue()).isAfter(previousBoundary);
        assertThat(workspace.getChatThreadStartedAt())
                .as("la frontière a bien bougé ENSUITE").isEqualTo(to.getValue());
    }

    @Test
    @DisplayName("sans frontière précédente, la fenêtre part de la création : la première session en est une")
    void theFirstSessionIsASession() {
        OffsetDateTime created = OffsetDateTime.parse("2026-09-20T10:00:00Z");
        workspace.setChatThreadStartedAt(null);
        workspace.setCreatedAt(created);
        decides(BilanTrigger.PROPOSE);

        service.restart(userId, workspaceId);

        verify(bilan).decide(eq(userId), eq(workspaceId), eq(UserRole.ADMIN), eq(created), any());
    }

    @Test
    @DisplayName("l'issue du bilan est rendue à l'écran, sans casser la réponse de reprise")
    void theOutcomeReachesTheScreen() {
        decides(BilanTrigger.AUTOMATIQUE);
        assertThat(service.restart(userId, workspaceId).bilan()).isEqualTo("AUTOMATIQUE");

        decides(BilanTrigger.AUCUN);
        assertThat(service.restart(userId, workspaceId).bilan()).isEqualTo("AUCUN");
    }

    @Test
    @DisplayName("le rôle vient du contexte de sécurité, jamais d'un paramètre")
    void theRoleComesFromTheSecurityContext() {
        when(currentUser.principal())
                .thenReturn(Optional.of(new AuthenticatedUser(userId, "u@ex.com", UserRole.USER)));
        decides(BilanTrigger.AUCUN);

        service.restart(userId, workspaceId);

        verify(bilan).decide(eq(userId), eq(workspaceId), eq(UserRole.USER), any(), any());
    }

    @Test
    @DisplayName("sans bilan branché, le nouveau départ se comporte EXACTEMENT comme avant")
    void withoutTheBilanNothingChanges() {
        AtelierThreadService bare = new AtelierThreadService(
                workspaceService, workspaceRepository, messageRepository);

        assertThat(bare.restart(userId, workspaceId).bilan()).isEqualTo("AUCUN");
        verify(bilan, never()).decide(any(), any(), any(), any(), any());
        assertThat(workspace.getChatThreadStartedAt()).isAfter(previousBoundary);
    }

    @Test
    @DisplayName("un nouveau départ efface toujours résumé, mode et plan — le bilan n'y change rien")
    void theRestartStillDoesItsJob() {
        workspace.setChatThreadSummary("un résumé");
        workspace.setChatThreadMode("ACT");
        workspace.setChatThreadPlan("[]");
        decides(BilanTrigger.AUTOMATIQUE);

        service.restart(userId, workspaceId);

        assertThat(workspace.getChatThreadSummary()).isNull();
        assertThat(workspace.getChatThreadMode()).isNull();
        assertThat(workspace.getChatThreadPlan()).isNull();
        verify(workspaceRepository).save(workspace);
    }
}
