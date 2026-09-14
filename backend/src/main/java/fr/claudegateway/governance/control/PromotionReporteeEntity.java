package fr.claudegateway.governance.control;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Une <b>promotion reportée faute de poste</b> persistée (F-93 / SF-93-05).
 *
 * <p>SF-93-04 gardait ce report en mémoire du processus ; il n'y survivait ni à un redémarrage ni à
 * un autre pod. Cette ligne le rend durable, une par triple {@code (user_id, host_id, workspace_id)}.
 * Sa sémantique — cumul, durée de vie, réclamation une fois — reste celle de {@link PromotionReportee}
 * qui l'écrit et la lit ; l'entité ne porte que l'état, aucune logique.</p>
 *
 * <p>Les éléments sont stockés <b>à plat</b> dans une seule colonne (liste courte, bornée, lue en
 * bloc, jamais interrogée par élément), séparés par un saut de ligne — même choix que
 * {@code GovernancePackage.control_ids}.</p>
 */
@Entity
@Table(name = "promotion_reportee",
        uniqueConstraints = @UniqueConstraint(name = "uq_promotion_reportee_triple",
                columnNames = {"user_id", "host_id", "workspace_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionReporteeEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire du projet — filtre d'isolation, jamais renseigné autrement. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Le poste hors ligne dont la carte n'a pas pu être écrite. */
    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** Éléments durables non rangés, à plat, séparés par un saut de ligne ; jamais interrogés. */
    @Column(name = "elements", columnDefinition = "text")
    private String elements;

    /** Dette déclarée la plus haute des tours reportés (nombre de cases {@code - [ ]}). */
    @Column(name = "dette", nullable = false)
    private int dette;

    /** Date du <b>premier</b> report encore dû du triple, conservée au cumul. */
    @Column(name = "reported_at", nullable = false)
    private OffsetDateTime reportedAt;
}
