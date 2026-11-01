package fr.claudegateway.jarvis;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

    /** Une lecture de la voix : caractères lus et coût en dollars. */
    public void addSpeech(UUID userId, long characters, BigDecimal costUsd) {
        bump(userId, day -> {
            day.setCharsSpoken(day.getCharsSpoken() + Math.max(0L, characters));
            day.setSpeechCostUsd(day.getSpeechCostUsd().add(costUsd == null ? BigDecimal.ZERO : costUsd));
        });
    }

    /** F-233 / SF-233-04 — une session d'écoute en flux : secondes et coût en dollars. */
    public void addStream(UUID userId, long seconds, BigDecimal costUsd) {
        bump(userId, day -> {
            day.setSttStreamSeconds(day.getSttStreamSeconds() + Math.max(0L, seconds));
            day.setSttStreamCostUsd(day.getSttStreamCostUsd().add(costUsd == null ? BigDecimal.ZERO : costUsd));
        });
    }

    /** F-235 / SF-235-01 (D15) — un appel au cerveau rapide et son coût en dollars. */
    public void addFastBrain(UUID userId, BigDecimal costUsd) {
        bump(userId, day -> {
            day.setFastBrainCalls(day.getFastBrainCalls() + 1);
            day.setFastBrainCostUsd(day.getFastBrainCostUsd().add(costUsd == null ? BigDecimal.ZERO : costUsd));
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
