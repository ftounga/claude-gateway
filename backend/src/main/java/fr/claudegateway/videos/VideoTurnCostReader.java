package fr.claudegateway.videos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.AtelierProperties;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.TurnExtras;
import fr.claudegateway.quota.TurnTokens;

/**
 * <b>Le coût moyen d'un tour de l'agent</b> (F-216 / SF-216-01), base du poste « agent » du devis.
 *
 * <p>Lu dans {@code atelier_turn_measures} (F-188) : cette table ne contient <b>que</b> les tours de la boucle — pas
 * les lignes de la voix ni de Plume, que {@code usage_turns} mêle aux tours (au même modèle pour Plume). La moyenne
 * des tokens par nature, sur la fenêtre glissante, est chiffrée par la grille existante
 * ({@link ProviderCostCalculator}, F-133) : le coût étant linéaire en tokens, c'est exactement la moyenne des coûts.</p>
 *
 * <p><b>Isolation</b> : la moyenne est celle du compte ({@code user_id} dans la requête), au modèle courant de la boucle.
 * Trop peu de tours, un coût nul ou une panne de lecture ⇒ la valeur par défaut configurée : un devis se rend
 * toujours.</p>
 */
@Component
public class VideoTurnCostReader {

    private static final Logger log = LoggerFactory.getLogger(VideoTurnCostReader.class);

    private final JdbcTemplate jdbcTemplate;
    private final ProviderCostCalculator calculator;
    private final AtelierProperties atelierProperties;
    private final VideoCostProperties coefficients;
    private final Clock clock;

    public VideoTurnCostReader(JdbcTemplate jdbcTemplate, ProviderCostCalculator calculator,
            AtelierProperties atelierProperties, VideoCostProperties coefficients, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.calculator = calculator;
        this.atelierProperties = atelierProperties;
        this.coefficients = coefficients;
        this.clock = clock;
    }

    /**
     * Le coût d'un tour retenu pour le devis.
     *
     * @param costUsd  coût d'un tour, en dollars
     * @param samples  tours mesurés sur la fenêtre (0 si la valeur par défaut s'applique faute de mesure)
     * @param measured vrai si c'est la moyenne mesurée, faux si c'est la valeur par défaut
     * @param model    modèle de la boucle
     */
    public record TurnCostEstimate(BigDecimal costUsd, long samples, boolean measured, String model) {
    }

    /** Le coût moyen d'un tour du compte, ou la valeur par défaut. */
    public TurnCostEstimate estimate(UUID userId) {
        String model = atelierProperties.model();
        BigDecimal fallback = coefficients.defaultTurnCostUsd();
        if (userId == null) {
            return new TurnCostEstimate(fallback, 0, false, model);
        }
        try {
            Timestamp since = Timestamp.from(clock.instant().minus(coefficients.turnCostWindow()));
            Averages averages = jdbcTemplate.queryForObject("SELECT COUNT(*), AVG(input_tokens), AVG(output_tokens), "
                    + "AVG(cache_read_tokens), AVG(cache_write_tokens), AVG(web_search_requests) "
                    + "FROM atelier_turn_measures WHERE user_id = ? AND model = ? AND occurred_at >= ?",
                    (rs, i) -> new Averages(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                            rs.getDouble(5), rs.getDouble(6)),
                    userId, model, since);
            if (averages == null || averages.count() < coefficients.minTurnSamples()) {
                return new TurnCostEstimate(fallback, averages == null ? 0 : averages.count(), false, model);
            }
            long cacheRead = Math.round(averages.cacheRead());
            long cacheWrite = Math.round(averages.cacheWrite());
            long uncached = Math.max(0L, Math.round(averages.input()) - cacheRead - cacheWrite);
            TurnTokens tokens = new TurnTokens(uncached, Math.round(averages.output()), cacheRead, cacheWrite);
            BigDecimal cost = calculator.calculate(tokens, new TurnExtras(Math.round(averages.webSearch()), 0L), model)
                    .amountUsd();
            if (cost == null || cost.signum() <= 0) {
                return new TurnCostEstimate(fallback, averages.count(), false, model);
            }
            return new TurnCostEstimate(cost.setScale(4, RoundingMode.HALF_UP), averages.count(), true, model);
        } catch (RuntimeException e) {
            log.warn("Devis vidéo : coût moyen d'un tour illisible, valeur par défaut ({})",
                    e.getClass().getSimpleName());
            return new TurnCostEstimate(fallback, 0, false, model);
        }
    }

    private record Averages(long count, double input, double output, double cacheRead, double cacheWrite,
            double webSearch) {
    }
}
