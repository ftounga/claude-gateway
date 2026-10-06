package fr.claudegateway.atelier.poste;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.AtelierToolTrace;

/**
 * <b>La mesure du terminal central</b> (F-178 / SF-178-04) : sur les terminaux du poste de l'utilisateur,
 * avant / après un pivot — combien de fois l'agent a pris les nouveaux chemins (recall portée poste,
 * {@code sujets_etat}, lectures de l'application) plutôt que la fouille ({@code bash}, {@code read_file},
 * {@code grep}), et ce qu'a coûté un tour en moyenne.
 *
 * <p>Lecture seule, sous {@code user_id}. Les appels sont comptés depuis la trajectoire d'outils persistée
 * ({@code atelier_messages.tool_trace}), bornée à {@link #MAX_TRACES} messages par fenêtre. Ce n'est pas
 * un tableau de bord : c'est la preuve demandée par la règle « justesse avant coût » — on vérifie qu'on a
 * ajouté un accès sans alourdir le tour.</p>
 */
@Service
public class PosteToolsMeasureService {

    static final int MAX_TRACES = 5_000;

    /** Les outils dont on compte l'usage. */
    static final List<String> TRACKED = List.of("recall_poste", "recall_fil", PosteToolCatalog.SUBJECTS_STATE,
            PosteToolCatalog.RADAR_RESUME, PosteToolCatalog.RADAR_SUJETS, PosteToolCatalog.PAGES_LISTER,
            PosteToolCatalog.PAGE_LIRE, PosteToolCatalog.COMPTE_CONSOMMATION, "bash", "read_file", "grep");

    static final String HOST_TERMINALS_SQL = "SELECT id FROM workspaces WHERE user_id = ? AND host_terminal = TRUE";

    static final String TURNS_SQL = "SELECT count(*) AS turns, coalesce(sum(provider_cost_usd), 0) AS cost "
            + "FROM usage_turns WHERE user_id = ? AND workspace_id = ? AND occurred_at >= ? AND occurred_at < ?";

    static final String TRACES_SQL = "SELECT tool_trace FROM atelier_messages WHERE user_id = ? "
            + "AND workspace_id = ? AND tool_trace IS NOT NULL AND created_at >= ? AND created_at < ? "
            + "ORDER BY created_at DESC LIMIT ?";

    private final JdbcTemplate jdbc;

    public PosteToolsMeasureService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Une fenêtre {@code [from, to)}.
     *
     * @param turns          tours des terminaux du poste
     * @param costPerTurnUsd coût moyen d'un tour (USD, fournisseur)
     * @param calls          appels par outil suivi
     */
    public record Window(OffsetDateTime from, OffsetDateTime to, long hostTerminals, long turns,
            BigDecimal costPerTurnUsd, Map<String, Long> calls) {
    }

    /** Avant / après, et ce qu'il faut lire. */
    public record Comparison(Window before, Window after, List<String> notes) {
    }

    @Transactional(readOnly = true)
    public Window window(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        Timestamp start = Timestamp.from(from.toInstant());
        Timestamp end = Timestamp.from(to.toInstant());
        List<UUID> terminals = jdbc.query(HOST_TERMINALS_SQL, (rs, n) -> rs.getObject("id", UUID.class), userId);
        long turns = 0;
        BigDecimal cost = BigDecimal.ZERO;
        Map<String, Long> calls = new TreeMap<>();
        TRACKED.forEach(name -> calls.put(name, 0L));
        for (UUID terminal : terminals) {
            Map<String, Object> row = jdbc.queryForMap(TURNS_SQL, userId, terminal, start, end);
            turns += ((Number) row.get("turns")).longValue();
            Object c = row.get("cost");
            if (c instanceof BigDecimal b) {
                cost = cost.add(b);
            } else if (c instanceof Number num) {
                cost = cost.add(BigDecimal.valueOf(num.doubleValue()));
            }
            for (String json : jdbc.queryForList(TRACES_SQL, String.class, userId, terminal, start, end,
                    MAX_TRACES)) {
                count(AtelierToolTrace.fromJson(json), calls);
            }
        }
        BigDecimal perTurn = turns == 0 ? BigDecimal.ZERO
                : cost.divide(BigDecimal.valueOf(turns), 4, RoundingMode.HALF_UP);
        return new Window(from, to, terminals.size(), turns, perTurn, calls);
    }

    static void count(AtelierToolTrace trace, Map<String, Long> calls) {
        if (trace == null || trace.isEmpty()) {
            return;
        }
        for (AtelierToolTrace.Step step : trace.steps()) {
            if (step == null || step.calls() == null) {
                continue;
            }
            for (AtelierToolTrace.Call call : step.calls()) {
                String name = call == null ? null : call.name();
                if ("recall".equals(name)) {
                    boolean host = call.input() != null
                            && "poste".equalsIgnoreCase(call.input().path("portee").asText(""));
                    name = host ? "recall_poste" : "recall_fil";
                }
                if (name != null && calls.containsKey(name)) {
                    calls.merge(name, 1L, Long::sum);
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public Comparison compare(UUID userId, OffsetDateTime pivot, int days, OffsetDateTime now) {
        OffsetDateTime afterEnd = pivot.plusDays(days).isAfter(now) ? now : pivot.plusDays(days);
        Window before = window(userId, pivot.minusDays(days), pivot);
        Window after = window(userId, pivot, afterEnd.isBefore(pivot) ? pivot : afterEnd);
        List<String> notes = new ArrayList<>();
        long newPaths = after.calls().getOrDefault("recall_poste", 0L)
                + after.calls().getOrDefault(PosteToolCatalog.SUBJECTS_STATE, 0L);
        notes.add("Nouveaux chemins pris après le pivot (recall poste + sujets_etat) : " + newPaths + ".");
        notes.add("Coût moyen d'un tour au terminal du poste : " + before.costPerTurnUsd() + " $ avant → "
                + after.costPerTurnUsd() + " $ après (fournisseur, USD).");
        notes.add("À lire avec la justesse : une baisse de bash/read_file n'est un gain que si les réponses "
                + "citent leur source (sujet, date) — lecture humaine des fils.");
        return new Comparison(before, after, notes);
    }
}
