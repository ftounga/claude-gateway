package fr.claudegateway.runner.teams;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * <b>Ce qui a le droit de sortir de la page</b> (F-108 / SF-108-03) — la liste blanche des clés
 * métier d'une réponse SharePoint / OneDrive.
 *
 * <p>L'arbitrage du PO fait appeler l'API SharePoint <b>depuis la page</b>, avec la session du
 * navigateur. Tout ce qui authentifie cet appel — cookies, digest de formulaire
 * ({@code X-RequestDigest}), en-têtes — vit et meurt dans la page. Et certaines réponses portent
 * elles-mêmes des secrets : {@code FormDigestValue} dans {@code contextinfo}, une adresse
 * pré-authentifiée {@code @content.downloadUrl} dans l'API {@code v2.1}. <b>Rien de cela ne doit
 * franchir {@code Runtime.evaluate}.</b></p>
 *
 * <p>La garde ne se fait donc pas par intention mais par <b>projection</b> : le script de page ne
 * rend que les clés de {@link #KEEP}, récursivement ; et la relecture Java applique <b>la même
 * liste</b> une seconde fois, au cas où une page modifiée rendrait davantage. Deux verrous, une
 * seule liste — ils ne peuvent pas diverger.</p>
 */
public final class SharePointProjection {

    /**
     * Les clés métier qui peuvent sortir : noms, chemins, tailles, dates, identifiants d'élément,
     * versions, existence, et l'adresse personnelle OneDrive. <b>Pas une de plus.</b>
     */
    public static final List<String> KEEP = List.of("value", "Name", "ServerRelativeUrl", "Length",
            "TimeLastModified", "UniqueId", "UIVersionLabel", "ItemCount", "Exists", "PersonalUrl");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SharePointProjection() {
    }

    /** Applique la liste blanche : tout ce qui n'y est pas disparaît, à toute profondeur. */
    public static JsonNode pick(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(child -> {
                JsonNode picked = pick(child);
                out.add(picked == null ? MAPPER.nullNode() : picked);
            });
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (KEEP.contains(field.getKey())) {
                    JsonNode picked = pick(field.getValue());
                    out.set(field.getKey(), picked == null ? MAPPER.nullNode() : picked);
                }
            }
            return out;
        }
        return node;
    }

    /**
     * Le même filtre, en JavaScript, pour le script de page. Il est <b>engendré</b> depuis
     * {@link #KEEP} : il n'existe pas de seconde liste à tenir à jour.
     */
    static String script() {
        String keys = KEEP.stream().map(key -> TextNode.valueOf(key).toString())
                .collect(Collectors.joining(","));
        return "const KEEP = new Set([" + keys + "]);"
                + " const pick = (v) => Array.isArray(v) ? v.map(pick)"
                + " : (v && typeof v === 'object')"
                + " ? Object.fromEntries(Object.entries(v).filter(([k]) => KEEP.has(k))"
                + ".map(([k, x]) => [k, pick(x)])) : v;";
    }
}
