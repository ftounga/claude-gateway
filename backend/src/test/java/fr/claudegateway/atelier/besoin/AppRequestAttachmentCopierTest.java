package fr.claudegateway.atelier.besoin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.atelier.ProjectFileRead;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;

/** F-205 / SF-205-01 — la copie des pièces vers {@code besoins/{id}/}, et ses échecs. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppRequestAttachmentCopierTest {

    @Mock private AppRequestRepository requests;
    @Mock private AppRequestAttachmentRepository attachments;
    @Mock private WorkspaceService workspaceService;
    @Mock private ProjectFileRead fileRead;

    private final InMemoryWorkspaceStorage storage = new InMemoryWorkspaceStorage();
    private AppRequestAttachmentCopier copier;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private Workspace workspace;
    private final List<AppRequestAttachment> pieces = new ArrayList<>();

    @BeforeEach
    void setUp() {
        copier = new AppRequestAttachmentCopier(requests, attachments, workspaceService, fileRead, storage,
                Runnable::run, 1_000);
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        when(requests.findByIdAndUserId(requestId, userId)).thenReturn(Optional.of(AppRequest.builder()
                .id(requestId).userId(userId).workspaceId(workspaceId).status(AppRequest.EN_ATTENTE).build()));
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(attachments.findByRequestIdAndUserIdOrderBySeqAsc(requestId, userId)).thenReturn(pieces);
        when(attachments.save(any(AppRequestAttachment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AppRequestAttachment piece(int seq, String name) {
        AppRequestAttachment a = AppRequestAttachment.builder().id(UUID.randomUUID()).requestId(requestId)
                .userId(userId).seq(seq).name(name).contentType("image/png").sourcePath(".atelier/entrees/" + name)
                .copyStatus(AppRequestAttachment.PENDING).build();
        pieces.add(a);
        return a;
    }

    @Test
    void aReadablePieceIsCopiedUnderTheRequestPrefix() {
        AppRequestAttachment a = piece(1, "capture écran.png");
        when(fileRead.read(eq(userId), eq(workspace), anyString(), eq(a.getSourcePath()), anyLong(), anyString()))
                .thenReturn(ProjectFileRead.Read.ok(new byte[] {1, 2, 3}));

        copier.copyAsync(userId, requestId);

        assertThat(a.getCopyStatus()).isEqualTo(AppRequestAttachment.COPIED);
        assertThat(a.getStorageKey()).isEqualTo("besoins/" + requestId + "/1-capture__cran.png");
        assertThat(storage.getFile(a.getStorageKey())).contains(new byte[] {1, 2, 3});
    }

    @Test
    void anUnreadablePieceIsMarkedFailedWithItsReasonAndTheOthersGoOn() {
        AppRequestAttachment lost = piece(1, "a.png");
        AppRequestAttachment ok = piece(2, "b.png");
        when(fileRead.read(eq(userId), eq(workspace), anyString(), eq(lost.getSourcePath()), anyLong(), anyString()))
                .thenReturn(ProjectFileRead.Read.error("Fichier illisible sur la machine (a.png)."));
        when(fileRead.read(eq(userId), eq(workspace), anyString(), eq(ok.getSourcePath()), anyLong(), anyString()))
                .thenReturn(ProjectFileRead.Read.ok(new byte[] {9}));

        copier.copyAll(userId, requestId);

        assertThat(lost.getCopyStatus()).isEqualTo(AppRequestAttachment.FAILED);
        assertThat(lost.getCopyError()).contains("illisible");
        assertThat(lost.getStorageKey()).isNull();
        assertThat(ok.getCopyStatus()).isEqualTo(AppRequestAttachment.COPIED);
    }

    @Test
    void aVanishedTerminalFailsThePendingPieces() {
        AppRequestAttachment a = piece(1, "a.png");
        when(workspaceService.requireOwned(userId, workspaceId)).thenThrow(new WorkspaceNotFoundException("x"));

        copier.copyAll(userId, requestId);

        assertThat(a.getCopyStatus()).isEqualTo(AppRequestAttachment.FAILED);
    }

    @Test
    void aFullQueueFailsThePiecesInsteadOfBlocking() {
        AppRequestAttachment a = piece(1, "a.png");
        AppRequestAttachmentCopier rejecting = new AppRequestAttachmentCopier(requests, attachments,
                workspaceService, fileRead, storage, task -> {
                    throw new java.util.concurrent.RejectedExecutionException();
                }, 1_000);

        rejecting.copyAsync(userId, requestId);

        assertThat(a.getCopyStatus()).isEqualTo(AppRequestAttachment.FAILED);
    }

    @Test
    void anotherAccountCopiesNothing() {
        UUID bob = UUID.randomUUID();
        when(requests.findByIdAndUserId(requestId, bob)).thenReturn(Optional.empty());
        AppRequestAttachment a = piece(1, "a.png");

        copier.copyAll(bob, requestId);

        assertThat(a.getCopyStatus()).isEqualTo(AppRequestAttachment.PENDING);
    }
}
