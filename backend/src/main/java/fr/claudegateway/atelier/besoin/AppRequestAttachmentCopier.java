package fr.claudegateway.atelier.besoin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.ProjectFileRead;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * <b>Copie les pièces d'un besoin dans le stockage de la gateway</b> (F-205 / SF-205-01) : sur un poste,
 * les octets d'une capture ou d'une vidéo restent sinon sur la machine, hors de portée de qui cadrera le
 * besoin. Clé : {@code besoins/{id}/{n}-{nom}}.
 *
 * <p><b>En arrière-plan</b> ({@code appRequestCopyExecutor}) : une vidéo de 100 Mo lue par tranches ne
 * bloque pas le clic [Enregistrer]. Un échec (poste hors ligne, fichier disparu, trop lourd) marque la
 * pièce {@code FAILED} avec son motif : le besoin, lui, reste enregistré.</p>
 *
 * <p><b>Isolation</b> : le terminal est relu par {@code requireOwned(userId, workspaceId)}, les pièces par
 * {@code request_id + user_id}. Les octets ne sont jamais journalisés.</p>
 */
@Component
public class AppRequestAttachmentCopier {

    private static final Logger log = LoggerFactory.getLogger(AppRequestAttachmentCopier.class);

    /** Préfixe des pièces des besoins dans le stockage. */
    public static final String PREFIX = "besoins/";
    /** Nom d'outil du journal runner pour la lecture de copie. */
    static final String AUDIT_TOOL = "signaler_besoin_copie";

    private final AppRequestRepository requests;
    private final AppRequestAttachmentRepository attachments;
    private final WorkspaceService workspaceService;
    private final ProjectFileRead fileRead;
    private final WorkspaceStorage storage;
    private final Executor executor;
    private final long maxBytes;

    public AppRequestAttachmentCopier(AppRequestRepository requests, AppRequestAttachmentRepository attachments,
            WorkspaceService workspaceService, ProjectFileRead fileRead, WorkspaceStorage storage,
            @Qualifier("appRequestCopyExecutor") Executor executor,
            @Value("${app.atelier.deposit.max-runner-bytes:104857600}") long maxBytes) {
        this.requests = requests;
        this.attachments = attachments;
        this.workspaceService = workspaceService;
        this.fileRead = fileRead;
        this.storage = storage;
        this.executor = executor;
        this.maxBytes = maxBytes;
    }

    /** Confie la copie au pool ; file pleine ⇒ les pièces en attente passent {@code FAILED}. */
    public void copyAsync(UUID userId, UUID requestId) {
        try {
            executor.execute(() -> copyAll(userId, requestId));
        } catch (RejectedExecutionException ex) {
            markPendingFailed(userId, requestId, "Copie non lancée : file de copie pleine.");
        }
    }

    /** Copie chaque pièce {@code PENDING} du besoin. */
    public void copyAll(UUID userId, UUID requestId) {
        Optional<AppRequest> request = requests.findByIdAndUserId(requestId, userId);
        if (request.isEmpty()) {
            return;
        }
        Workspace workspace;
        try {
            workspace = workspaceService.requireOwned(userId, request.get().getWorkspaceId());
        } catch (RuntimeException ex) {
            markPendingFailed(userId, requestId, "Terminal d'origine introuvable.");
            return;
        }
        List<AppRequestAttachment> pieces = attachments.findByRequestIdAndUserIdOrderBySeqAsc(requestId, userId);
        for (AppRequestAttachment piece : pieces) {
            if (!AppRequestAttachment.PENDING.equals(piece.getCopyStatus())) {
                continue;
            }
            copyOne(userId, workspace, requestId, piece);
        }
    }

    private void copyOne(UUID userId, Workspace workspace, UUID requestId, AppRequestAttachment piece) {
        try {
            ProjectFileRead.Read read = fileRead.read(userId, workspace, "besoin-" + piece.getId(),
                    piece.getSourcePath(), maxBytes, AUDIT_TOOL);
            if (read.failed()) {
                fail(piece, read.error());
                return;
            }
            String key = keyOf(requestId, piece);
            storage.putFile(key, read.content(), piece.getContentType());
            piece.setStorageKey(key);
            piece.setCopyStatus(AppRequestAttachment.COPIED);
            piece.setCopyError(null);
            attachments.save(piece);
        } catch (RuntimeException ex) {
            log.info("Pièce de besoin non copiée ({})", ex.getClass().getSimpleName());
            fail(piece, "Copie impossible : " + ex.getClass().getSimpleName());
        }
    }

    /** Les octets d'une pièce copiée. */
    public Optional<byte[]> read(String storageKey) {
        return storage.getFile(storageKey);
    }

    /** Efface les pièces copiées des besoins donnés (suppression du compte), au mieux. */
    public void deleteCopies(List<UUID> requestIds) {
        for (UUID id : requestIds) {
            try {
                storage.deletePrefix(PREFIX + id + "/");
            } catch (RuntimeException ex) {
                log.warn("Pièces du besoin {} non effacées ({})", id, ex.getClass().getSimpleName());
            }
        }
    }

    static String keyOf(UUID requestId, AppRequestAttachment piece) {
        String safe = piece.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 120) {
            safe = safe.substring(safe.length() - 120);
        }
        return PREFIX + requestId + "/" + piece.getSeq() + "-" + safe;
    }

    private void fail(AppRequestAttachment piece, String reason) {
        piece.setCopyStatus(AppRequestAttachment.FAILED);
        String text = reason == null ? "Copie impossible." : reason;
        piece.setCopyError(text.length() > 500 ? text.substring(0, 500) : text);
        attachments.save(piece);
    }

    private void markPendingFailed(UUID userId, UUID requestId, String reason) {
        for (AppRequestAttachment piece : attachments.findByRequestIdAndUserIdOrderBySeqAsc(requestId, userId)) {
            if (AppRequestAttachment.PENDING.equals(piece.getCopyStatus())) {
                fail(piece, reason);
            }
        }
    }
}
