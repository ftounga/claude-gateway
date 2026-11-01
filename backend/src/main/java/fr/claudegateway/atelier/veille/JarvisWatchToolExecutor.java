package fr.claudegateway.atelier.veille;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.veille.JarvisWatchDtos.JarvisWatchView;
import fr.claudegateway.atelier.veille.JarvisWatchDtos.ProposeRequest;

/**
 * Exécute l'outil {@code veille_poser} (F-230 / SF-230-01) : traduit l'entrée de l'agent en une proposition
 * rangée par {@link JarvisWatchService#propose}. Le terminal et le compte sont ceux du tour ; la garde est
 * réévaluée ici (un appel hors garde est refusé, rien n'est créé).
 */
@Component
public class JarvisWatchToolExecutor {

    /** Résultat rendu au modèle. */
    public record Outcome(String content, boolean error) {
    }

    private final JarvisWatchToolCatalog catalog;
    private final JarvisWatchService service;

    public JarvisWatchToolExecutor(JarvisWatchToolCatalog catalog, JarvisWatchService service) {
        this.catalog = catalog;
        this.service = service;
    }

    public Outcome execute(UUID userId, Workspace workspace, String tool, JsonNode input) {
        if (!JarvisWatchToolCatalog.isWatchTool(tool) || !catalog.isOpenFor(userId, workspace)) {
            return new Outcome("Les veilles se posent dans le terminal d'un sujet sur poste : réponds sans cet "
                    + "outil.", true);
        }
        try {
            Integer chantier = input != null && input.hasNonNull("chantier") && input.get("chantier").canConvertToInt()
                    ? input.get("chantier").asInt() : null;
            JarvisWatchView view = service.propose(userId, workspace, new ProposeRequest(str(input, "nom"),
                    str(input, "plateforme"), str(input, "perimetre"), str(input, "commande"), str(input, "script"),
                    times(input), chantier));
            return new Outcome("Veille proposée (« " + view.name() + " », " + String.join(", ", view.runTimes())
                    + ", du lundi au vendredi) : elle N'EST PAS active. Dis au PO qu'une carte au-dessus du fil lui "
                    + "montre la commande ; il la teste, puis l'active ou la refuse.", false);
        } catch (JarvisWatchExceptions.WatchException e) {
            return new Outcome("Veille refusée : " + e.getMessage(), true);
        }
    }

    private static List<String> times(JsonNode input) {
        List<String> times = new ArrayList<>();
        if (input == null || !input.has("heures")) {
            return times;
        }
        JsonNode node = input.get("heures");
        if (node.isArray()) {
            node.forEach(value -> times.add(value.asText()));
        } else if (node.isTextual()) {
            // Tolérance : « 09:00, 14:00 » en une chaîne.
            for (String part : node.asText().split("[,;\\s]+")) {
                if (!part.isBlank()) {
                    times.add(part);
                }
            }
        }
        return times;
    }

    private static String str(JsonNode input, String field) {
        return input == null || !input.hasNonNull(field) ? null : input.get(field).asText();
    }
}
