package fr.claudegateway.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Filtre de sortie des outils MCP (cadrage F-112 §6.4) : masque les <b>secrets reconnus</b> avant
 * qu'un contenu ne quitte la gateway vers le fournisseur de l'IA connectée. « Les outils ne renvoient
 * ni jeton, ni mot de passe, ni cookie, ni clé. »
 *
 * <p>Même esprit que le filtre du journal : on ne cherche pas à deviner tout secret possible, mais à
 * neutraliser les <b>formes reconnaissables</b> (jetons d'API à préfixe, JWT, clés cloud, jetons
 * personnels de la gateway). Le masquage est appliqué récursivement à tout contenu structuré
 * (chaînes, listes, maps) rendu par un outil.</p>
 *
 * <p>Défense en profondeur : les outils ne <b>doivent</b> déjà pas sélectionner de champ secret ;
 * ce filtre est le dernier rempart si un contenu tiers (terminal, page, message Teams) en charrie un.</p>
 */
@Component
public class McpSecretFilter {

    /** Remplacement appliqué à tout secret reconnu. */
    public static final String MASK = "***masqué***";

    /**
     * Motifs de secrets reconnus. Chaque motif capture une forme dont la seule présence trahit un
     * secret ; le texte ordinaire n'a aucune raison d'y ressembler.
     */
    private static final List<Pattern> SECRETS = List.of(
            // Clés d'API Anthropic / OpenAI et jetons personnels de la gateway.
            Pattern.compile("sk-ant-[A-Za-z0-9._-]{8,}"),
            Pattern.compile("\\bsk-[A-Za-z0-9]{20,}"),
            Pattern.compile("\\bcgmcp_[A-Za-z0-9._-]{8,}"),
            // Jetons GitHub (PAT classiques et fine-grained), GitLab.
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("\\bglpat-[A-Za-z0-9._-]{20,}"),
            // Jetons Slack.
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
            // Clés d'accès AWS.
            Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
            // Google API keys.
            Pattern.compile("\\bAIza[A-Za-z0-9_-]{20,}"),
            // JWT (trois segments base64url séparés par des points).
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}"),
            // En-tête d'autorisation porteur.
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._-]{12,}"),
            // Clé privée PEM (bloc entier).
            Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"));

    /** Masque les secrets reconnus dans une chaîne. {@code null} → {@code null}. */
    public String maskString(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String masked = value;
        for (Pattern secret : SECRETS) {
            masked = secret.matcher(masked).replaceAll(MASK);
        }
        return masked;
    }

    /**
     * Masque les secrets récursivement dans un contenu structuré : les chaînes sont filtrées, les
     * listes et maps parcourues. Les autres types (nombres, booléens) sont rendus tels quels. La
     * structure et l'ordre des maps sont préservés.
     */
    @SuppressWarnings("unchecked")
    public Object mask(Object value) {
        if (value instanceof String s) {
            return maskString(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> masked = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                masked.put(String.valueOf(entry.getKey()), mask(entry.getValue()));
            }
            return masked;
        }
        if (value instanceof List<?> list) {
            List<Object> masked = new ArrayList<>(list.size());
            for (Object item : list) {
                masked.add(mask(item));
            }
            return masked;
        }
        return value;
    }

    /** Variante typée pour un contenu structuré déjà en {@code Map}. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> maskMap(Map<String, Object> structured) {
        return (Map<String, Object>) mask(structured);
    }
}
