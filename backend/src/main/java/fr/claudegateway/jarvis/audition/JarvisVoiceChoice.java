package fr.claudegateway.jarvis.audition;

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

/** <b>Un choix de voix de Jarvis</b> à l'audition (F-233 / SF-233-07) : le plus récent du compte fait foi. */
@Entity
@Table(name = "jarvis_voice_choices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisVoiceChoice {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire : toute lecture et toute écriture filtrent dessus. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "voice", nullable = false, length = 64)
    private String voice;

    @Column(name = "label", length = 8)
    private String label;

    @Column(name = "chosen_at", nullable = false)
    private OffsetDateTime chosenAt;
}
