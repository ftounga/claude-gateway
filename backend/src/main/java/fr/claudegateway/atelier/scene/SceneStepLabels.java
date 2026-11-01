package fr.claudegateway.atelier.scene;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.agent.AgentContentBlock;

/**
 * <b>Les étiquettes de scène</b> (F-234 / SF-234-04, D5) — un nom court pour l'étape d'un outil métier que l'écran
 * montrait jusqu'ici <b>sans cible</b> (chantier, étape, attente, passation, skill, veille, document…), et l'étape
 * {@code web} des outils serveur du fournisseur ({@code web_search}, {@code web_fetch}).
 *
 * <p><b>Additif et sans effet sur le raisonnement</b> : ces étiquettes ne servent qu'à l'événement {@code action} du
 * flux (écran Jarvis, terminal). Elles ne touchent ni la consigne système, ni les descriptions d'outils, ni le message,
 * ni le journal d'audit ({@code auditTarget} inchangé) — le préfixe de cache reste octet pour octet identique.</p>
 *
 * <p>Jamais de contenu : des noms, des numéros, des états ; ni corps de message, ni description d'attente, ni
 * diagnostic, ni consigne. Une requête web se montre (c'est ce qu'on a cherché) ; une page lue, par son
 * <b>domaine</b> seul.</p>
 */
public final class SceneStepLabels {

    /** Longueur maximale d'une étiquette. */
    public static final int MAX_CHARS = 120;
    /** Le type d'étape des outils serveur du fournisseur. */
    public static final String WEB_STEP = "web";

    private SceneStepLabels() {
    }

    /** L'étiquette de l'outil {@code tool}, ou {@code null} s'il n'en a pas (l'écran garde alors la cible d'audit). */
    public static String label(String tool, JsonNode input) {
        if (tool == null || input == null) {
            return null;
        }
        String label = switch (tool) {
            case "update_plan_step" -> join(chantier(input), step(input), stepStatus(text(input, "status")));
            case "update_sub_steps" -> join(chantier(input), step(input), "sous-étapes");
            case "set_subject_plan" -> join(chantier(input), planSize(input));
            case "valider_plan", "confirmer_diagnostic" -> chantier(input);
            case "lancer_chantier_autonome" -> join(chantier(input),
                    "UNTIL_BLOCKED".equals(text(input, "mode")) ? "jusqu'au blocage" : null);
            case "submit_diagnosis" -> join(chantier(input), confidence(text(input, "confidence")));
            case "record_blocker" -> join(text(input, "person"),
                    "MESSAGE".equals(text(input, "kind")) ? "message" : text(input, "categorie") == null ? null
                            : text(input, "categorie").toLowerCase(Locale.ROOT));
            case "update_blocker" -> join(text(input, "requested_to"),
                    "DEMANDE".equals(text(input, "status")) ? "demandé"
                            : "A_FAIRE".equals(text(input, "status")) ? "à refaire" : null);
            case "close_blocker" -> input.path("cancelled").asBoolean(false) ? "annulée" : "levée";
            case "ouvrir_sujet" -> text(input, "sujet");
            case "create_subject" -> text(input, "name");
            case "skill" -> text(input, "nom");
            case "gouvernance_proposer" -> join(lower(text(input, "type")), text(input, "nom"));
            case "veille_poser" -> join(text(input, "plateforme"), text(input, "nom"));
            case "routine_proposer" -> join(text(input, "nom"), text(input, "rythme"));
            case "signaler_besoin" -> join(lower(text(input, "type")), text(input, "titre"));
            case "build_presentation", "build_document", "build_spreadsheet", "render_diagram" ->
                    text(input, "filename");
            default -> null;
        };
        return shorten(label);
    }

    /**
     * Les étapes {@code web} d'une réponse du fournisseur : une par {@code server_tool_use} — la requête d'une
     * recherche, le domaine d'une page lue. Rien pour les résultats.
     */
    public static List<WebStep> webSteps(List<AgentContentBlock> reasoning) {
        List<WebStep> steps = new ArrayList<>();
        if (reasoning == null) {
            return steps;
        }
        for (AgentContentBlock block : reasoning) {
            if (!(block instanceof AgentContentBlock.ServerTool server) || server.raw() == null
                    || !"server_tool_use".equals(server.raw().path("type").asText())) {
                continue;
            }
            String name = server.raw().path("name").asText("");
            JsonNode input = server.raw().path("input");
            if ("web_search".equals(name)) {
                steps.add(new WebStep(name, shorten(text(input, "query"))));
            } else if ("web_fetch".equals(name)) {
                steps.add(new WebStep(name, domain(text(input, "url"))));
            }
        }
        return steps;
    }

    /** Une étape web : l'outil serveur et sa cible lisible. */
    public record WebStep(String tool, String target) {
    }

    /** Le domaine d'une adresse, sans chemin ni paramètres ({@code null} si illisible). */
    static String domain(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url.strip()).getHost();
            if (host == null) {
                return null;
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String chantier(JsonNode input) {
        return input.hasNonNull("chantier") && input.get("chantier").canConvertToInt()
                ? "chantier " + input.get("chantier").asInt() : null;
    }

    private static String step(JsonNode input) {
        return input.hasNonNull("step") && input.get("step").canConvertToInt()
                ? "étape " + input.get("step").asInt() : null;
    }

    private static String planSize(JsonNode input) {
        JsonNode steps = input.path("steps");
        return steps.isArray() && !steps.isEmpty() ? "plan en " + steps.size() + " étape" + (steps.size() > 1 ? "s" : "")
                : null;
    }

    private static String stepStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "FAIT" -> "faite";
            case "VERIFIE" -> "vérifiée";
            case "ECHEC" -> "en échec";
            case "BLOQUE" -> "bloquée";
            case "A_FAIRE" -> "à faire";
            default -> null;
        };
    }

    private static String confidence(String confidence) {
        return confidence == null ? null : "confiance " + confidence.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String text(JsonNode input, String key) {
        JsonNode node = input == null ? null : input.get(key);
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText().strip().replaceAll("\\s+", " ");
        return value.isEmpty() ? null : value;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!out.isEmpty()) {
                    out.append(" · ");
                }
                out.append(part);
            }
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static String shorten(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_CHARS ? value : value.substring(0, MAX_CHARS - 1).stripTrailing() + "…";
    }
}
