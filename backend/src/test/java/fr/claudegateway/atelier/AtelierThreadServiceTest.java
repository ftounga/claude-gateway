package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.atelier.dto.AtelierResumeResponse;

/**
 * Reprise du fil (F-39 / SF-39-04) : ce qui se reprend en silence, ce qui mérite une question, et
 * ce qu'un « nouveau départ » change — c'est-à-dire la mémoire de l'agent, jamais la conversation.
 */
@ExtendWith(MockitoExtension.class)
class AtelierThreadServiceTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private WorkspaceRepository workspaceRepository;
    @Mock private AtelierMessageRepository messageRepository;

    private AtelierThreadService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new AtelierThreadService(workspaceService, workspaceRepository, messageRepository);
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
    }

    private AtelierMessage messageAt(OffsetDateTime when) {
        AtelierMessage message = AtelierMessage.builder()
                .id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role("USER").content("bonjour").build();
        message.setCreatedAt(when);
        return message;
    }

    @Test
    void anActiveProjectResumesWithoutAskingAnything() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        OffsetDateTime yesterday = OffsetDateTime.now().minusDays(1);
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of(messageAt(yesterday.minusDays(1)), messageAt(yesterday)));

        AtelierResumeResponse state = service.resumeState(userId, workspaceId);

        assertThat(state.prompt()).isEqualTo("NONE");
        assertThat(state.turns()).isEqualTo(2);
        assertThat(state.lastMessageAt()).isEqualTo(yesterday);
        assertThat(state.threadStartedAt()).isNull();
    }

    @Test
    void aProjectUntouchedForFifteenDaysAsksTheQuestion() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of(messageAt(OffsetDateTime.now().minusDays(15))));

        assertThat(service.resumeState(userId, workspaceId).prompt()).isEqualTo("IDLE");
    }

    @Test
    void aProjectWithoutAnyMessageAsksNothingAndCountsZero() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());

        AtelierResumeResponse state = service.resumeState(userId, workspaceId);

        assertThat(state.prompt()).isEqualTo("NONE");
        assertThat(state.turns()).isZero();
        assertThat(state.lastMessageAt()).isNull();
    }

    @Test
    void afterAFreshStartOnlyMessagesPastTheBoundaryAreCounted() {
        OffsetDateTime boundary = OffsetDateTime.now().minusHours(2);
        workspace.setChatThreadStartedAt(boundary);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(messageRepository.findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                workspaceId, userId, boundary)).thenReturn(List.of(messageAt(OffsetDateTime.now())));

        AtelierResumeResponse state = service.resumeState(userId, workspaceId);

        assertThat(state.turns()).isEqualTo(1);
        assertThat(state.threadStartedAt()).isEqualTo(boundary);
        // Le fil complet n'est jamais relu pour ce calcul : la frontière fait foi.
        verify(messageRepository, never()).findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId);
    }

    @Test
    void aFreshStartMovesTheBoundaryAndDeletesNothing() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);

        AtelierResumeResponse state = service.restart(userId, workspaceId);

        assertThat(workspace.getChatThreadStartedAt()).isNotNull();
        assertThat(state.threadStartedAt()).isEqualTo(workspace.getChatThreadStartedAt());
        assertThat(state.turns()).isZero();
        verify(workspaceRepository).save(workspace);
        verify(messageRepository, never()).deleteByWorkspaceId(any());
        verify(messageRepository, never()).deleteByUserId(any());
    }

    @Test
    void aSecondFreshStartSimplyMovesTheBoundaryAgain() {
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);

        OffsetDateTime first = service.restart(userId, workspaceId).threadStartedAt();
        OffsetDateTime second = service.restart(userId, workspaceId).threadStartedAt();

        assertThat(second).isAfterOrEqualTo(first);
    }

    @Test
    void aProjectOneDoesNotOwnIsNeitherReadNorWritten() {
        when(workspaceService.requireOwned(userId, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("introuvable"));

        assertThatThrownBy(() -> service.resumeState(userId, workspaceId))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThatThrownBy(() -> service.restart(userId, workspaceId))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(workspaceRepository, never()).save(any());
    }
}
