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

    // ---- F-235 / SF-235-07 (D14) : le cerveau qui a tenu le tour, et ce que ses garde-fous ont fait. Sans contenu.

    /** {@code FAST}, {@code FAST_DELEGATED} ou {@code OPUS} ; {@code null} pour les tours d'avant F-235. */
    @Column(name = "brain", length = 16)
    private String brain;

    @Column(name = "fast_model", length = 64)
    private String fastModel;

    @Column(name = "fast_first_token_ms")
    private Integer fastFirstTokenMs;

    @Builder.Default
    @Column(name = "grounding_trips", nullable = false)
    private int groundingTrips = 0;

    @Builder.Default
    @Column(name = "fillers_removed", nullable = false)
    private int fillersRemoved = 0;

    @Builder.Default
    @Column(name = "repeats_removed", nullable = false)
    private int repeatsRemoved = 0;

    @Builder.Default
    @Column(name = "imitations_removed", nullable = false)
    private int imitationsRemoved = 0;

    @Builder.Default
    @Column(name = "forced_delegation", nullable = false)
    private boolean forcedDelegation = false;

    /** La raison d'une délégation d'office (liste fermée : grounding, refusal, error, timeout, empty, tool_text…). */
    @Column(name = "delegation_reason", length = 16)
    private String delegationReason;

    /** La parole suivante (≤ 30 s) a corrigé cette réponse rapide (« non », « c'est faux », « vérifie »…). */
    @Builder.Default
    @Column(name = "correction", nullable = false)
    private boolean correction = false;

    /**
     * F-239 / SF-239-02 (D3b) — la parole a été refusée par la porte de parole réelle (aucun tour) : la raison (liste
     * fermée : empty, noise, hallucination, no_voice, implausible), {@code null} sinon. Jamais le texte.
     */
    @Column(name = "rejected_kind", length = 16)
    private String rejectedKind;

    /** F-239 / SF-239-02 (D3b) — la longueur du texte transcrit refusé, {@code null} sinon. */
    @Column(name = "rejected_chars")
    private Integer rejectedChars;
}
