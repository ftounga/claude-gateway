package fr.claudegateway.atelier.actions;

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
 * <b>Une action à faire</b>, née dans un terminal de projet (F-151 / SF-151-01).
 *
 * <p>Ce que l'agent ne peut pas faire à la place de l'utilisateur : contacter quelqu'un, demander un
 * accès, obtenir une validation. Écrite par l'agent quand il bute sur une dépendance humaine, elle
 * <b>survit au tour</b> qui l'a produite — c'est tout son intérêt.</p>
 *
 * <p><b>Isolation.</b> Toute lecture et toute écriture filtrent {@code (user_id, workspace_id)}.
 * Le projet d'un autre compte est <b>introuvable</b> — 404, jamais 403.</p>
 *
 * <p><b>Pourquoi pas un engagement du Radar</b> (F-99), qui dit déjà tout cela : son
 * {@code subject_id} est NOT NULL, et un terminal de projet n'a pas toujours de sujet. Le rendre
 * nullable toucherait le cœur du Radar pour un besoin qui n'est pas le sien. Une table à soi, et
 * <b>une seule liste à l'écran</b> : le menu agrège les deux sources.</p>
 */
@Entity
@Table(name = "terminal_actions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TerminalAction {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Le projet où l'action est née — clé du filtre avec {@code user_id}. */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** Le sujet du Radar quand le terminal en porte un, pour recoller les deux vues. */
    @Column(name = "subject_id")
    private UUID subjectId;

    /** Ce qu'il faut faire, à l'impératif, du point de vue de l'utilisateur. */
    @Column(name = "description", nullable = false, length = 300)
    private String description;

    /** Ce que ça débloque : la raison d'être de l'action. */
    @Column(name = "blocks", length = 200)
    private String blocks;

    /** Qui est concerné — texte libre, ce que l'agent a lu dans le fil. Pas une entité. */
    @Column(name = "person", length = 120)
    private String person;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private TerminalActionKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TerminalActionStatus status;

    /**
     * La phrase qui a fermé l'action. Sans elle, on ne saurait plus <b>pourquoi</b> elle a disparu
     * de la liste — et une action qui disparaît sans raison est une action qu'on refait.
     */
    @Column(name = "closed_reason", length = 300)
    private String closedReason;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Vrai tant que l'action attend encore quelque chose de l'utilisateur. */
    public boolean isOpen() {
        return status == TerminalActionStatus.OPEN;
    }
}
