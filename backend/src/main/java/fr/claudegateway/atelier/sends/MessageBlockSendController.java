package fr.claudegateway.atelier.sends;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * « Marquer envoyé » sur un encart mail/message (F-211 / SF-211-01).
 *
 * <p>L'identité vient <b>exclusivement</b> du {@link CurrentUser} ; le droit au terminal suit la même
 * garde que l'historique ; l'isolation est appliquée par le service ({@code requireOwned} : 404 sur
 * un terminal d'autrui).</p>
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/messages")
public class MessageBlockSendController {

    private final MessageBlockSendService service;
    private final AtelierAccessService access;
    private final CurrentUser currentUser;

    public MessageBlockSendController(MessageBlockSendService service, AtelierAccessService access,
                                      CurrentUser currentUser) {
        this.service = service;
        this.access = access;
        this.currentUser = currentUser;
    }

    /** Les envois actifs du terminal, relus au rechargement. */
    @GetMapping("/sends")
    public List<SendResponse> list(@PathVariable UUID workspaceId) {
        access.requireTerminalAccess(workspaceId);
        return service.list(currentUser.requireId(), workspaceId).stream().map(SendResponse::from).toList();
    }

    /** [Marquer envoyé] */
    @PostMapping("/{messageId}/sends/{index}")
    public SendResponse mark(@PathVariable UUID workspaceId, @PathVariable UUID messageId,
                             @PathVariable int index) {
        access.requireTerminalAccess(workspaceId);
        return SendResponse.from(service.mark(currentUser.requireId(), workspaceId, messageId, index));
    }

    /** [Annuler] */
    @DeleteMapping("/{messageId}/sends/{index}")
    public ResponseEntity<Void> cancel(@PathVariable UUID workspaceId, @PathVariable UUID messageId,
                                       @PathVariable int index) {
        access.requireTerminalAccess(workspaceId);
        service.cancel(currentUser.requireId(), workspaceId, messageId, index);
        return ResponseEntity.noContent().build();
    }

    /** Un envoi tel que l'écran le montre. {@code attenteKey} n'est rendu que si l'attente a bougé. */
    public record SendResponse(UUID messageId, int blockIndex, String kind, String subject, String recipient,
                               OffsetDateTime sentAt, String attenteKey) {
        static SendResponse from(MessageBlockSend send) {
            return new SendResponse(send.getMessageId(), send.getBlockIndex(), send.getKind().name(),
                    send.getSubject(), send.getRecipient(), send.getSentAt(),
                    send.getAttenteActionId() == null ? null : send.getAttenteKey());
        }
    }
}
