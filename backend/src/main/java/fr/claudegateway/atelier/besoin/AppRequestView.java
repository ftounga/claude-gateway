package fr.claudegateway.atelier.besoin;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Ce que l'écran (carte du terminal, onglet Besoins) et Claude Code (route admin) relisent d'un besoin
 * (F-205 / SF-205-01). Jamais la clé de stockage d'une pièce : on la lit par son rang.
 */
public record AppRequestView(UUID id, UUID workspaceId, UUID messageId, String kind, String title,
        String description, String reformulation, String observedContext, String status, String featureRef,
        OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime recordedAt,
        List<AttachmentView> attachments, UUID amendsRequestId, List<ComplementView> complements) {

    /**
     * Un complément joint au besoin (F-205 / SF-205-04) : les mots du PO pour ce complément, la
     * reformulation à jour de l'agent, et ses pièces — lues par l'id du complément
     * ({@code /admin/app-requests/{id}/attachments/{position}}).
     */
    public record ComplementView(UUID id, UUID workspaceId, String description, String reformulation,
            String observedContext, OffsetDateTime createdAt, OffsetDateTime recordedAt,
            List<AttachmentView> attachments) {

        static ComplementView of(AppRequest c, List<AppRequestAttachment> attachments) {
            return new ComplementView(c.getId(), c.getWorkspaceId(), c.getDescription(), c.getReformulation(),
                    c.getObservedContext(), c.getCreatedAt(), c.getRecordedAt(),
                    attachments.stream().map(AttachmentView::of).toList());
        }
    }

    /**
     * Une pièce et l'état de sa copie.
     *
     * @param position   rang, à partir de 1 ({@code …/attachments/{position}})
     * @param copyStatus {@code null} (proposé) | {@code PENDING} | {@code COPIED} | {@code FAILED}
     */
    public record AttachmentView(int position, String name, String contentType, long size, String copyStatus,
            String copyError) {

        static AttachmentView of(AppRequestAttachment a) {
            return new AttachmentView(a.getSeq(), a.getName(), a.getContentType(), a.getSizeBytes(),
                    a.getCopyStatus(), a.getCopyError());
        }
    }

    static AppRequestView of(AppRequest r, List<AppRequestAttachment> attachments) {
        return of(r, attachments, List.of());
    }

    static AppRequestView of(AppRequest r, List<AppRequestAttachment> attachments,
            List<ComplementView> complements) {
        return new AppRequestView(r.getId(), r.getWorkspaceId(), r.getMessageId(), r.getKind(), r.getTitle(),
                r.getDescription(), r.getReformulation(), r.getObservedContext(), r.getStatus(), r.getFeatureRef(),
                r.getCreatedAt(), r.getUpdatedAt(), r.getRecordedAt(),
                attachments.stream().map(AttachmentView::of).toList(), r.getAmendsRequestId(),
                List.copyOf(complements));
    }
}
