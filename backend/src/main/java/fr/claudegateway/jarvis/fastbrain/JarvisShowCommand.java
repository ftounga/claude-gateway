package fr.claudegateway.jarvis.fastbrain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Un ordre d'écran</b> du cerveau rapide (F-237 / SF-237-08, cadrage §6.2) : ce que l'outil {@code afficher} demande,
 * <b>validé</b> par la passerelle puis relayé au navigateur par l'événement SSE {@code show}. Aucun effet en base.
 *
 * <p>Garde-fous passerelle (§7) : action fermée ; au plus {@value #MAX_PIECES} identifiants, de forme connue ; au plus
 * {@value #MAX_APPUI} pièces <b>d'initiative</b> ({@code raison=appui}) par réponse — au-delà, tronqué et compté.</p>
 *
 * @param action  une des {@link JarvisFastBrainPrompt#SHOW_ACTIONS}
 * @param pieces  identifiants de pièces (≤ 8)
 * @param scope   la portée d'une galerie ({@code null} si vide)
 * @param reason  {@code demande} ou {@code appui}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JarvisShowCommand(String action, List<String> pieces, Scope scope, Integer index, Integer time,
        String chapter, Integer page, String zoom, String target, String reason) {

    /** Identifiants au plus par ordre (cadrage D8). */
    public static final int MAX_PIECES = 8;
    /** Pièces d'initiative au plus par réponse (cadrage D8, §7). */
    public static final int MAX_APPUI = 3;
    /** Longueur au plus d'un texte libre (chapitre, cible, sujet…). */
    static final int MAX_TEXT = 120;

    /** La forme d'un identifiant de pièce (SF-237-01, {@code PieceId}). */
    static final Pattern PIECE_ID = Pattern
            .compile("^(dep|proof|page|video|img|slides|send|mail|moment|need|diag):[A-Za-z0-9@/_.:-]{1,120}$");
    private static final Pattern ZOOM = Pattern.compile("^([+-]|reset|[1-8])$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** La portée d'une galerie : sujet, chantier, catégorie, type, série, depuis. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Scope(String subjectId, Integer chantier, String category, String type, String series,
            String since) {

        boolean isEmpty() {
            return subjectId == null && chantier == null && category == null && type == null && series == null
                    && since == null;
        }
    }

    /** Le refus d'un ordre (rendu au modèle, jamais relayé). */
    public static final String UNKNOWN_ACTION = "UNKNOWN_ACTION";
    /** Un ordre d'initiative alors que les trois pièces de la réponse sont déjà montrées. */
    public static final String APPUI_EXHAUSTED = "APPUI_EXHAUSTED";

    /**
     * Le résultat de la validation : l'ordre gardé (ou {@code null}), la raison du refus, les pièces retirées et le
     * nombre de pièces d'initiative consommées.
     */
    public record Parsed(JarvisShowCommand command, String refusal, int dropped, int appuiUsed) {

        /** Ce qui est rendu au modèle comme résultat d'outil. */
        public String toolResult() {
            if (command == null) {
                return "refusé : " + refusal;
            }
            return dropped > 0 ? "ok (" + dropped + " pièce(s) non montrée(s) : au plus trois d'initiative, huit "
                    + "en tout, identifiants connus)" : "ok";
        }
    }

    /** Valide l'entrée de l'outil ; {@code appuiLeft} = pièces d'initiative encore permises dans la réponse. */
    public static Parsed parse(JsonNode input, int appuiLeft) {
        String action = text(input, "action").toLowerCase(Locale.ROOT);
        if (!JarvisFastBrainPrompt.SHOW_ACTIONS.contains(action)) {
            return new Parsed(null, UNKNOWN_ACTION, 0, 0);
        }
        String reason = "appui".equals(text(input, "raison")) ? "appui" : "demande";
        List<String> ids = new ArrayList<>();
        int dropped = 0;
        JsonNode list = input == null ? null : input.get("pieces");
        if (list != null && list.isArray()) {
            for (JsonNode n : list) {
                String id = n == null ? "" : n.asText("").strip();
                if (PIECE_ID.matcher(id).matches() && !ids.contains(id) && ids.size() < MAX_PIECES) {
                    ids.add(id);
                } else if (!id.isEmpty()) {
                    dropped++;
                }
            }
        }
        Scope scope = scope(input == null ? null : input.get("portee"));
        int appuiUsed = 0;
        if ("appui".equals(reason)) {
            int left = Math.max(0, appuiLeft);
            if (left == 0) {
                return new Parsed(null, APPUI_EXHAUSTED, ids.size(), 0);
            }
            if (ids.size() > left) {
                dropped += ids.size() - left;
                ids = new ArrayList<>(ids.subList(0, left));
            }
            // Une galerie d'initiative compte pour une pièce ; des identifiants, pour chacun.
            appuiUsed = ids.isEmpty() ? 1 : ids.size();
        }
        String zoom = text(input, "zoom");
        String since = scope == null ? null : scope.since();
        if (scope != null && since != null && !DATE.matcher(since).matches()) {
            scope = new Scope(scope.subjectId(), scope.chantier(), scope.category(), scope.type(), scope.series(),
                    null);
        }
        JarvisShowCommand command = new JarvisShowCommand(action, List.copyOf(ids),
                scope == null || scope.isEmpty() ? null : scope, integer(input, "index"), integer(input, "temps"),
                blankToNull(text(input, "chapitre")), integer(input, "page"),
                ZOOM.matcher(zoom).matches() ? zoom : null, blankToNull(text(input, "cible")), reason);
        return new Parsed(command, null, dropped, appuiUsed);
    }

    private static Scope scope(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String category = text(node, "categorie");
        String type = text(node, "type");
        return new Scope(blankToNull(text(node, "sujet")), integer(node, "chantier"),
                JarvisFastBrainPrompt.CATEGORIES.contains(category) ? blankToNull(category) : null,
                JarvisFastBrainPrompt.TYPES.contains(type) ? blankToNull(type) : null,
                blankToNull(text(node, "serie")), blankToNull(text(node, "depuis")));
    }

    private static Integer integer(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        return node != null && node.isIntegralNumber() ? node.asInt() : null;
    }

    private static String text(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        String value = node.asText("").strip();
        return value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
