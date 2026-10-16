package fr.claudegateway.atelier.actions;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Un accès obtenu</b> (F-180 / SF-180-04, décision D4) : le système, le compte, quand, par qui,
 * jusqu'à quand. <b>Jamais un secret</b> : {@link AccesSecretGuard} refuse tout mot de passe ou jeton avant
 * écriture.
 *
 * <p><b>Isolation</b> : {@code user_id} sur toute lecture, puis le poste ({@code host_id}) — ou le terminal
 * ({@code workspace_id}) quand il est hébergé.</p>
 */
@Entity
@Table(name = "acces_obtenus")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccesObtenu {

    public static final int MAX_FIELD = 120;
    public static final int MAX_NOTE = 300;

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", updatable = false)
    private UUID hostId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "system", nullable = false, length = MAX_FIELD)
    private String system;

    @Column(name = "normalized_system", nullable = false, length = MAX_FIELD)
    private String normalizedSystem;

    @Column(name = "account", length = MAX_FIELD)
    private String account;

    @Column(name = "obtained_at", nullable = false)
    private LocalDate obtainedAt;

    @Column(name = "granted_by", length = MAX_FIELD)
    private String grantedBy;

    @Column(name = "interlocuteur_id")
    private UUID interlocuteurId;

    @Column(name = "expires_at")
    private LocalDate expiresAt;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "action_id")
    private UUID actionId;

    @Column(name = "note", length = MAX_NOTE)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Vrai si l'accès est expiré à cette date. */
    public boolean isExpiredOn(LocalDate day) {
        return expiresAt != null && day != null && expiresAt.isBefore(day);
    }
}
