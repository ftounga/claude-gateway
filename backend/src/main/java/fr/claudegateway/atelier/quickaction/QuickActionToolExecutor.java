package fr.claudegateway.atelier.quickaction;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.QuickActionRequest;
import fr.claudegateway.atelier.quickaction.QuickActionDtos.QuickActionView;
import fr.claudegateway.atelier.skills.SkillTriggers;

/**
 * Exécute l'outil {@code action_rapide} (F-275 / SF-275-02) : traduit l'entrée de l'agent en appel de
 * {@link QuickActionService}. Le terminal et le compte sont ceux du tour (jamais lus dans l'entrée) ; la garde est
 * réévaluée ici. Une action est désignée parmi celles <b>visibles dans ce terminal</b> seulement.
 */
@Component
public class QuickActionToolExecutor {

    /** Résultat rendu au modèle. */
    public record Outcome(String content, boolean error) {
    }

    private static final Map<String, List<Integer>> DAYS = Map.of("lundi", List.of(1), "mardi", List.of(2),
            "mercredi", List.of(3), "jeudi", List.of(4), "vendredi", List.of(5), "samedi", List.of(6),
            "dimanche", List.of(7), "jours_ouvres", List.of(1, 2, 3, 4, 5));
    private static final String[] DAY_NAMES = {"", "lun", "mar", "mer", "jeu", "ven", "sam", "dim"};
    /** Plafond de lignes rendues par {@code lister}. */
    static final int MAX_LISTED = 40;

    private final QuickActionToolCatalog catalog;
    private final QuickActionService service;

    public QuickActionToolExecutor(QuickActionToolCatalog catalog, QuickActionService service) {
        this.catalog = catalog;
        this.service = service;
    }

    public Outcome execute(UUID userId, Workspace workspace, String tool, JsonNode input) {
        if (!QuickActionToolCatalog.isQuickActionTool(tool) || !catalog.isOpenFor(userId, workspace)) {
            return new Outcome("Les actions rapides n'existent pas dans ce terminal : réponds sans cet outil.", true);
        }
        try {
            return switch (normalize(str(input, "operation"))) {
                case "ajouter" -> add(userId, workspace, input);
                case "lister" -> list(userId, workspace);
                case "modifier" -> update(userId, workspace, input);
                case "supprimer" -> delete(userId, workspace, input);
                default -> new Outcome("operation : ajouter, lister, modifier ou supprimer.", true);
            };
        } catch (QuickActionException e) {
            return new Outcome("Refusé : " + e.getMessage(), true);
        }
    }

    private Outcome add(UUID userId, Workspace workspace, JsonNode input) {
        QuickActionView view = service.create(userId, workspace, new QuickActionRequest(str(input, "libelle"),
                str(input, "phrase"), upper(str(input, "portee")), target(userId, workspace, input), null,
                click(input), days(input), moment(input), null));
        return new Outcome("Action rapide rangée : " + line(view) + ". Elle apparaît au bouton « Actions rapides » de "
                + "la saisie, sans recharger. Termine ton tour en une ligne.", false);
    }

    private Outcome update(UUID userId, Workspace workspace, JsonNode input) {
        QuickAction found = designate(userId, workspace, str(input, "action"));
        String sujet = str(input, "sujet");
        boolean clearTarget = sujet != null && isHere(sujet);
        QuickActionView view = service.update(userId, workspace, found.getId(), new QuickActionRequest(
                str(input, "libelle"), str(input, "phrase"), upper(str(input, "portee")),
                clearTarget ? null : target(userId, workspace, input), clearTarget, click(input), days(input),
                moment(input), input != null && input.path("sans_suggestion").asBoolean(false)));
        return new Outcome("Action rapide modifiée : " + line(view) + ".", false);
    }

    private Outcome delete(UUID userId, Workspace workspace, JsonNode input) {
        QuickAction found = designate(userId, workspace, str(input, "action"));
        service.delete(userId, found.getId());
        return new Outcome("Action rapide « " + found.getLabel() + " » supprimée.", false);
    }

    private Outcome list(UUID userId, Workspace workspace) {
        List<QuickActionView> views = service.list(userId, workspace, true);
        if (views.isEmpty()) {
            return new Outcome("Aucune action rapide dans ce terminal.", false);
        }
        List<String> lines = new ArrayList<>();
        for (QuickActionView v : views.subList(0, Math.min(views.size(), MAX_LISTED))) {
            lines.add("- " + line(v) + (v.id() != null ? " [id " + v.id() + "]" : " [skill " + v.skill() + "]"));
        }
        if (views.size() > MAX_LISTED) {
            lines.add("… et " + (views.size() - MAX_LISTED) + " autres.");
        }
        return new Outcome(views.size() + " action(s) rapide(s) :\n" + String.join("\n", lines), false);
    }

    /** L'action perso visée, parmi celles visibles ici : id exact, sinon libellé (casse et espaces ignorés). */
    private QuickAction designate(UUID userId, Workspace workspace, String wanted) {
        if (wanted == null || wanted.isBlank()) {
            throw QuickActionException.invalid("Donne l'action visée (« action » : son libellé ou son id).");
        }
        List<QuickAction> visible = service.visible(userId, workspace);
        String w = wanted.strip();
        for (QuickAction a : visible) {
            if (a.getId().toString().equals(w)) {
                return a;
            }
        }
        String key = SkillTriggers.key(w);
        List<QuickAction> byLabel = visible.stream().filter(a -> a.getLabelKey().equals(key)).toList();
        if (byLabel.size() == 1) {
            return byLabel.get(0);
        }
        String known = visible.stream().map(QuickAction::getLabel).limit(12).collect(Collectors.joining(", "));
        throw QuickActionException.invalid("Aucune action rapide « " + w + " » ici"
                + (known.isEmpty() ? " (aucune action personnalisée)." : " (actions : " + known + ")."));
    }

    private UUID target(UUID userId, Workspace workspace, JsonNode input) {
        String sujet = str(input, "sujet");
        if (sujet == null || isHere(sujet)) {
            return null;
        }
        return service.resolveSubject(userId, workspace, sujet).getId();
    }

    private static boolean isHere(String sujet) {
        String v = normalize(sujet);
        return v.isEmpty() || v.equals("ici") || v.equals("aucun");
    }

    private static String click(JsonNode input) {
        String v = normalize(str(input, "clic"));
        if (v.isEmpty()) {
            return null;
        }
        return v.startsWith("envoy") ? QuickAction.ENVOYER : v.startsWith("depos") ? QuickAction.DEPOSER : v.toUpperCase(Locale.ROOT);
    }

    private static List<Integer> days(JsonNode input) {
        JsonNode node = input == null ? null : input.get("suggestion_jours");
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        List<Integer> out = new ArrayList<>();
        for (JsonNode d : node) {
            List<Integer> mapped = DAYS.get(normalize(d.asText()).replace(' ', '_'));
            if (mapped == null) {
                throw QuickActionException.invalid("Jour inconnu : « " + d.asText() + " » (lundi … dimanche, jours_ouvres).");
            }
            out.addAll(mapped);
        }
        return out;
    }

    private static String moment(JsonNode input) {
        String v = normalize(str(input, "suggestion_moment")).replace(' ', '_').replace('-', '_');
        return v.isEmpty() ? null : v.toUpperCase(Locale.ROOT);
    }

    /** « Point hebdo » → « point hebdo » · reporting-securite · envoi direct · portée POSTE · lun matin · lancée le … */
    private static String line(QuickActionView v) {
        List<String> parts = new ArrayList<>();
        parts.add("« " + v.label() + " » → « " + v.phrase() + " »");
        parts.add(v.targetMissing() ? "sujet cible introuvable" : v.targetName() != null ? "dans " + v.targetName() : "ici");
        parts.add(QuickAction.ENVOYER.equals(v.clickMode()) ? "envoi direct" : "dans la saisie");
        if (v.scope() != null) {
            parts.add("portée " + v.scope());
        }
        if (!v.suggestDays().isEmpty()) {
            parts.add("suggérée " + v.suggestDays().stream().map(d -> DAY_NAMES[d]).collect(Collectors.joining(" "))
                    + (v.suggestMoment() == null || "JOURNEE".equals(v.suggestMoment()) ? ""
                            : " " + v.suggestMoment().toLowerCase(Locale.ROOT).replace('_', '-')));
        }
        parts.add(v.lastRunAt() == null ? "jamais lancée" : "lancée le " + v.lastRunAt().toLocalDate());
        return String.join(" · ", parts);
    }

    private static String upper(String v) {
        return v == null || v.isBlank() ? null : normalize(v).toUpperCase(Locale.ROOT);
    }

    /** Minuscules, sans accents, bords retirés. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String n = Normalizer.normalize(value.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT);
    }

    private static String str(JsonNode input, String field) {
        return input == null || !input.hasNonNull(field) ? null : input.get(field).asText();
    }
}
