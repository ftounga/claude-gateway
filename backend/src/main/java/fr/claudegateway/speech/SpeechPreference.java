package fr.claudegateway.speech;

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

/** La voix par défaut d'un compte (F-215 / SF-215-01). Clé : {@code user_id}. */
@Entity
@Table(name = "speech_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpeechPreference {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "voice", nullable = false, length = 32)
    private String voice;

    @Column(name = "instructions", length = 1000)
    private String instructions;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
