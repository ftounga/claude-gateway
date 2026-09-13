package fr.claudegateway.mail;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>L'état de remise d'un courriel du client</b> (F-110 / SF-110-02), relu par le bloc « Courriel envoyé » du
 * terminal tant que l'envoi n'est pas terminé. JWT ; garde runner (Forge ou Vigie) ; filtre {@code user_id} —
 * le courriel d'autrui est indiscernable d'un courriel inconnu (404). Jamais le corps.
 */
@RestController
@RequestMapping("/client-emails")
public class ClientEmailController {

    private final ClientEmailRepository repository;
    private final AtelierAccessService access;
    private final CurrentUser currentUser;

    public ClientEmailController(ClientEmailRepository repository, AtelierAccessService access,
            CurrentUser currentUser) {
        this.repository = repository;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping("/{emailId}")
    public ClientEmailView read(@PathVariable UUID emailId) {
        access.requireRunnerAccess();
        return repository.findByIdAndUserId(emailId, currentUser.requireId())
                .map(ClientEmailView::of)
                .orElseThrow(() -> new MailAddressException(HttpStatus.NOT_FOUND, "not_found", "Courriel introuvable."));
    }

    /** L'état d'un courriel, sans son corps. */
    public record ClientEmailView(String id, String recipient, boolean recipientVerified, String clientName,
            String subject, int attachmentCount, int sizeBytes, String status, String failureReason,
            OffsetDateTime createdAt, OffsetDateTime sentAt) {

        static ClientEmailView of(ClientEmail email) {
            return new ClientEmailView(email.getId().toString(), email.getRecipient(), email.isRecipientVerified(),
                    email.getClientName(), email.getSubject(), email.getAttachmentCount(), email.getSizeBytes(),
                    email.getStatus().name(), email.getFailureReason(), email.getCreatedAt(), email.getSentAt());
        }
    }
}
