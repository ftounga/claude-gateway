package fr.claudegateway.atelier.deposit;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
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
 * Fichier déposé dans un terminal (F-115 / SF-115-01), en attente d'un tour. C'est ce qui donne au
 * dépôt une mémoire entre le geste de l'utilisateur (glisser / coller / trombone) et le tour suivant :
 * la consigne du tour lira les dépôts non consommés d'un projet (SF-115-03) et n'y remettra que le
 * <b>chemin</b>, jamais le binaire.
 *
 * <p><b>Isolation</b> : un dépôt appartient à un couple {@code (user_id, workspace_id)} et n'est
 * jamais lu ni écrit hors de ce couple — un identifiant de workspace deviné n'ouvre rien.</p>
 *
 * <p>{@code path} est le chemin <b>relatif</b> où l'agent lira le fichier : {@code entrees/<nom>} pour
 * un workspace hébergé, {@code .atelier/entrees/<nom>} pour un poste. {@code consumed_at} vaut
 * {@code null} tant qu'aucun tour n'a porté ce chemin dans sa consigne.</p>
 */
@Entity
@Table(name = "atelier_deposited_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AtelierDepositedFile {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire du workspace (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Workspace (terminal) où le fichier a été déposé. */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** Chemin relatif du fichier déposé, tel que l'agent le lira. */
    @Column(name = "path", nullable = false, length = 1024, updatable = false)
    private String path;

    /** Octets écrits, pour l'affichage « fichier déposé : … — 2,3 Mo » (SF-115-02). */
    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Horodaté quand un tour a porté ce chemin dans sa consigne (SF-115-03) ; {@code null} sinon. */
    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;
}
