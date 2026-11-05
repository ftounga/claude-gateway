package fr.claudegateway.jarvis;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * <b>Une journée de Jarvis pour un compte</b> (F-231 / SF-231-01, mesure SF-231-05) : ce que sa voix a lu et coûté,
 * et l'usage du mode (sessions, secondes parlées, réveils). Aucun contenu : ni texte ni audio.
 */
@Entity
@Table(name = "jarvis_usage_days")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JarvisUsageDay {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Propriétaire : toute lecture et toute écriture filtrent dessus. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "usage_day", nullable = false, updatable = false)
    private LocalDate day;

    @Builder.Default
    @Column(name = "chars_spoken", nullable = false)
    private long charsSpoken = 0L;

    @Builder.Default
    @Column(name = "speech_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal speechCostUsd = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "sessions", nullable = false)
    private int sessions = 0;

    @Builder.Default
    @Column(name = "spoken_seconds", nullable = false)
    private long spokenSeconds = 0L;

    @Builder.Default
    @Column(name = "wakes", nullable = false)
    private int wakes = 0;

    /** F-233 / SF-233-11 — retours au repos par fin de conversation mains libres (silence prolongé). */
    @Builder.Default
    @Column(name = "conversation_idle_ends", nullable = false)
    private int conversationIdleEnds = 0;

    /** F-233 / SF-233-04 — secondes de transcription en flux (écoute), comptées à la durée. */
    @Builder.Default
    @Column(name = "stt_stream_seconds", nullable = false)
    private long sttStreamSeconds = 0L;

    @Builder.Default
    @Column(name = "stt_stream_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal sttStreamCostUsd = BigDecimal.ZERO;

    /** F-235 / SF-235-01 (D15) — appels au cerveau rapide ce jour-là (pré-chauffages compris). */
    @Builder.Default
    @Column(name = "fast_brain_calls", nullable = false)
    private int fastBrainCalls = 0;

    /** F-235 / SF-235-01 (D15) — leur coût en dollars. Hors plafond de la voix : le cerveau rapide a le sien. */
    @Builder.Default
    @Column(name = "fast_brain_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal fastBrainCostUsd = BigDecimal.ZERO;

    /** F-239 / SF-239-12 — les tours Opus nés de Jarvis ce jour-là. */
    @Builder.Default
    @Column(name = "opus_turns", nullable = false)
    private int opusTurns = 0;

    /** F-239 / SF-239-12 — leur coût en dollars (converti en euros à la lecture). */
    @Builder.Default
    @Column(name = "opus_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal opusCostUsd = BigDecimal.ZERO;

    /** F-234 / SF-234-11 (D16) — scènes montrées ce jour-là, dont génériques. */
    @Builder.Default
    @Column(name = "scenes_shown", nullable = false)
    private int scenesShown = 0;

    @Builder.Default
    @Column(name = "scenes_generic", nullable = false)
    private int scenesGeneric = 0;

    /** F-234 / SF-234-11 — le détail par scène, « S3=12;S5=4;GENERIQUE=2 » (identifiants de scène seulement). */
    @Builder.Default
    @Column(name = "scene_counts", nullable = false, length = 1000)
    private String sceneCounts = "";

    /** F-234 / SF-234-11 — nœuds allumés, touchers de nœud, mode sobre activé, demandes « Montrer ». */
    @Builder.Default
    @Column(name = "nodes_lit", nullable = false)
    private int nodesLit = 0;

    @Builder.Default
    @Column(name = "node_touches", nullable = false)
    private int nodeTouches = 0;

    @Builder.Default
    @Column(name = "sober_on", nullable = false)
    private int soberOn = 0;

    @Builder.Default
    @Column(name = "show_requests", nullable = false)
    private int showRequests = 0;

    /** F-236 / SF-236-06 — sessions dessinées en 3D / restées en 2D, et le détail du rendu (vocabulaire fermé). */
    @Builder.Default
    @Column(name = "render_3d", nullable = false)
    private int render3d = 0;

    @Builder.Default
    @Column(name = "render_2d", nullable = false)
    private int render2d = 0;

    @Builder.Default
    @Column(name = "render_counts", nullable = false, length = 2000)
    private String renderCounts = "";

    /** F-244 / SF-244-01 (D3) — la retranscription d'énoncés, séparée de l'écoute en flux. */
    @Builder.Default
    @Column(name = "stt_utterance_seconds", nullable = false)
    private long sttUtteranceSeconds = 0L;

    @Builder.Default
    @Column(name = "stt_utterance_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal sttUtteranceCostUsd = BigDecimal.ZERO;

    /** F-244 / SF-244-01 (D3) — la dictée de repli appelée depuis {@code /jarvis}. */
    @Builder.Default
    @Column(name = "dictation_seconds", nullable = false)
    private long dictationSeconds = 0L;

    @Builder.Default
    @Column(name = "dictation_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal dictationCostUsd = BigDecimal.ZERO;

    /** F-244 / SF-244-01 — le courrier (F-242) et l'agenda (F-243), prévus dès maintenant. */
    @Builder.Default
    @Column(name = "mail_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal mailCostUsd = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "agenda_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal agendaCostUsd = BigDecimal.ZERO;

    /** F-247 / SF-247-12 et SF-247-14 (D16) — la lecture des captures et les constats rédigés, au budget Jarvis. */
    @Builder.Default
    @Column(name = "capture_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal captureCostUsd = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "notes_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal notesCostUsd = BigDecimal.ZERO;

    /** F-247 / SF-247-17 — les constats sur l'historique (« Synchroniser ») : au budget Jarvis, hors plafond mensuel
     * des constats (enveloppe ponctuelle par passage). */
    @Builder.Default
    @Column(name = "notes_catchup_cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal notesCatchupCostUsd = BigDecimal.ZERO;

    /** F-247 / SF-247-13 — les lectures de l'outil {@code conversations} (« qu'a dit X »). Compteur seul. */
    @Builder.Default
    @Column(name = "conversation_reads", nullable = false)
    private int conversationReads = 0;

    /** F-242 / SF-242-02 — les relèves du courrier, les relèves sans rien de neuf (0 FETCH, 0 IA), les courriers
     * mis en cache ; SF-242-03 — les corrections du tri au toucher. Compteurs seuls, aucun contenu. */
    @Builder.Default
    @Column(name = "mail_polls", nullable = false)
    private int mailPolls = 0;

    @Builder.Default
    @Column(name = "mail_polls_empty", nullable = false)
    private int mailPollsEmpty = 0;

    @Builder.Default
    @Column(name = "mail_fetched", nullable = false)
    private int mailFetched = 0;

    @Builder.Default
    @Column(name = "mail_triage_corrected", nullable = false)
    private int mailTriageCorrected = 0;

    /** F-243 / SF-243-01 — les relèves d'agenda, et celles sans changement (aucune analyse). Compteurs seuls. */
    @Builder.Default
    @Column(name = "calendar_polls", nullable = false)
    private int calendarPolls = 0;

    @Builder.Default
    @Column(name = "calendar_polls_unchanged", nullable = false)
    private int calendarPollsUnchanged = 0;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Ce que la <b>voix</b> de Jarvis a coûté ce jour-là, en dollars : lecture, écoute en flux, retranscription et dictée
     * de repli (F-233 / SF-233-04, complété par F-244 / SF-244-01). C'est l'assiette du plafond voix.
     */
    public BigDecimal voiceCostUsd() {
        return nz(speechCostUsd).add(nz(sttStreamCostUsd)).add(nz(sttUtteranceCostUsd)).add(nz(dictationCostUsd));
    }

    /** F-244 / SF-244-01 — <b>tout</b> ce que Jarvis a coûté ce jour-là, en dollars : voix, cerveau rapide, Opus, courrier, agenda,
     * captures et constats du registre d'état (F-247). */
    public BigDecimal totalCostUsd() {
        return voiceCostUsd().add(nz(fastBrainCostUsd)).add(nz(opusCostUsd)).add(nz(mailCostUsd))
                .add(nz(agendaCostUsd)).add(nz(captureCostUsd)).add(nz(notesCostUsd));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
