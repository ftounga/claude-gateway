package fr.claudegateway.jarvis.timing;

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
 * <b>Un tour vocal chronométré</b> (F-233 / SF-233-01, D2) : marques en millisecondes depuis la fin de parole
 * détectée, fournisseurs, compteurs d'interruption. <b>Aucun contenu</b> : ni texte ni audio.
 */
@Entity
@Table(name = "jarvis_turn_timings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisTurnTiming {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire : toute lecture filtre dessus. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "listen_mode", nullable = false, length = 16)
    private String listenMode;

    @Column(name = "stt_provider", length = 32)
    private String sttProvider;

    @Column(name = "tts_provider", length = 32)
    private String ttsProvider;

    @Builder.Default
    @Column(name = "tool_turn", nullable = false)
    private boolean toolTurn = false;

    @Column(name = "speech_ms")
    private Integer speechMs;

    @Column(name = "transcript_ms")
    private Integer transcriptMs;

    @Column(name = "reaction_ms")
    private Integer reactionMs;

    @Column(name = "sent_ms")
    private Integer sentMs;

    @Column(name = "first_token_ms")
    private Integer firstTokenMs;

    @Column(name = "first_oral_sentence_ms")
    private Integer firstOralSentenceMs;

    @Column(name = "first_audio_byte_ms")
    private Integer firstAudioByteMs;

    @Column(name = "first_sound_ms")
    private Integer firstSoundMs;

    @Column(name = "done_ms")
    private Integer doneMs;

    @Builder.Default
    @Column(name = "interruptions_detected", nullable = false)
    private int interruptionsDetected = 0;

    @Builder.Default
    @Column(name = "interruptions_restored", nullable = false)
    private int interruptionsRestored = 0;

    @Builder.Default
    @Column(name = "interruptions_confirmed", nullable = false)
    private int interruptionsConfirmed = 0;

    @Builder.Default
    @Column(name = "acknowledgements", nullable = false)
    private int acknowledgements = 0;

    @Builder.Default
    @Column(name = "false_triggers", nullable = false)
    private int falseTriggers = 0;
}
