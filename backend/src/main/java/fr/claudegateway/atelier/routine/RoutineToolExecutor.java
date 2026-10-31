package fr.claudegateway.atelier.routine;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.routine.RoutineDtos.RoutineRequest;
import fr.claudegateway.atelier.routine.RoutineDtos.RoutineView;

/**
 * Exécute l'outil {@code routine_proposer} (F-183 / SF-183-01) : traduit l'entrée de l'agent en une
 * proposition rangée par {@link RoutineService#propose}. Le terminal et le compte sont ceux du tour ;
 * la garde est réévaluée ici (un appel hors garde est refusé, rien n'est créé).
 */
@Component
public class RoutineToolExecutor {

    /** Résultat rendu au modèle. */
    public record Outcome(String content, boolean error) {
    }

    private static final Map<String, Integer> DAYS = Map.of("lundi", 1, "mardi", 2, "mercredi", 3, "jeudi", 4,
            "vendredi", 5, "samedi", 6, "dimanche", 7);

    private final RoutineToolCatalog catalog;
    private final RoutineService service;

    public RoutineToolExecutor(RoutineToolCatalog catalog, RoutineService service) {
        this.catalog = catalog;
        this.service = service;
    }

    public Outcome execute(UUID userId, Workspace workspace, String tool, JsonNode input) {
        if (!RoutineToolCatalog.isRoutineTool(tool) || !catalog.isOpenFor(userId, workspace)) {
            return new Outcome("Les routines se déclarent dans le terminal d'un sujet sur poste : "
                    + "réponds sans cet outil.", true);
        }
        try {
            String cadence = cadence(str(input, "rythme"));
            Integer day = null;
            if ("WEEKLY".equals(cadence)) {
                day = DAYS.get(normalize(str(input, "jour")));
            }
            // F-224 / SF-224-06 : le budget se dit en euros (« budget_eur ») ; « budget_usd » reste lu pour un appel
            // formé avant le changement.
            RoutineView view = service.propose(userId, workspace, new RoutineRequest(str(input, "nom"),
                    str(input, "consigne"), cadence, day, str(input, "heure"), null,
                    amount(input == null ? null : input.get("budget_usd")), null, null,
                    amount(input == null ? null : input.get("budget_eur")), null));
            return new Outcome("Proposition de routine rangée (« " + view.name() + " ») : elle N'EST PAS "
                    + "active. Dis à l'utilisateur qu'une carte au-dessus du fil lui permet de l'activer, de "
                    + "la modifier ou de la refuser.", false);
        } catch (RoutineExceptions.RoutineException e) {
            return new Outcome("Proposition refusée : " + e.getMessage(), true);
        }
    }

    private static String cadence(String rythme) {
        String value = normalize(rythme);
        return switch (value) {
            case "quotidien" -> "DAILY";
            case "jours_ouvres", "jours ouvres", "jours_ouvrés" -> "WEEKDAYS";
            case "hebdo", "hebdomadaire" -> "WEEKLY";
            default -> value.isEmpty() ? null : value.toUpperCase(Locale.ROOT);
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String str(JsonNode input, String field) {
        return input == null || !input.hasNonNull(field) ? null : input.get(field).asText();
    }

    private static BigDecimal amount(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue();
        }
        try {
            return new BigDecimal(value.asText().replace(',', '.').strip());
        } catch (NumberFormatException e) {
            throw new RoutineExceptions.InvalidRoutineException("Budget illisible : " + value + ".");
        }
    }
}
