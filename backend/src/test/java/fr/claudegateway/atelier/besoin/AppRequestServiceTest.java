package fr.claudegateway.atelier.besoin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
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

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.deposit.AtelierDepositedFile;
import fr.claudegateway.atelier.deposit.AtelierDepositedFileRepository;

/** F-205 / SF-205-01 — le registre des besoins : proposer, décider, administrer. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppRequestServiceTest {

    @Mock private AppRequestRepository requests;
    @Mock private AppRequestAttachmentRepository attachments;
    @Mock private AtelierDepositedFileRepository deposits;
    @Mock private WorkspaceService workspaceService;
    @Mock private AppRequestAttachmentCopier copier;

    private AppRequestService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace workspace;
    private final List<AppRequestAttachment> savedPieces = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new AppRequestService(requests, attachments, deposits, workspaceService, copier);
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(requests.save(any(AppRequest.class))).thenAnswer(inv -> {
            AppRequest r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
        when(attachments.save(any(AppRequestAttachment.class))).thenAnswer(inv -> {
            AppRequestAttachment a = inv.getArgument(0);
            savedPieces.add(a);
            return a;
        });
        when(deposits.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId)).thenReturn(List.of(
                deposit("entrees/capture.png", 1200), deposit("entrees/demo.mp4", 50_000)));
    }

    private AtelierDepositedFile deposit(String path, long size) {
        return AtelierDepositedFile.builder().id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId)
                .path(path).sizeBytes(size).createdAt(OffsetDateTime.now()).build();
    }

    private AppRequest proposed() {
        OffsetDateTime now = OffsetDateTime.now();
        return AppRequest.builder().id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId).kind("BUG")
                .title("t").description("d").status(AppRequest.PROPOSE).createdAt(now).updatedAt(now).build();
    }

    @Test
    void aProposalIsStoredProposedWithItsDepositedPieces() {
        AppRequestBlock block = service.propose(userId, workspace, null, "bug", "Le terminal se fige",
                "il se fige", "Le terminal ne répond plus après collage", "écran terminal",
                List.of("entrees/capture.png", "./demo.mp4", "entrees/capture.png"));

        assertThat(block.kind()).isEqualTo("BUG");
        assertThat(block.attachments()).extracting(AppRequestBlock.Piece::name)
                .containsExactly("capture.png", "demo.mp4");
        assertThat(block.attachments()).extracting(AppRequestBlock.Piece::contentType)
                .containsExactly("image/png", "video/mp4");
        assertThat(savedPieces).allSatisfy(p -> assertThat(p.getCopyStatus()).isNull());
        verify(copier, never()).copyAsync(any(), any());
    }

    @Test
    void aPieceThatIsNotADepositIsRefused() {
        assertThatThrownBy(() -> service.propose(userId, workspace, null, "BUG", "t", "d", null, null,
                List.of("src/secret.env")))
                .isInstanceOf(AppRequestInvalidException.class)
                .hasMessageContaining("src/secret.env");
    }

    @Test
    void invalidFieldsAreRefused() {
        assertThatThrownBy(() -> service.propose(userId, workspace, null, "IDEE", "t", "d", null, null, null))
                .isInstanceOf(AppRequestInvalidException.class);
        assertThatThrownBy(() -> service.propose(userId, workspace, null, "BUG", " ", "d", null, null, null))
                .isInstanceOf(AppRequestInvalidException.class);
        assertThatThrownBy(() -> service.propose(userId, workspace, null, "BUG", "t", "", null, null, null))
                .isInstanceOf(AppRequestInvalidException.class);
        assertThatThrownBy(() -> service.propose(userId, workspace, null, "BUG", "x".repeat(201), "d", null,
                null, null)).isInstanceOf(AppRequestInvalidException.class);
    }

    @Test
    void recordingPutsItInTheQueueAndSchedulesTheCopy() {
        AppRequest r = proposed();
        AppRequestAttachment piece = AppRequestAttachment.builder().requestId(r.getId()).userId(userId).seq(1)
                .name("capture.png").contentType("image/png").sourcePath("entrees/capture.png").build();
        when(requests.findByIdAndUserIdAndWorkspaceId(r.getId(), userId, workspaceId)).thenReturn(Optional.of(r));
        when(attachments.findByRequestIdAndUserIdOrderBySeqAsc(r.getId(), userId)).thenReturn(List.of(piece));

        AppRequestView view = service.record(userId, workspaceId, r.getId());

        assertThat(view.status()).isEqualTo(AppRequest.EN_ATTENTE);
        assertThat(view.recordedAt()).isNotNull();
        assertThat(view.attachments()).extracting(AppRequestView.AttachmentView::copyStatus)
                .containsExactly(AppRequestAttachment.PENDING);
        verify(copier).copyAsync(userId, r.getId());
    }

    @Test
    void decidingTwiceIsAConflict() {
        AppRequest r = proposed();
        r.setStatus(AppRequest.EN_ATTENTE);
        when(requests.findByIdAndUserIdAndWorkspaceId(r.getId(), userId, workspaceId)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.record(userId, workspaceId, r.getId()))
                .isInstanceOf(AppRequestConflictException.class);
        assertThatThrownBy(() -> service.dismiss(userId, workspaceId, r.getId()))
                .isInstanceOf(AppRequestConflictException.class);
    }

    @Test
    void dismissingRejectsWithoutCopy() {
        AppRequest r = proposed();
        when(requests.findByIdAndUserIdAndWorkspaceId(r.getId(), userId, workspaceId)).thenReturn(Optional.of(r));

        assertThat(service.dismiss(userId, workspaceId, r.getId()).status()).isEqualTo(AppRequest.REJETE);
        verify(copier, never()).copyAsync(any(), any());
    }

    @Test
    void anotherAccountSeesNothing() {
        UUID bob = UUID.randomUUID();
        when(workspaceService.requireOwned(bob, workspaceId)).thenThrow(new WorkspaceNotFoundException("x"));
        when(requests.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(bob, workspaceId, UUID.randomUUID()))
                .isInstanceOf(WorkspaceNotFoundException.class);
        assertThatThrownBy(() -> service.detail(bob, UUID.randomUUID()))
                .isInstanceOf(AppRequestNotFoundException.class);
    }

    @Test
    void theAdministratorSetsStatusAndFeature() {
        AppRequest r = proposed();
        r.setStatus(AppRequest.EN_ATTENTE);
        when(requests.findByIdAndUserId(r.getId(), userId)).thenReturn(Optional.of(r));

        AppRequestView view = service.update(userId, r.getId(), "cadre", "f-210");

        assertThat(view.status()).isEqualTo(AppRequest.CADRE);
        assertThat(view.featureRef()).isEqualTo("F-210");
        assertThat(service.update(userId, r.getId(), null, "").featureRef()).isNull();
        assertThatThrownBy(() -> service.update(userId, r.getId(), "PROPOSE", null))
                .isInstanceOf(AppRequestInvalidException.class);
        assertThatThrownBy(() -> service.update(userId, r.getId(), null, "ticket-12"))
                .isInstanceOf(AppRequestInvalidException.class);
    }

    @Test
    void anUnrecordedRequestCannotBeUpdatedByTheAdministrator() {
        AppRequest r = proposed();
        when(requests.findByIdAndUserId(r.getId(), userId)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.update(userId, r.getId(), "CADRE", null))
                .isInstanceOf(AppRequestConflictException.class);
    }

    @Test
    void theListFiltersByStatusAndRefusesAnUnknownOne() {
        when(requests.findByUserIdAndAmendsRequestIdIsNullAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of());

        assertThat(service.list(userId, "EN_ATTENTE")).isEmpty();
        verify(requests).findByUserIdAndAmendsRequestIdIsNullAndStatusInOrderByCreatedAtDesc(userId,
                java.util.Set.of("EN_ATTENTE"));
        assertThatThrownBy(() -> service.list(userId, "OUVERT")).isInstanceOf(AppRequestInvalidException.class);
    }

    @Test
    void contentTypesComeFromTheExtension() {
        assertThat(AppRequestService.contentTypeOf("a.JPG")).isEqualTo("image/jpeg");
        assertThat(AppRequestService.contentTypeOf("a.mov")).isEqualTo("video/quicktime");
        assertThat(AppRequestService.contentTypeOf("page.html")).isEqualTo("application/octet-stream");
        assertThat(AppRequestService.contentTypeOf("sans-extension")).isEqualTo("application/octet-stream");
    }

    // ------------------------------------------------------------------ F-205 / SF-205-04 : compléter

    private AppRequest recorded(String title, String status) {
        AppRequest r = proposed();
        r.setKind("EVOLUTION");
        r.setTitle(title);
        r.setStatus(status);
        when(requests.findByIdAndUserId(r.getId(), userId)).thenReturn(Optional.of(r));
        return r;
    }

    private void known(AppRequest... all) {
        when(requests.findByUserIdAndAmendsRequestIdIsNullAndStatusInOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(all));
    }

    @Test
    void aComplementByIdInheritsKindAndTitleAndIsProposedAttached() {
        AppRequest origin = recorded("Panneau visuel des chantiers", AppRequest.EN_ATTENTE);

        AppRequestBlock block = service.propose(userId, workspace, null, null, null, "et l'avancement détaillé",
                "panneau + sous-étapes", null, List.of("capture.png"), origin.getId().toString());

        assertThat(block.amendsRequestId()).isEqualTo(origin.getId());
        assertThat(block.kind()).isEqualTo("EVOLUTION");
        assertThat(block.title()).isEqualTo("Panneau visuel des chantiers");
        assertThat(block.description()).isEqualTo("et l'avancement détaillé");
        assertThat(block.attachments()).hasSize(1);
    }

    @Test
    void aComplementByTitleFragmentIsResolvedAndAmbiguityOrAbsenceListsCandidates() {
        AppRequest panel = recorded("Panneau visuel des chantiers", AppRequest.EN_ATTENTE);
        AppRequest bubble = recorded("Bulle d'aide qui cache Envoyer", AppRequest.CADRE);
        known(panel, bubble);

        assertThat(service.propose(userId, workspace, null, null, null, "d", null, null, null, "panneau visuel")
                .amendsRequestId()).isEqualTo(panel.getId());

        AppRequest other = recorded("Panneau des attentes", AppRequest.EN_ATTENTE);
        known(panel, bubble, other);
        assertThatThrownBy(() -> service.propose(userId, workspace, null, null, null, "d", null, null, null,
                "panneau")).isInstanceOf(AppRequestInvalidException.class)
                .hasMessageContaining("plusieurs").hasMessageContaining(panel.getId().toString())
                .hasMessageContaining(other.getId().toString());

        assertThatThrownBy(() -> service.propose(userId, workspace, null, null, null, "d", null, null, null,
                "inexistant")).isInstanceOf(AppRequestInvalidException.class)
                .hasMessageContaining("aucun besoin").hasMessageContaining(bubble.getId().toString());
    }

    @Test
    void aComplementOfAComplementClimbsToTheOriginAndRejectedOrUnrecordedOriginsAreRefused() {
        AppRequest origin = recorded("Origine", AppRequest.EN_ATTENTE);
        AppRequest complement = recorded("Origine", AppRequest.JOINT);
        complement.setAmendsRequestId(origin.getId());
        assertThat(service.propose(userId, workspace, null, null, null, "d", null, null, null,
                complement.getId().toString()).amendsRequestId()).isEqualTo(origin.getId());

        AppRequest rejected = recorded("Rejeté", AppRequest.REJETE);
        assertThatThrownBy(() -> service.propose(userId, workspace, null, null, null, "d", null, null, null,
                rejected.getId().toString())).isInstanceOf(AppRequestInvalidException.class)
                .hasMessageContaining("rejeté");
        AppRequest unrecorded = recorded("Pas encore", AppRequest.PROPOSE);
        assertThatThrownBy(() -> service.propose(userId, workspace, null, null, null, "d", null, null, null,
                unrecorded.getId().toString())).isInstanceOf(AppRequestInvalidException.class)
                .hasMessageContaining("Enregistrer");
    }

    @Test
    void anotherAccountsRequestCannotBeCompleted() {
        AppRequest theirs = proposed();
        theirs.setStatus(AppRequest.EN_ATTENTE);
        // Le dépôt n'est consulté que sous user_id : rien n'est trouvé pour ce compte.
        when(requests.findByIdAndUserId(theirs.getId(), userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.propose(userId, workspace, null, null, null, "d", null, null, null,
                theirs.getId().toString())).isInstanceOf(AppRequestInvalidException.class)
                .hasMessageContaining("aucun besoin");
    }

    @Test
    void joiningAComplementTouchesTheOriginAndReopensAFramedOne() {
        AppRequest origin = recorded("Origine", AppRequest.CADRE);
        origin.setFeatureRef("F-210");
        OffsetDateTime before = origin.getUpdatedAt();
        AppRequest complement = proposed();
        complement.setAmendsRequestId(origin.getId());
        when(requests.findByIdAndUserIdAndWorkspaceId(complement.getId(), userId, workspaceId))
                .thenReturn(Optional.of(complement));

        AppRequestView view = service.record(userId, workspaceId, complement.getId());

        assertThat(view.status()).isEqualTo(AppRequest.JOINT);
        assertThat(view.amendsRequestId()).isEqualTo(origin.getId());
        assertThat(origin.getStatus()).isEqualTo(AppRequest.EN_ATTENTE);
        assertThat(origin.getFeatureRef()).isEqualTo("F-210");
        assertThat(origin.getUpdatedAt()).isAfterOrEqualTo(before);
        verify(requests).save(origin);
    }

    @Test
    void dismissingAComplementLeavesTheOriginAlone() {
        AppRequest origin = recorded("Origine", AppRequest.EN_ATTENTE);
        AppRequest complement = proposed();
        complement.setAmendsRequestId(origin.getId());
        when(requests.findByIdAndUserIdAndWorkspaceId(complement.getId(), userId, workspaceId))
                .thenReturn(Optional.of(complement));

        assertThat(service.dismiss(userId, workspaceId, complement.getId()).status()).isEqualTo(AppRequest.REJETE);
        verify(requests, never()).save(origin);
    }

    @Test
    void theDetailCarriesJoinedComplementsAndTheAdministratorCannotPatchAComplement() {
        AppRequest origin = recorded("Origine", AppRequest.EN_ATTENTE);
        AppRequest complement = recorded("Origine", AppRequest.JOINT);
        complement.setAmendsRequestId(origin.getId());
        complement.setDescription("et aussi ceci");
        when(requests.findByUserIdAndAmendsRequestIdInAndStatusOrderByCreatedAtAsc(userId, List.of(origin.getId()),
                AppRequest.JOINT)).thenReturn(List.of(complement));

        AppRequestView view = service.detail(userId, origin.getId());

        assertThat(view.complements()).extracting(AppRequestView.ComplementView::description)
                .containsExactly("et aussi ceci");
        assertThatThrownBy(() -> service.update(userId, complement.getId(), "CADRE", null))
                .isInstanceOf(AppRequestConflictException.class).hasMessageContaining("complément");
    }
}
