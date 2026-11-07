package fr.claudegateway.jarvis.mail;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Un courrier en cache</b> (F-242 / SF-242-02, D5) — en-têtes et extrait (≤ 2 000 caractères) <b>scellés</b> dans
 * {@link #sealed} ; le corps complet n'est jamais stocké (relu par IMAP à la demande). Purge à 30 jours.
 */
@Entity
@Table(name = "jarvis_mail_items")
@Getter
@Setter
@NoArgsConstructor
public class JarvisMailItem {

    public static final String SOURCE_IMAP = "IMAP";
    public static final String SOURCE_DEPOT = "DEPOT";

    /** Le tri (SF-242-03). */
    public enum Triage {
        A_TRAITER, A_LIRE, A_IGNORER, EN_ATTENTE
    }

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** La boîte IMAP ; {@code null} pour un courrier déposé (F-249, {@link #SOURCE_DEPOT}). */
    @Column(name = "account_id", updatable = false)
    private UUID accountId;

    /** F-249 / SF-249-01 — la source : {@link #SOURCE_IMAP} (relève F-242) ou {@link #SOURCE_DEPOT} (dépôt Outlook). */
    @Column(name = "source", nullable = false, length = 8)
    private String source = SOURCE_IMAP;

    /** F-249 — le poste (le client) d'un courrier déposé ; {@code null} pour un courrier IMAP. */
    @Column(name = "host_id", updatable = false)
    private UUID hostId;

    /**
     * F-249 — le corps texte borné (8 000 caractères) <b>scellé</b> d'un courrier déposé : aucun IMAP ne permet de le
     * relire. Toujours {@code null} pour un courrier IMAP (F-242 D5 inchangé).
     */
    @Column(name = "body_sealed", length = 24000)
    private String bodySealed;

    @Column(name = "uid", nullable = false, updatable = false)
    private long uid;

    @Column(name = "uidvalidity", nullable = false, updatable = false)
    private long uidvalidity;

    @Column(name = "message_id", length = 500)
    private String messageId;

    /** {@code In-Reply-To}, sinon la première référence : le fil auquel ce courrier répond. */
    @Column(name = "thread_ref", length = 500)
    private String threadRef;

    /** JSON de {@link MailHeaders} scellé ({@code dataKey.iv.ciphertext}). */
    @Column(name = "sealed", nullable = false, length = 16000)
    private String sealed;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "has_calendar", nullable = false)
    private boolean hasCalendar;

    /** Indice de règle (D6) : {@code List-Unsubscribe}, {@code Precedence: bulk}, expéditeur {@code noreply}. */
    @Column(name = "bulk", nullable = false)
    private boolean bulk;

    @Column(name = "flags", length = 200)
    private String flags;

    @Enumerated(EnumType.STRING)
    @Column(name = "triage", nullable = false, length = 16)
    private Triage triage = Triage.EN_ATTENTE;

    @Column(name = "triage_reason", length = 120)
    private String triageReason;

    @Column(name = "triaged_at")
    private OffsetDateTime triagedAt;

    @Column(name = "briefed_at")
    private OffsetDateTime briefedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** F-249 — un courrier déposé depuis l'Outlook d'un client (aucune boîte IMAP derrière). */
    public boolean isDeposited() {
        return SOURCE_DEPOT.equals(source);
    }
}
