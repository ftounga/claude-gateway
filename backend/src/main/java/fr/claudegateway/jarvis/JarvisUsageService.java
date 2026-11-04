package fr.claudegateway.jarvis;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.quota.UsageLedgerService;
import fr.claudegateway.quota.UsageTurn;

/**
 * <b>Le cumul du jour de Jarvis</b> (F-231 / SF-231-01, mesure SF-231-05) : une ligne par compte et par jour, mise à
 * jour par incrément. Deux écritures simultanées du même jour se rencontrent sur la contrainte d'unicité : la
 * seconde relit la ligne créée et l'incrémente.
 *
 * <p><b>Ne fait jamais échouer l'appel</b> : la voix est déjà payée, un relevé perdu est un défaut d'information
 * (même arbitrage que {@code UsageLedgerService}).</p>
 */
@Service
public class JarvisUsageService {

    private static final Logger log = LoggerFactory.getLogger(JarvisUsageService.class);

    private final JarvisUsageDayRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JarvisUsageService(JarvisUsageDayRepository repository, PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** F-244 / SF-244-01 — le journal du coût, où chaque dépense Jarvis est aussi étiquetée. Facultatif. */
    private UsageLedgerService ledger;

    @Autowired(required = false)
    public void setLedger(UsageLedgerService ledger) {
        this.ledger = ledger;
    }

    /** Date de la grille des tarifs de la voix et de l'écoute de Jarvis (réglages de déploiement). */
    static final String PRICING_VERSION = "jarvis-voice";

    /** Une lecture de la voix : caractères lus et coût en dollars. */
    public void addSpeech(UUID userId, long characters, BigDecimal costUsd) {
        addSpeech(userId, null, null, null, characters, costUsd);
    }

    /**
     * F-244 / SF-244-01 (D2) — une lecture de la voix, au <b>tarif réel</b> de son fournisseur (OpenAI ou ElevenLabs),
     * inscrite à la journée <b>et</b> au journal ({@code ANNEXE}, {@code JARVIS}, poste {@code VOICE}).
     */
    public void addSpeech(UUID userId, UUID workspaceId, UUID hostId, String model, long characters,
            BigDecimal costUsd) {
        bump(userId, day -> {
            day.setCharsSpoken(day.getCharsSpoken() + Math.max(0L, characters));
            day.setSpeechCostUsd(day.getSpeechCostUsd().add(costUsd == null ? BigDecimal.ZERO : costUsd));
        });
        ledger(userId, workspaceId, hostId, UsageTurn.JarvisPart.VOICE, model, (Math.max(0L, characters) + 3) / 4,
                costUsd);
    }

    /** F-233 / SF-233-04 — une session d'écoute en flux : secondes et coût en dollars. */
    public void addStream(UUID userId, long seconds, BigDecimal costUsd) {
        bump(userId, day -> {
            day.setSttStreamSeconds(day.getSttStreamSeconds() + Math.max(0L, seconds));
            day.setSttStreamCostUsd(day.getSttStreamCostUsd().add(costUsd == null ? BigDecimal.ZERO : costUsd));
        });
        ledger(userId, null, null, UsageTurn.JarvisPart.STT_STREAM, null, 0L, costUsd);
    }

    /** F-244 / SF-244-01 (D3) — une retranscription d'énoncé : secondes et coût, à part de l'écoute en flux. */
    public void addUtterance(UUID userId, long seconds, BigDecimal costUsd) {
        bump(userId, day -> {
            day.setSttUtteranceSeconds(day.getSttUtteranceSeconds() + Math.max(0L, seconds));
            day.setSttUtteranceCostUsd(day.getSttUtteranceCostUsd().add(costUsd == null ? BigDecimal.ZERO : costUsd));
        });
        ledger(userId, null, null, UsageTurn.JarvisPart.STT_UTTERANCE, null, 0L, costUsd);
    }

    /** F-244 / SF-244-01 (D3) — une dictée de repli appelée depuis {@code /jarvis} : secondes et coût en dollars. */
    public void addDictation(UUID userId, long seconds, BigDecimal costUsd) {
        bump(userId, day -> {
            day.setDictationSeconds(day.getDictationSeconds() + Math.max(0L, seconds));
            day.setDictationCostUsd(day.getDictationCostUsd().add(costUsd == null ? BigDecimal.ZERO : costUsd));
        });
        ledger(userId, null, null, UsageTurn.JarvisPart.DICTATION, null, 0L, costUsd);
    }

    /** Tarif de la dictée de repli (whisper-1 : 0,006 $ la minute), réglable ; sert seulement au budget de Jarvis. */
    private BigDecimal dictationCostUsdPerMinute = new BigDecimal("0.006");

    @Autowired(required = false)
    public void setDictationCost(
            @org.springframework.beans.factory.annotation.Value("${app.jarvis.dictation.cost-usd-per-minute:0.006}")
            BigDecimal perMinute) {
        if (perMinute != null && perMinute.signum() >= 0) {
            this.dictationCostUsdPerMinute = perMinute;
        }
    }

    /**
     * F-244 / SF-244-01 (D3) — un extrait de dictée de repli envoyé depuis {@code /jarvis} : la durée est estimée de la
     * taille (WAV PCM 16 kHz, sinon ≈ 32 kb/s), bornée à 1-120 s comme la retranscription, puis comptée au tarif de la
     * dictée. Best-effort : jamais au point de casser la dictée.
     */
    public void addDictationClip(UUID userId, int bytes, String contentType) {
        try {
            long seconds = dictationSeconds(bytes, contentType);
            addDictation(userId, seconds, dictationCostUsdPerMinute.multiply(BigDecimal.valueOf(seconds))
                    .divide(BigDecimal.valueOf(60), 6, java.math.RoundingMode.HALF_UP));
        } catch (RuntimeException e) {
            log.warn("Dictée Jarvis non comptée ({})", e.getClass().getSimpleName());
        }
    }

    /** Les secondes estimées d'un extrait de dictée (1 à 120 s). */
    static long dictationSeconds(int bytes, String contentType) {
        boolean wav = contentType != null && contentType.contains("wav");
        long estimate = wav ? Math.round(Math.ceil(bytes / 32_000.0)) : Math.round(Math.ceil(bytes / 4_000.0));
        return Math.max(1L, Math.min(120L, estimate));
    }

    /** F-244 / SF-244-01 — la ligne du journal d'une dépense Jarvis hors jetons ; best-effort. */
    private void ledger(UUID userId, UUID workspaceId, UUID hostId, UsageTurn.JarvisPart part, String model,
            long inputTokens, BigDecimal costUsd) {
        if (ledger == null || userId == null) {
            return;
        }
        try {
            ledger.recordJarvisCost(userId, workspaceId, hostId, part, model, inputTokens, costUsd, PRICING_VERSION);
        } catch (RuntimeException e) {
            log.warn("Dépense Jarvis non inscrite au journal ({})", e.getClass().getSimpleName());
        }
    }

    /** F-235 / SF-235-01 (D15) — un appel au cerveau rapide et son coût en dollars. */
    public void addFastBrain(UUID userId, BigDecimal costUsd) {
        bump(userId, day -> {
            day.setFastBrainCalls(day.getFastBrainCalls() + 1);
            day.setFastBrainCostUsd(day.getFastBrainCostUsd().add(costUsd == null ? BigDecimal.ZERO : costUsd));
        });
    }

    /** F-239 / SF-239-12 — un tour Opus né de Jarvis et son coût en dollars. */
    public void addOpus(UUID userId, BigDecimal costUsd) {
        bump(userId, day -> {
            day.setOpusTurns(day.getOpusTurns() + 1);
            day.setOpusCostUsd(day.getOpusCostUsd().add(costUsd == null ? BigDecimal.ZERO : costUsd));
        });
    }

    /** F-235 / SF-235-01 (D15) — le dollar dépensé par le cerveau rapide depuis le premier jour du mois. */
    public BigDecimal monthFastBrainCostUsd(UUID userId) {
        BigDecimal sum = repository.sumFastBrainCostUsdSince(userId, today().withDayOfMonth(1));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * F-234 / SF-234-11 (D16) — les scènes d'un tour : compte par identifiant de scène (déjà validé par l'appelant),
     * nœuds allumés, demandes « Montrer ». Aucun contenu.
     */
    public void addScenes(UUID userId, java.util.Map<String, Integer> counts, int lit, int shows) {
        bump(userId, day -> {
            java.util.Map<String, Integer> merged = SceneCounts.parse(day.getSceneCounts());
            int shown = 0;
            int generic = 0;
            for (var e : counts.entrySet()) {
                int n = Math.max(0, e.getValue() == null ? 0 : e.getValue());
                merged.merge(e.getKey(), n, Integer::sum);
                shown += n;
                if (SceneCounts.GENERIC.equals(e.getKey())) {
                    generic += n;
                }
            }
            day.setSceneCounts(SceneCounts.format(merged));
            day.setScenesShown(day.getScenesShown() + shown);
            day.setScenesGeneric(day.getScenesGeneric() + generic);
            day.setNodesLit(day.getNodesLit() + Math.max(0, lit));
            day.setShowRequests(day.getShowRequests() + Math.max(0, shows));
        });
    }

    /**
     * F-236 / SF-236-06 — une session de l'écran Jarvis vue par le rendu : 3D ou 2D, et ses compteurs (clés du
     * vocabulaire fermé de {@link RenderCounts}, déjà validées). Aucun contenu.
     */
    public void addRender(UUID userId, boolean threeD, java.util.Map<String, Integer> counts) {
        bump(userId, day -> {
            if (threeD) {
                day.setRender3d(day.getRender3d() + 1);
            } else {
                day.setRender2d(day.getRender2d() + 1);
            }
            java.util.Map<String, Integer> merged = RenderCounts.parse(day.getRenderCounts());
            counts.forEach((k, v) -> merged.merge(k, Math.max(0, v), Integer::sum));
            day.setRenderCounts(RenderCounts.format(merged));
        });
    }

    /** F-234 / SF-234-11 — un toucher de nœud de la constellation (« passe sur X »). */
    public void addNodeTouch(UUID userId) {
        bump(userId, day -> day.setNodeTouches(day.getNodeTouches() + 1));
    }

    /** F-234 / SF-234-11 — le mode sobre activé sur un appareil. */
    public void addSoberOn(UUID userId) {
        bump(userId, day -> day.setSoberOn(day.getSoberOn() + 1));
    }

    /** SF-231-05 — une ouverture de l'écran Jarvis (mesure d'adoption, D9). */
    public void addSession(UUID userId) {
        bump(userId, day -> day.setSessions(day.getSessions() + 1));
    }

    /** SF-231-05 — des secondes parlées par le PO (bornées par l'appelant). */
    public void addSpoken(UUID userId, long seconds) {
        bump(userId, day -> day.setSpokenSeconds(day.getSpokenSeconds() + Math.max(0L, seconds)));
    }

    /** F-242 / SF-242-02 — une relève de boîte ; {@code empty} : rien de neuf (aucun FETCH, aucune IA). */
    public void addMailPoll(UUID userId, boolean empty, int fetched) {
        bump(userId, day -> {
            day.setMailPolls(day.getMailPolls() + 1);
            if (empty) {
                day.setMailPollsEmpty(day.getMailPollsEmpty() + 1);
            }
            day.setMailFetched(day.getMailFetched() + Math.max(0, fetched));
        });
    }

    /** F-243 / SF-243-01 — une relève d'agenda ; {@code unchanged} : rien n'a été analysé (304 ou même empreinte). */
    public void addCalendarPoll(UUID userId, boolean unchanged) {
        bump(userId, day -> {
            day.setCalendarPolls(day.getCalendarPolls() + 1);
            if (unchanged) {
                day.setCalendarPollsUnchanged(day.getCalendarPollsUnchanged() + 1);
            }
        });
    }

    /** F-242 / SF-242-03 — le coût d'un appel de tri du courrier (dollars, ligne « Courrier » du budget Jarvis). */
    public void addMailCost(UUID userId, BigDecimal costUsd) {
        bump(userId, day -> day.setMailCostUsd(day.getMailCostUsd().add(costUsd == null ? BigDecimal.ZERO : costUsd)));
    }

    /** F-242 / SF-242-03 — une correction du tri au toucher. */
    public void addMailTriageCorrected(UUID userId) {
        bump(userId, day -> day.setMailTriageCorrected(day.getMailTriageCorrected() + 1));
    }

    /** SF-231-05 — un réveil par le mot « Jarvis ». */
    public void addWake(UUID userId) {
        bump(userId, day -> day.setWakes(day.getWakes() + 1));
    }

    /** F-233 / SF-233-11 — un retour au repos par fin de conversation (silence prolongé). */
    public void addIdleEnd(UUID userId) {
        bump(userId, day -> day.setConversationIdleEnds(day.getConversationIdleEnds() + 1));
    }

    /** SF-231-05 — les journées du compte depuis {@code days} jours (aujourd'hui compris), les plus anciennes d'abord. */
    public java.util.List<JarvisUsageDay> lastDays(UUID userId, int days) {
        return repository.findByUserIdAndDayGreaterThanEqualOrderByDayAsc(userId, today().minusDays(Math.max(1, days) - 1L));
    }

    /** Le dollar dépensé par la voix depuis le premier jour du mois courant. */
    public BigDecimal monthSpeechCostUsd(UUID userId) {
        LocalDate first = today().withDayOfMonth(1);
        BigDecimal sum = repository.sumSpeechCostUsdSince(userId, first);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** Aujourd'hui, à l'horloge de l'application. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Incrémente la journée du compte ; best-effort. */
    void bump(UUID userId, Consumer<JarvisUsageDay> change) {
        if (userId == null) {
            return;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                tx.executeWithoutResult(status -> {
                    LocalDate day = today();
                    JarvisUsageDay row = repository.findByUserIdAndDay(userId, day)
                            .orElseGet(() -> JarvisUsageDay.builder().userId(userId).day(day).build());
                    change.accept(row);
                    row.setUpdatedAt(OffsetDateTime.now(clock));
                    repository.saveAndFlush(row);
                });
                return;
            } catch (DataIntegrityViolationException e) {
                // La ligne du jour vient d'être créée par une écriture concurrente : on la relit et on incrémente.
                log.debug("Journée Jarvis créée en concurrence, nouvel essai");
            } catch (RuntimeException e) {
                log.warn("Journée Jarvis non inscrite ({})", e.getClass().getSimpleName());
                return;
            }
        }
    }
}
