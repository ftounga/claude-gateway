package fr.claudegateway.governance.map.index;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ChatMessage;
import fr.claudegateway.ai.ChatRole;
import fr.claudegateway.byok.ByokKeyService;

/**
 * <b>La couche sémantique de l'extraction</b> (F-174 / SF-174-02, D2 b).
 *
 * <p>Comprendre un texte est une capacité de Claude (Provider-First) : la gateway ne fait que poser
 * la question, via l'interface {@link AIProvider} — jamais Anthropic en direct — et <b>vérifier</b> la
 * réponse. Un identifiant rendu par le modèle n'est gardé que s'il figure mot pour mot dans la
 * section ; un numéro de ligne hors de la section est ignoré ; une date illisible aussi.</p>
 *
 * <p><b>Ne lève jamais.</b> Le résultat dit si la lecture a abouti, et ce qu'elle a coûté.</p>
 */
@Component
public class HostMapSectionExtractor {

    private static final Logger log = LoggerFactory.getLogger(HostMapSectionExtractor.class);

    static final String MARKER = "===CARTE===";

    static final String CONSIGNE = """
            Tu lis UNE section d'une carte d'infrastructure tenue par un consultant chez son client \
            (comptes cloud, clusters, dépôts, forges, registres, domaines, proxy, accès, jetons…). \
            Les lignes porteuses de faits sont numérotées « L<n> : ». Ton travail est une LECTURE \
            FIDÈLE : tu n'inventes rien, tu ne complètes rien, tu ne reformules pas les identifiants.

            Rends :
            - "entites" : les ressources nommées dans la section — {"type": "compte_aws" | "cluster" | \
            "depot" | "forge" | "registre" | "domaine" | "hote" | "proxy" | "acces" | "jeton" | \
            "service" | "equipe" | "autre", "libelle": nom court tel qu'écrit, "identifiants": \
            [identifiants EXACTS recopiés du texte : numéro de compte, ARN, nom d'hôte, URL, groupe/projet…], \
            "domaine": domaine métier s'il est dit, "environnement": "prod" | "hors-prod" | "dev" | \
            "recette" | … s'il est dit, "etat": "joignable" | "injoignable" | "actif" | "obsolete" | \
            "a_cartographier" s'il est dit} ;
            - "relations" : les liens dits par le texte — {"de": libellé, "vers": libellé, "nature": \
            "heberge" | "dans" | "accede_a" | "depend_de" | "accorde" | "remplace" | "autre"} ;
            - "pieges" : les numéros des lignes qui décrivent un piège, une erreur à ne pas commettre, \
            une cause réelle d'incident ou un comportement contre-intuitif ;
            - "echeances" : les lignes qui annoncent une date limite (jeton qui périme, certificat qui \
            expire, accès à renouveler) — {"ligne": n, "date": "AAAA-MM-JJ"}.

            Tu DOIS terminer par une ligne contenant exactement :

            ===CARTE===

            suivie d'un seul objet JSON, et rien d'autre après :

            {"entites": [], "relations": [], "pieges": [], "echeances": []}
            """;

    /** Une entité lue par le modèle, déjà vérifiée. */
    public record ModelEntity(String kind, String label, List<String> identifiers, String domain,
            String environment, String state) {
    }

    /** Une relation lue par le modèle. */
    public record ModelRelation(String from, String to, String nature) {
    }

    /** Une échéance lue par le modèle. */
    public record ModelDeadline(int line, LocalDate date) {
    }

    /** Ce que la lecture a rendu. */
    public record Extraction(List<ModelEntity> entities, List<ModelRelation> relations,
            Set<Integer> pitfallLines, List<ModelDeadline> deadlines) {
    }

    /**
     * Le résultat : l'extraction (ou {@code null}), la consommation, et si le fournisseur est
     * indisponible (clé absente : on réessaiera plus tard sans compter d'échec).
     */
    public record Result(Extraction extraction, int inputTokens, int outputTokens, String model,
            boolean providerUnavailable) {

        public boolean ok() {
            return extraction != null;
        }
    }

    private final AIProvider aiProvider;
    private final ByokKeyService byokKeyService;
    private final HostMapIndexProperties properties;
    private final ObjectMapper objectMapper;

    public HostMapSectionExtractor(AIProvider aiProvider, ByokKeyService byokKeyService,
            HostMapIndexProperties properties, ObjectMapper objectMapper) {
        this.aiProvider = aiProvider;
        this.byokKeyService = byokKeyService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Lit une section.
     *
     * @param userId  le propriétaire de la carte (sa clé BYOK s'il en a une, sinon la clé plateforme)
     * @param path    le fichier
     * @param heading le titre de la section
     * @param facts   les lignes porteuses, numérotées comme dans le fichier
     */
    public Result extract(UUID userId, String path, String heading,
            List<HostMapSectionSplitter.FactLine> facts) {
        String model = properties.model();
        try {
            String apiKey = byokKeyService.resolveActiveApiKey(userId).orElse(null);
            String material = material(path, heading, facts, properties.maxSectionChars());
            ChatCompletionResult result = aiProvider.complete(new ChatCompletionRequest(model,
                    List.of(new ChatMessage(ChatRole.USER, material)), List.of(), apiKey, CONSIGNE,
                    properties.maxOutputTokens(), true));
            int in = result == null ? 0 : result.inputTokens();
            int out = result == null ? 0 : result.outputTokens();
            Extraction extraction = parse(result == null ? null : result.content(), facts);
            return new Result(extraction, in, out, model, false);
        } catch (AIProviderUnavailableException ex) {
            return new Result(null, 0, 0, model, true);
        } catch (RuntimeException ex) {
            log.debug("Carte : extraction de section en échec ({})", ex.getClass().getSimpleName());
            return new Result(null, 0, 0, model, false);
        }
    }

    static String material(String path, String heading, List<HostMapSectionSplitter.FactLine> facts,
            int maxChars) {
        StringBuilder text = new StringBuilder();
        text.append("Fichier : ").append(path).append('\n');
        text.append("Section : ").append(heading == null || heading.isBlank() ? "(préambule)" : heading)
                .append("\n\n");
        for (HostMapSectionSplitter.FactLine fact : facts) {
            String line = "L" + fact.lineNo() + " : " + fact.text() + "\n";
            if (text.length() + line.length() > maxChars) {
                text.append("… (section tronquée)\n");
                break;
            }
            text.append(line);
        }
        return text.toString();
    }

    /** Lit et VÉRIFIE la réponse. {@code null} si elle est illisible. */
    Extraction parse(String content, List<HostMapSectionSplitter.FactLine> facts) {
        if (content == null || content.isBlank()) {
            return null;
        }
        int marker = content.lastIndexOf(MARKER);
        String json = marker >= 0 ? content.substring(marker + MARKER.length()) : content;
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json.substring(start, end + 1));
        } catch (Exception ex) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        String haystack = haystack(facts);
        Set<Integer> lines = new LinkedHashSet<>();
        facts.forEach(fact -> lines.add(fact.lineNo()));

        List<ModelEntity> entities = new ArrayList<>();
        for (JsonNode node : root.path("entites")) {
            String label = text(node, "libelle", 300);
            if (label == null) {
                continue;
            }
            List<String> identifiers = new ArrayList<>();
            for (JsonNode id : node.path("identifiants")) {
                String value = id.isTextual() ? id.asText().strip() : null;
                // D2 (a) : un identifiant n'est gardé que s'il est écrit dans la section.
                if (value != null && !value.isEmpty() && value.length() <= 300
                        && haystack.contains(value.toLowerCase(Locale.ROOT))) {
                    identifiers.add(value);
                }
            }
            entities.add(new ModelEntity(orDefault(text(node, "type", 32), "autre"), label,
                    identifiers, text(node, "domaine", 100), text(node, "environnement", 50),
                    text(node, "etat", 32)));
        }
        List<ModelRelation> relations = new ArrayList<>();
        for (JsonNode node : root.path("relations")) {
            String from = text(node, "de", 300);
            String to = text(node, "vers", 300);
            if (from != null && to != null) {
                relations.add(new ModelRelation(from, to, orDefault(text(node, "nature", 100), "autre")));
            }
        }
        Set<Integer> pitfalls = new LinkedHashSet<>();
        for (JsonNode node : root.path("pieges")) {
            if (node.canConvertToInt() && lines.contains(node.asInt())) {
                pitfalls.add(node.asInt());
            }
        }
        List<ModelDeadline> deadlines = new ArrayList<>();
        for (JsonNode node : root.path("echeances")) {
            int line = node.path("ligne").asInt(-1);
            String date = text(node, "date", 10);
            if (!lines.contains(line) || date == null) {
                continue;
            }
            try {
                deadlines.add(new ModelDeadline(line, LocalDate.parse(date)));
            } catch (DateTimeParseException ex) {
                // Date illisible : on n'invente pas d'échéance.
            }
        }
        return new Extraction(entities, relations, pitfalls, deadlines);
    }

    private static String haystack(List<HostMapSectionSplitter.FactLine> facts) {
        StringBuilder text = new StringBuilder();
        facts.forEach(fact -> text.append(fact.text()).append('\n'));
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText().strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
