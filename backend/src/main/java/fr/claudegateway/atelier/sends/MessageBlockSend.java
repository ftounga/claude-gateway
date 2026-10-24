package fr.claudegateway.atelier.sends;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Un encart mail/message marqué envoyé</b> par l'utilisateur (F-211 / SF-211-01).
 *
 * <p>Repère stable : {@code (message_id, block_index)}, le rang du bloc mail/message dans la réponse
 * d'agent persistée. L'objet, le destinataire et la clé d'attente sont lus par le serveur dans le
 * message, jamais fournis par le client. Si le bloc sert une attente, son état d'avant est gardé ici
 * pour « Annuler ».</p>
 *
 * <p><b>Isolation.</b> Toute lecture filtre {@code (user_id, workspace_id)}.</p>
 */
@Entity
@Table(name = "message_block_sends")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageBlockSend {

    /** Nature du bloc envoyé. */
    public enum Kind { MAIL, MESSAGE }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    @Column(name = "block_index", nullable = false, updatable = false)
    private int blockIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Column(name = "subject", length = 300)
    private String subject;

    @Column(name = "recipient", length = 120)
    private String recipient;

    /** Le début du corps, pour nommer un message sans objet dans la note de tour. */
    @Column(name = "excerpt", length = 120)
    private String excerpt;

    @Column(name = "attente_key", length = 200)
    private String attenteKey;

    @Column(name = "attente_action_id")
    private UUID attenteActionId;

    @Column(name = "attente_prev_status", length = 16)
    private String attentePrevStatus;

    @Column(name = "attente_prev_requested_at")
    private OffsetDateTime attentePrevRequestedAt;

    @Column(name = "attente_prev_requested_to", length = 120)
    private String attentePrevRequestedTo;

    @Column(name = "attente_prev_channel", length = 60)
    private String attentePrevChannel;

    @Column(name = "attente_marked_at")
    private OffsetDateTime attenteMarkedAt;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    /** Vrai tant que l'envoi n'a pas été annulé. */
    public boolean isActive() {
        return cancelledAt == null;
    }
}
