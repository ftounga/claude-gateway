package fr.claudegateway.governance.map.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.governance.map.HostFactLookup;
import fr.claudegateway.governance.map.index.HostMapFactEmbeddingStore.ScoredFact;

/**
 * <b>La recherche hybride sur l'index de la carte</b> (F-174 / SF-174-03, D5).
 *
 * <p>Quatre maillons, dans l'ordre de leur sûreté :</p>
 * <ol>
 *   <li><b>identifiant exact</b> — un compte, un ARN, un hôte cité dans la question et porté tel quel
 *   par un fait ;</li>
 *   <li><b>entité nommée</b> — une ressource de l'index dont le nom ou un identifiant est cité : les
 *   faits qui la mentionnent ;</li>
 *   <li><b>similarité sémantique</b> — pgvector, sous un seuil exigeant (silencieux &gt; bruyant) ;</li>
 *   <li><b>lexical</b> — les termes distinctifs de F-137, sur les faits indexés, en dernier recours.</li>
 * </ol>
 *
 * <p>Un fait retenu par un maillon n'est pas répété par les suivants. Toute lecture porte
 * {@code (user_id, host_id)} : seule la carte du poste du tour est interrogée.</p>
 */
@Service
public class HostMapSearch {

    /** Pourquoi un fait a été retenu. L'ordre est celui de la priorité. */
    public enum Reason {
        IDENTIFIER, ENTITY, SEMANTIC, LEXICAL
    }

    /** Un fait retenu, et pourquoi. */
    public record Hit(HostMapFact fact, Reason reason) {
    }

    /** Ce que la recherche rend : les faits retenus, et les entités que la question touche. */
    public record Result(List<Hit> hits, List<HostMapEntity> touched) {

        public boolean isEmpty() {
            return hits.isEmpty();
        }
    }

    /** Un nom trop court ne désigne rien : « eu », « ops ». */
    static final int MIN_LABEL_LENGTH = 4;

    /**
     * Au-delà, un terme ou un nom décrit la carte, pas la question (même règle que F-137) : on ne
     * remonte pas ses faits.
     */
    static final int MAX_MATCHES_PER_TERM = 8;

    private final HostMapFactRepository facts;
    private final HostMapEntityRepository entities;
    private final HostMapSemantic semantic;
    private final HostMapIndexProperties properties;

    public HostMapSearch(HostMapFactRepository facts, HostMapEntityRepository entities,
            HostMapSemantic semantic, HostMapIndexProperties properties) {
        this.facts = facts;
        this.entities = entities;
        this.semantic = semantic;
        this.properties = properties;
    }

    /** Vrai si l'index de ce poste porte au moins un fait (sinon : repli lexical F-137). */
    @Transactional(readOnly = true)
    public boolean hasIndex(UUID userId, UUID hostId) {
        return userId != null && hostId != null && facts.countByUserIdAndHostId(userId, hostId) > 0;
    }

    /**
     * Les faits de la carte de CE poste qui répondent à la question.
     *
     * @param limit nombre de faits au plus
     */
    @Transactional(readOnly = true)
    public Result search(UUID userId, UUID hostId, String question, int limit) {
        if (userId == null || hostId == null || question == null || question.isBlank() || limit <= 0) {
            return new Result(List.of(), List.of());
        }
        String lowered = question.toLowerCase(Locale.ROOT);
        Map<UUID, Hit> retained = new LinkedHashMap<>();

        // 1. Identifiants exacts cités dans la question.
        Set<String> identifiers = new LinkedHashSet<>();
        HostMapPatterns.identifiers(question).forEach(id -> identifiers.add(id.value().toLowerCase(Locale.ROOT)));
        Set<String> terms = HostFactLookup.distinctiveTerms(question);
        identifiers.addAll(terms); // Un terme distinctif peut être un identifiant que les motifs ignorent.
        for (String identifier : identifiers) {
            // Un identifiant cité est toujours pertinent : on ne l'écarte pas s'il est fréquent, on
            // borne seulement sa part pour laisser la place aux autres identifiants de la question.
            List<HostMapFact> carrying = facts.findByIdentifierPattern(userId, hostId,
                    HostMapLikes.exactIdentifier(identifier));
            add(retained, carrying.subList(0, Math.min(carrying.size(), MAX_MATCHES_PER_TERM)),
                    Reason.IDENTIFIER, limit);
        }

        // 2. Entités nommées : la question cite leur nom ou un de leurs identifiants.
        List<HostMapEntity> touched = touchedEntities(userId, hostId, lowered);
        Set<String> labels = new LinkedHashSet<>();
        touched.forEach(entity -> labels.add(entity.getLabelNorm()));
        for (String label : labels) {
            List<HostMapFact> mentioning = facts.findByTextPattern(userId, hostId, HostMapLikes.contains(label));
            if (mentioning.size() <= MAX_MATCHES_PER_TERM) {
                add(retained, mentioning, Reason.ENTITY, limit);
            }
        }

        // 3. Similarité sémantique, sous le seuil.
        if (retained.size() < limit && semantic.isEnabled()) {
            List<ScoredFact> nearest = semantic.nearest(userId, hostId, question, properties.semanticTopN());
            List<UUID> ids = new ArrayList<>();
            for (ScoredFact scored : nearest) {
                if (scored.distance() <= properties.semanticMaxDistance()) {
                    ids.add(scored.id());
                }
            }
            if (!ids.isEmpty()) {
                Map<UUID, HostMapFact> byId = new HashMap<>();
                facts.findByUserIdAndHostIdAndIdIn(userId, hostId, ids).forEach(f -> byId.put(f.getId(), f));
                List<HostMapFact> ordered = new ArrayList<>();
                ids.forEach(id -> {
                    HostMapFact fact = byId.get(id);
                    if (fact != null) {
                        ordered.add(fact);
                    }
                });
                add(retained, ordered, Reason.SEMANTIC, limit);
            }
        }

        // 4. Lexical (F-137), sur les faits indexés, en dernier recours.
        for (String term : terms) {
            if (retained.size() >= limit) {
                break;
            }
            List<HostMapFact> matches = facts.findByTextPattern(userId, hostId, HostMapLikes.contains(term));
            if (matches.size() <= MAX_MATCHES_PER_TERM) {
                add(retained, matches, Reason.LEXICAL, limit);
            }
        }
        return new Result(new ArrayList<>(retained.values()), touched);
    }

    /** Les entités de CE poste dont la question cite le nom ou un identifiant. */
    List<HostMapEntity> touchedEntities(UUID userId, UUID hostId, String loweredQuestion) {
        List<HostMapEntity> touched = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (HostMapEntity entity : entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(userId, hostId)) {
            if (citedIn(entity, loweredQuestion) && seen.add(entity.getLabelNorm())) {
                touched.add(entity);
            }
        }
        return touched;
    }

    private static boolean citedIn(HostMapEntity entity, String loweredQuestion) {
        String label = entity.getLabelNorm();
        if (label != null && label.length() >= MIN_LABEL_LENGTH && containsWord(loweredQuestion, label)) {
            return true;
        }
        String identifiers = entity.getIdentifiers();
        if (identifiers == null) {
            return false;
        }
        for (String identifier : identifiers.split("\n")) {
            if (identifier.length() >= MIN_LABEL_LENGTH && containsWord(loweredQuestion, identifier)) {
                return true;
            }
        }
        return false;
    }

    /** {@code needle} figure dans {@code text} sans être collé à une lettre ou un chiffre. */
    static boolean containsWord(String text, String needle) {
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            int end = at + needle.length();
            boolean leftOk = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            boolean rightOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static void add(Map<UUID, Hit> retained, List<HostMapFact> found, Reason reason, int limit) {
        for (HostMapFact fact : found) {
            if (retained.size() >= limit) {
                return;
            }
            retained.putIfAbsent(fact.getId(), new Hit(fact, reason));
        }
    }
}
