package fr.claudegateway.jarvis.capture;

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

/**
 * <b>Un énoncé de Jarvis gardé pour le banc d'écoute</b> (F-239 / SF-239-03) — seulement pour un ADMIN qui l'a
 * demandé, 7 jours au plus. L'audio est dans le stockage objet ({@link #storageKey}) ; toute lecture filtre sur
 * {@link #userId}.
 */
@Entity
@Table(name = "jarvis_audio_captures")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisAudioCapture {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "storage_key", nullable = false, length = 300)
    private String storageKey;

    @Column(name = "seconds")
    private Integer seconds;

    @Column(name = "listen_mode", length = 16)
    private String listenMode;

    @Column(name = "provider", length = 32)
    private String provider;

    /** Le texte provisoire de l'écoute en flux. */
    @Column(name = "stream_text", length = 2000)
    private String streamText;

    /** La retranscription retenue (ce que Jarvis a compris). */
    @Column(name = "final_text", length = 2000)
    private String finalText;

    /** La correction du PO (SF-239-06), la référence du banc quand elle existe. */
    @Column(name = "corrected_text", length = 2000)
    private String correctedText;

    /** F-255 / SF-255-01 — le tour vocal de l'énoncé (identifiant tiré par l'écran), {@code null} sinon. */
    @Column(name = "turn_id", length = 36)
    private String turnId;
}
