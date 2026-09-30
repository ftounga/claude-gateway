package fr.claudegateway.atelier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.dto.AtelierResumeResponse;
import fr.claudegateway.atelier.dto.ThreadCostSummaryResponse;
import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.quota.ProviderPricingProperties.ModelPricing;
import fr.claudegateway.quota.TurnCostView;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;

/**
 * L'<b>économie du fil courant</b> (F-165 / SF-165-02), servie à la commande vue {@code /cout}.
 *
 * <p><b>Une vue, pas un moteur.</b> Ce service <b>compose</b> ce qui existe déjà — le journal
 * {@code usage_turns} (F-61), la grille de tarifs (F-133) et la conversion (SF-133-02) — pour répondre
 * à une seule question : « combien ce fil m'a-t-il coûté, et où part l'argent ? ». Aucune capacité de
 * Claude n'est réimplémentée (Provider-First) ; le backend reste une passerelle (Gateway-First).</p>
 *
 * <p><b>Isolation.</b> L'ownership est délégué à {@link AtelierThreadService#resumeState} qui appelle
 * {@code requireOwned(userId, workspaceId)} — un fil d'autrui rend un <b>404 indiscernable</b>, jamais
 * un coût. La lecture du journal filtre {@code user_id} <b>et</b> {@code workspace_id} : un utilisateur
 * ne voit <b>que</b> son propre fil. C'est délibérément distinct de la garde admin de F-133
 * ({@link TurnCostView#labelFor}), qui protège la vue de <b>refacturation inter-clients</b> : ici,
 * l'utilisateur consulte <b>sa propre</b> consommation — la raison d'être de F-165.</p>
 *
 * <p><b>Aucun contenu</b> ne sort d'ici : des volumes et des montants seulement.</p>
 */
@Service
public class AtelierThreadCostService {

    /** Devise d'affichage : l'euro est une commodité de lecture, la facture est en dollars (SF-133-02). */
    private static final String DISPLAY_CURRENCY = "EUR";

    /** Une « page » de classeur ≈ 500 tokens (~une page de texte). Langage accessible, pas du jargon. */
    private static final long PAGE_TOKENS = 500L;

    /** Longueur de la mini-tendance : les derniers tours suffisent à lire une pente. */
    private static final int TREND_TURNS = 12;

    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000L);
    private static final int USD_SCALE = 6;

    private final AtelierThreadService threadService;
    private final UsageTurnRepository usageTurnRepository;
    private final ProviderPricingProperties pricing;
    private final TurnCostView costView;

    public AtelierThreadCostService(AtelierThreadService threadService,
            UsageTurnRepository usageTurnRepository, ProviderPricingProperties pricing,
            TurnCostView costView) {
        this.threadService = threadService;
        this.usageTurnRepository = usageTurnRepository;
        this.pricing = pricing;
        this.costView = costView;
    }

    /**
     * L'économie du fil courant du projet.
     *
     * @param userId      utilisateur du contexte de sécurité (jamais un paramètre client)
     * @param workspaceId projet consulté
     * @throws WorkspaceNotFoundException 404 indiscernable si le projet n'existe pas ou n'appartient
     *                                    pas à l'utilisateur (via {@code requireOwned})
     */
    @Transactional(readOnly = true)
    public ThreadCostSummaryResponse summary(UUID userId, UUID workspaceId) {
        // Ownership + état de reprise (tours vivants / rangés, frontière du fil) en une lecture isolée.
        AtelierResumeResponse resume = threadService.resumeState(userId, workspaceId);
        OffsetDateTime from = resume.threadStartedAt();

        // Le fil courant : les tours depuis le dernier nouveau départ (sinon tout le projet). Le filtre
        // se fait en mémoire sur une lecture déjà bornée à (user_id, workspace_id) — jamais une borne de
        // temps arbitraire côté base.
        List<UsageTurn> turns = usageTurnRepository
                .findByUserIdAndWorkspaceIdOrderByOccurredAtAsc(userId, workspaceId);
        List<UsageTurn> fil = from == null
                ? turns
                : turns.stream().filter(turn -> !turn.getOccurredAt().isBefore(from)).toList();

        BigDecimal cumulativeUsd = BigDecimal.ZERO;
        BigDecimal writeUsd = BigDecimal.ZERO;
        BigDecimal readUsd = BigDecimal.ZERO;
        BigDecimal outputUsd = BigDecimal.ZERO;
        long inputTokensTotal = 0L;
        long cacheReadTotal = 0L;

        for (UsageTurn turn : fil) {
            cumulativeUsd = cumulativeUsd.add(nonNull(turn.getProviderCostUsd()));
            ModelPricing rates = ratesFor(turn.getModel());
            // input_tokens porte l'entrée TRAITÉE (cache compris) ; les colonnes de cache la ventilent.
            long input = turn.getInputTokens();
            long cacheRead = turn.getCacheReadTokens();
            long cacheWrite = turn.getCacheWriteTokens();
            long freshInput = Math.max(0L, input - cacheRead - cacheWrite);
            // L'entrée neuve (marginale en agentique) est agrégée au poste ÉCRITURE : décomposition
            // exhaustive, part somme 100 %.
            writeUsd = writeUsd
                    .add(costOf(freshInput, rates.input()))
                    .add(costOf(cacheWrite, rates.cacheWrite()));
            readUsd = readUsd.add(costOf(cacheRead, rates.cacheRead()));
            outputUsd = outputUsd.add(costOf(turn.getOutputTokens(), rates.output()));
            inputTokensTotal += input;
            cacheReadTotal += cacheRead;
        }

        BigDecimal decompositionUsd = writeUsd.add(readUsd).add(outputUsd);
        int writePercent = percent(writeUsd, decompositionUsd);
        int readPercent = percent(readUsd, decompositionUsd);
        // La sortie reçoit le reste : la somme des trois postes fait alors EXACTEMENT 100 %.
        int outputPercent = decompositionUsd.signum() <= 0 ? 0 : 100 - writePercent - readPercent;

        ThreadCostSummaryResponse.Breakdown breakdown = new ThreadCostSummaryResponse.Breakdown(
                costView.toEur(writeUsd), writePercent,
                costView.toEur(readUsd), readPercent,
                costView.toEur(outputUsd), outputPercent);

        UsageTurn last = fil.isEmpty() ? null : fil.get(fil.size() - 1);
        BigDecimal lastTurnUsd = last == null ? BigDecimal.ZERO : nonNull(last.getProviderCostUsd());
        long contextTokens = last == null ? 0L : last.getInputTokens();

        int hotCachePercent = inputTokensTotal <= 0L
                ? 0
                : (int) Math.min(100L, Math.round(cacheReadTotal * 100.0 / inputTokensTotal));

        return new ThreadCostSummaryResponse(
                DISPLAY_CURRENCY,
                costView.toEur(cumulativeUsd),
                costView.toEur(lastTurnUsd),
                fil.size(),
                breakdown,
                hotCachePercent,
                contextTokens,
                pagesOf(contextTokens),
                resume.turns(),
                resume.foldedTurns(),
                trend(fil));
    }

    /** Le coût par tour des derniers tours, du plus ancien au plus récent (mini-tendance). */
    private List<BigDecimal> trend(List<UsageTurn> fil) {
        int fromIndex = Math.max(0, fil.size() - TREND_TURNS);
        List<BigDecimal> costs = new ArrayList<>();
        for (UsageTurn turn : fil.subList(fromIndex, fil.size())) {
            costs.add(costView.toEur(nonNull(turn.getProviderCostUsd())));
        }
        return List.copyOf(costs);
    }

    /** Tarifs du modèle servi, ou repli garanti non nul — jamais d'échec pour un problème de grille. */
    private ModelPricing ratesFor(String model) {
        ModelPricing rates = pricing.pricingOf(model);
        return rates != null ? rates : pricing.fallbackPricing();
    }

    private static BigDecimal costOf(long tokens, BigDecimal pricePerMillion) {
        if (tokens <= 0L) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens)
                .multiply(pricePerMillion)
                .divide(TOKENS_PER_MILLION, USD_SCALE, RoundingMode.HALF_UP);
    }

    /** Part entière d'un total (0 quand le total est nul : une part de rien est zéro, pas une erreur). */
    private static int percent(BigDecimal part, BigDecimal total) {
        if (total.signum() <= 0 || part.signum() <= 0) {
            return 0;
        }
        return part.multiply(BigDecimal.valueOf(100L))
                .divide(total, 0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    private static int pagesOf(long tokens) {
        if (tokens <= 0L) {
            return 0;
        }
        return (int) Math.max(1L, Math.round((double) tokens / PAGE_TOKENS));
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
