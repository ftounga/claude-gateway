package fr.claudegateway.speech;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.quota.TurnCost;
import fr.claudegateway.quota.TurnExtras;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageLedgerService;

/**
 * <b>L'usage de la voix off au journal d'usage</b> (F-215 / SF-215-01), comme Plume : une ligne par appel,
 * à son coût calculé, rattachée au compte, au sujet et au poste. Aucun contenu n'y entre.
 *
 * <p>L'API de synthèse ne rapporte pas d'usage : les jetons d'entrée sont estimés (caractères / 4) et le
 * coût calculé sur des tarifs configurables ({@link SpeechProperties}) — marqué {@code CALCULATED}.</p>
 *
 * <p>Ne fait jamais échouer l'appel : le fournisseur est déjà payé, un relevé perdu est un défaut
 * d'information (même arbitrage que {@link UsageLedgerService}).</p>
 */
@Component
public class SpeechUsage {

    private static final Logger log = LoggerFactory.getLogger(SpeechUsage.class);

    /** Date de la grille de tarifs de la voix (OpenAI, 2026-10). */
    static final String PRICING_VERSION = "speech-2026-10";

    private final UsageLedgerService ledger;
    private final SpeechProperties properties;

    public SpeechUsage(UsageLedgerService ledger, SpeechProperties properties) {
        this.ledger = ledger;
        this.properties = properties;
    }

    /**
     * Inscrit un appel.
     *
     * @param characters         caractères lus à voix haute (toutes voix confondues)
     * @param transcribedSeconds secondes d'audio transcrites en mode mots (0 sinon)
     */
    public void record(UUID userId, UUID workspaceId, UUID hostId, long characters, double transcribedSeconds) {
        if (userId == null || characters <= 0) {
            return;
        }
        try {
            TurnTokens tokens = TurnTokens.of((characters + 3) / 4, 0L);
            ledger.recordTurn(userId, workspaceId, hostId, tokens, TurnExtras.NONE,
                    new TurnCost(cost(characters, transcribedSeconds), TurnCost.Source.CALCULATED,
                            properties.model(), PRICING_VERSION, false));
        } catch (RuntimeException e) {
            log.warn("Voix off : consommation non inscrite au journal ({})", e.getClass().getSimpleName());
        }
    }

    /** Le coût calculé d'un appel, en dollars. */
    BigDecimal cost(long characters, double transcribedSeconds) {
        BigDecimal speech = properties.costUsdPerThousandChars()
                .multiply(BigDecimal.valueOf(characters))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        BigDecimal transcription = properties.transcriptionCostUsdPerMinute()
                .multiply(BigDecimal.valueOf(Math.max(0d, transcribedSeconds)))
                .divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP);
        return speech.add(transcription);
    }
}
