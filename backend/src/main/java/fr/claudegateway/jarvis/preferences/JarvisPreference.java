package fr.claudegateway.jarvis.preferences;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** <b>Les préférences de Jarvis d'un compte</b> (F-235 / SF-235-09, D18) — une ligne par compte, clé {@code user_id}. */
@Entity
@Table(name = "jarvis_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisPreference {

    /** Propriétaire et clé : toute lecture et toute écriture filtrent dessus. */
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** {@code NONE} ou {@code LIGHT}. */
    @Column(name = "humour", nullable = false, length = 8)
    private String humour;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
