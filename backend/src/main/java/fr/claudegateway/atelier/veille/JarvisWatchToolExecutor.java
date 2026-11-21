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
        if (JarvisWatchToolCatalog.STATUS.equals(tool)) {
            return new Outcome(status(userId, workspace), false);
        }
        try {
            Integer chantier = input != null && input.hasNonNull("chantier") && input.get("chantier").canConvertToInt()
                    ? input.get("chantier").asInt() : null;
            JarvisWatchView view = service.propose(userId, workspace, new ProposeRequest(str(input, "nom"),
                    str(input, "plateforme"), str(input, "perimetre"), str(input, "commande"), str(input, "script"),
                    times(input), chantier, str(input, "usage")));
            if ("STATE".equals(view.purpose())) {
                return new Outcome("Sonde de relevé proposée (« " + view.name() + " ») : elle N'EST PAS active. "
                        + "Lance-la une fois et dis ce que tu vois ; le PO validera toutes les sondes d'un geste "
                        + "(« Tout valider », dans « Ce que je sais »).", false);
            }
            return new Outcome("Veille proposée (« " + view.name() + " », " + String.join(", ", view.runTimes())
                    + ", du lundi au vendredi) : elle N'EST PAS active. Dis au PO qu'une carte au-dessus du fil lui "
                    + "montre la commande ; il la teste, puis l'active ou la refuse.", false);
        } catch (JarvisWatchExceptions.WatchException e) {
            return new Outcome("Veille refusée : " + e.getMessage(), true);
        }
    }

    /**
     * F-273 / SF-273-03 — l'état des veilles du SEUL sujet du tour ({@code user_id + workspace_id}, via
     * {@link JarvisWatchService#list}), une ligne par veille. Lecture seule.
     */
    String status(UUID userId, Workspace workspace) {
        List<JarvisWatchView> watches = service.list(userId, workspace.getId());
        if (watches.isEmpty()) {
            return "Aucune veille ni sonde de relevé dans ce sujet.";
        }
        StringBuilder out = new StringBuilder("Veilles et sondes de relevé de ce sujet (état tenu par la passerelle) :");
        for (JarvisWatchView w : watches) {
            out.append("\n- « ").append(w.name()).append(" » (")
                    .append("STATE".equals(w.purpose()) ? "relevé" : "veille").append(") — ").append(state(w))
                    .append(" · commande : ").append(w.command());
            if (w.lastRunAt() != null) {
                out.append(" · dernier passage ").append(DATE.format(w.lastRunAt())).append(' ')
                        .append(w.lastRunStatus());
            } else {
                out.append(" · aucun passage");
            }
            if (w.lastErrorMessage() != null) {
                out.append(" · dernière erreur");
                if (w.lastErrorAt() != null) {
                    out.append(' ').append(DATE.format(w.lastErrorAt()));
                }
                out.append(" (").append(w.lastErrorCode()).append(") : ").append(w.lastErrorMessage());
            }
        }
        out.append("\nUne proposition ne tourne pas tant que le PO ne l'a pas testée et validée (carte du fil, ou "
                + "« Tout valider » dans « Ce que je sais ») ; pour réparer, corrige la sonde et rappelle "
                + "veille_poser avec le même nom.");
        return out.toString();
    }

    private static final java.time.format.DateTimeFormatter DATE = java.time.format.DateTimeFormatter
            .ofPattern("dd/MM HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC);

    private static String state(JarvisWatchView w) {
        if (w.proposed()) {
            return w.testedAt() == null ? "proposée, jamais testée avec succès (ne tourne pas)"
                    : "proposée, testée le " + DATE.format(w.testedAt()) + ", à valider par le PO (ne tourne pas)";
        }
        if (w.broken()) {
            return "en panne (" + w.consecutiveFailures() + " échecs d'affilée)";
        }
        return w.active() ? "active" : "en pause";
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
