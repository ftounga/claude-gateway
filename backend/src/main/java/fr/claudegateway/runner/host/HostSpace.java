package fr.claudegateway.runner.host;

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

/**
 * Un poste <b>activé dans un espace</b> (F-106 / SF-106-01) : une ligne par couple (poste, espace).
 *
 * <p>Aucune donnée du poste n'est recopiée ici : le poste reste {@link RunnerHost}, et cette ligne
 * ne dit que « il est visible dans cet espace depuis telle date ».</p>
 */
@Entity
@Table(name = "host_spaces")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HostSpace {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire du poste (= {@code users.id}). Filtre d'isolation obligatoire. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "host_id", nullable = false, updatable = false)
    private UUID hostId;

    @Enumerated(EnumType.STRING)
    @Column(name = "space", nullable = false, updatable = false, length = 16)
    private ClientSpace space;

    @Column(name = "activated_at", nullable = false, updatable = false)
    private OffsetDateTime activatedAt;
}
