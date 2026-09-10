package fr.claudegateway.access;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
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

import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.SubscriptionStatus;

/**
 * Un <b>code d'accès à durée limitée</b> (F-62). Émis par un ADMIN, il ouvre à qui le saisit le
 * <b>droit</b> d'accès à la Forge pendant {@link #durationHours} heures — et rien d'autre.
 *
 * <p><b>Ce qu'il n'est pas.</b> Ce n'est pas un plan, pas un paiement, pas une remise. Rien de ce
 * qui est ici n'est écrit dans {@code subscriptions} ni chez le fournisseur de paiement : la colonne
 * {@code plan_code} appartient au webhook Stripe, et un code qui y écrirait finirait par changer ce
 * qu'un client paie. Le plan de l'utilisateur n'est donc jamais quitté — d'où le fait qu'il n'y ait
 * <b>rien à restaurer</b> au terme.</p>
 *
 * <p><b>Le terme.</b> {@link #grantedUntil} est le seul mécanisme d'expiration : passé cet instant,
 * la comparaison qui ouvre le droit répond non. Il n'existe volontairement aucun job planifié —
 * l'exigence est que le retour survienne « même si personne ne se connecte », et une comparaison
 * tient cette promesse là où un cron peut simplement ne pas tourner.</p>
 *
 * <p><b>Le secret.</b> Seul le SHA-256 du code est stocké ({@link #codeHash}). Le code en clair est
 * montré une fois à l'émission, jamais persisté, jamais journalisé. {@link #label} est ce qui permet
 * de reconnaître un code dans la liste d'administration.</p>
 */
@Entity
@Table(name = "access_codes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccessCode {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** SHA-256 hexadécimal du code. Unique : deux codes ne peuvent pas se confondre. */
    @Column(name = "code_hash", nullable = false, updatable = false, length = 64, unique = true)
    private String codeHash;

    /** Libellé donné par l'admin (« démo prospect Dupont ») : la seule façon de reconnaître le code. */
    @Column(name = "label", nullable = false, length = 120)
    private String label;

    /**
     * Code <b>nominatif</b> : si renseigné (en minuscules), seul le compte portant cet e-mail peut le
     * consommer. {@code null} ⇒ le code est au porteur.
     */
    @Column(name = "assigned_email", length = 255)
    private String assignedEmail;

    /**
     * Plan dont le code ouvre le <b>droit</b> ({@code GOLD}). Figé à l'émission : changer le produit
     * demain ne doit pas réécrire l'histoire des codes déjà remis.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "granted_plan_code", nullable = false, updatable = false, length = 32)
    private PlanCode grantedPlanCode;

    /** Durée du droit, en heures, figée à l'émission (24 par défaut). */
    @Column(name = "duration_hours", nullable = false, updatable = false)
    private int durationHours;

    /** Au-delà, un code <b>non consommé</b> ne vaut plus rien. C'est le « daté » de la spec. */
    @Column(name = "valid_until", nullable = false, updatable = false)
    private OffsetDateTime validUntil;

    /** ADMIN émetteur (contexte de sécurité, jamais un paramètre client). */
    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private UUID createdByUserId;

    // ------------------------------------------------ Trace de consommation (qui, quand, pour qui)

    /** Compte qui a consommé le code. Clé d'isolation de toute lecture de droit. */
    @Column(name = "redeemed_by_user_id")
    private UUID redeemedByUserId;

    /** Instant de la consommation ; {@code null} tant que le code n'a pas servi. */
    @Column(name = "redeemed_at")
    private OffsetDateTime redeemedAt;

    /** Terme du droit ({@code redeemedAt + durationHours}). Le seul mécanisme d'expiration. */
    @Column(name = "granted_until")
    private OffsetDateTime grantedUntil;

    /**
     * Plan que portait le compte au moment de la consommation. <b>Trace</b>, pas mécanisme : on n'y
     * « revient » pas, on ne l'a jamais quitté. Il est copié ici pour que l'admin puisse lire, sans
     * recouper deux tables, « ce compte reviendra à SOLO ».
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_plan_code", length = 32)
    private PlanCode previousPlanCode;

    /** Statut d'abonnement au moment de la consommation. Même rôle de trace. */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 16)
    private SubscriptionStatus previousStatus;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
