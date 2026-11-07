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

    // ---- F-240 / SF-240-01 — ce qui est dit (longueur, coupe, invitation, étage atteint). Jamais le texte.

    /** Les mots de la réponse dite (hors annonce avant outils) ; {@code null} sans réponse dite. */
    @Column(name = "spoken_words")
    private Integer spokenWords;

    @Column(name = "spoken_chars")
    private Integer spokenChars;

    /** La durée audio de la réponse dite, hors annonce, en ms. */
    @Column(name = "spoken_ms")
    private Integer spokenMs;

    /** Les mots de l'annonce qui précède les outils (D3 : comptés à part). */
    @Column(name = "announce_words")
    private Integer announceWords;

    /** L'écran a coupé la ligne dite (D5 : plus de 35 mots ⇒ première phrase seulement). */
    @Column(name = "oral_cut")
    private Boolean oralCut;

    /** Une invitation ciblée « ↪ » a été dite (D6). */
    @Column(name = "invitation")
    private Boolean invitation;

    /** L'étage atteint : 1 dit, 2 essentiel, 3 détail. */
    @Column(name = "depth_reached")
    private Integer depthReached;

    /** Comment l'étage a été atteint : {@code voix} ou {@code toucher}. */
    @Column(name = "depth_via", length = 8)
    private String depthVia;

    /** F-241 / SF-241-03 — le portrait servait à l'échange (jamais son texte). */
    @Column(name = "portrait_on")
    private Boolean portraitOn;

    /** F-241 / SF-241-03 (D12 a) — phrases écartées par le filet « À ne pas aborder ». */
    @Column(name = "portrait_blocked")
    private Integer portraitBlocked;

    /** F-241 / SF-241-04 (D11) — {@code SPONTANEE}, {@code REPONSE} ou {@code AUCUNE} ; jamais le texte. */
    @Column(name = "personal_allusion", length = 9)
    private String personalAllusion;

    // ---- F-247 / SF-247-01 — la question d'état et sa réponse (cadrage §7). Jamais le texte.

    /** Le poste visé était déconnecté au moment de la question ; {@code null} avant F-247. */
    @Column(name = "host_offline")
    private Boolean hostOffline;

    /** La question est une question d'état (classification déterministe, {@code JarvisStateQuestion}). */
    @Column(name = "state_question")
    private Boolean stateQuestion;

    /** {@code UNKNOWN}, {@code KNOWN_STALE}, {@code KNOWN_FRESH} ou {@code NA}. */
    @Column(name = "state_answer", length = 12)
    private String stateAnswer;

    /** La lecture du bloc « État connu » tout prêt dans la réplique, en ms (SF-247-10). */
    @Column(name = "state_block_ms")
    private Integer stateBlockMs;

    // ---- F-248 / SF-248-01 — les pancartes (cadrage §7). Jamais de contenu.

    /** Délai entre la première phrase jouée et la première pancarte montrée (ms, K2/K3) ; {@code null} sans jeu. */
    @Column(name = "cards_lag_ms")
    private Integer cardsLagMs;

    /** Nombre de pancartes du jeu montré ; {@code null} sans jeu. */
    @Column(name = "cards_count")
    private Integer cardsCount;

    // ---- F-233 / SF-233-13 — l'écoute en flux. Jamais de contenu.

    /** Pourquoi le flux n'a pas servi (liste fermée {@code JarvisLatencyService.STREAM_FALLBACKS}) ; {@code null} sinon. */
    @Column(name = "stream_fallback", length = 24)
    private String streamFallback;

    /** La page tournait dans le compagnon Android ; {@code null} pour un écran d'avant SF-233-13. */
    @Column(name = "companion")
    private Boolean companion;
}
