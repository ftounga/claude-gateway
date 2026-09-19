package fr.claudegateway.runner.diag;

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
 * Une ligne du journal de diagnostic du runner (F-132 / SF-132-02) : un événement <b>structuré et
 * déjà expurgé</b> remonté par le runner dans la trame {@code runner_diag} (SF-132-01).
 *
 * <p>Ce que cette table contient : des <b>formes et des états</b> — état du Chrome managé, verdict de
 * la sonde Teams, cycle de vie de la capture (tailles, jamais le média), ticks de la Vigie, erreurs
 * (type + message court). Ce qu'elle ne contient <b>jamais</b> (invariant, poste client/banque) :
 * un secret, une URL brute (seulement sa classe), un chemin sensible, un contenu Teams —
 * l'expurgation est faite à la source.</p>
 *
 * <p><b>Isolation</b> : {@code user_id} et {@code host_id} viennent de la <b>session</b> runner
 * ({@code RunnerIdentity}), jamais d'un champ du message. Toute lecture filtre sur les deux.</p>
 */
@Entity
@Table(name = "runner_diag_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RunnerDiagEventEntity {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire du poste (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Poste concerné (= {@code runner_hosts.id}). Filtre d'isolation obligatoire. */
    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    /** {@code DEBUG} | {@code INFO} | {@code WARN} | {@code ERROR}. */
    @Column(name = "level", nullable = false, length = 8, updatable = false)
    private String level;

    /** Catégorie courte (ex. {@code chrome}, {@code teams}, {@code capture}, {@code vigie}, {@code error}). */
    @Column(name = "category", nullable = false, length = 32, updatable = false)
    private String category;

    /** Code court de l'événement (ex. {@code chrome_state}). */
    @Column(name = "code", nullable = false, length = 64, updatable = false)
    private String code;

    /** Message court expurgé, ou {@code null}. Jamais un secret, jamais une URL brute. */
    @Column(name = "message", length = 500, updatable = false)
    private String message;

    /** Petite carte de champs scalaires expurgés, sérialisée en JSON compact ; {@code null} si vide. */
    @Column(name = "fields", length = 2000, updatable = false)
    private String fields;

    /** Horodatage d'observation côté runner (horloge du poste), ou {@code null}. */
    @Column(name = "observed_at", updatable = false)
    private OffsetDateTime observedAt;

    /** Horodatage serveur de réception. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
