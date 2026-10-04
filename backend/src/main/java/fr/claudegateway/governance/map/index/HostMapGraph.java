package fr.claudegateway.governance.map.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>Le plan de la carte</b> (F-173 / SF-173-01) : ce que l'écran dessine, calculé sur l'index F-174.
 *
 * <p><b>D1</b> : tout vient de la base ({@code host_map_entities / relations / facts / sections}),
 * jamais d'une lecture du poste — le plan répond poste hors ligne, daté par {@code indexedAt}.
 * Lecture seule, aucun appel au modèle.</p>
 *
 * <p>Une ressource citée dans douze sections est <b>un</b> nœud : les entités sont fusionnées par
 * libellé normalisé, et un identifiant exact (motif) déjà porté par une ressource nommée par le modèle
 * s'y range. L'identifiant d'un nœud est un condensé de son libellé : stable d'une ré-extraction à
 * l'autre, sûr dans une URL ({@code ?noeud=}).</p>
 */
@Service
public class HostMapGraph {

    static final int MAX_NODES = 2000;
    static final int MAX_DEADLINES = 200;
    static final int MAX_TO_MAP = 200;
    static final int MAX_CARD_FACTS = 80;

    private static final Pattern NODE_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern TO_MAP_TEXT = Pattern.compile("[àa]\\s+cartographier", Pattern.CASE_INSENSITIVE);
    private static final String STATE_TO_MAP = "a_cartographier";
    private static final int MIN_LABEL_MATCH = 3;

    /** Un nœud du plan. */
    public record Node(String id, String label, String kind, String parentId, int depth, int children,
            String domain, String environment, String state, List<String> identifiers, int facts,
            int traps, LocalDate observedOn, boolean stale, LocalDate nextDue, boolean toMap) {
    }

    /** Un lien entre deux nœuds. */
    public record Edge(String id, String source, String target, String nature) {
    }

    /** Une échéance datée. */
    public record Deadline(String nodeId, String nodeLabel, String text, LocalDate dueOn, boolean overdue,
            String path, String heading, int lineNo) {
    }

    /** Une chose que la carte dit encore à cartographier. */
    public record ToMap(String nodeId, String label, String text, String path, String heading, Integer lineNo) {
    }

    /** Le plan. */
    public record View(boolean indexed, OffsetDateTime indexedAt, long pendingSections, int factMaxAgeDays,
            int totalNodes, boolean truncated, List<Node> nodes, List<Edge> edges, List<Deadline> deadlines,
            List<ToMap> toMap) {

        static View empty(int factMaxAgeDays, long pending) {
            return new View(false, null, pending, factMaxAgeDays, 0, false, List.of(), List.of(), List.of(),
                    List.of());
        }
    }

    /** Un fait de la fiche. */
    public record CardFact(String path, String heading, int lineNo, String text, String kind,
            LocalDate observedOn, LocalDate dueOn, boolean stale) {
    }

    /** Une relation de la fiche, vue depuis le nœud. */
    public record CardRelation(String nature, String direction, String otherId, String otherLabel) {
    }

    /** Une source de la fiche : le fichier et la section où la ressource est dite. */
    public record Source(String path, String heading) {
    }

    /** La fiche d'une ressource. */
    public record Card(Node node, int totalFacts, List<CardFact> facts, List<CardRelation> relations,
            List<Source> sources) {
    }

    private final HostMapEntityRepository entities;
    private final HostMapRelationRepository relations;
    private final HostMapFactRepository facts;
    private final HostMapSectionRepository sections;
    private final ObjectMapper objectMapper;
    private final int factMaxAgeDays;

    public HostMapGraph(HostMapEntityRepository entities, HostMapRelationRepository relations,
            HostMapFactRepository facts, HostMapSectionRepository sections, ObjectMapper objectMapper,
            @Value("${app.governance.map.fact-max-age-days:120}") int factMaxAgeDays) {
        this.entities = entities;
        this.relations = relations;
        this.facts = facts;
        this.sections = sections;
        this.objectMapper = objectMapper;
        this.factMaxAgeDays = factMaxAgeDays;
    }

    public int factMaxAgeDays() {
        return factMaxAgeDays;
    }

    /** Le plan de la carte de CE poste. */
    @Transactional(readOnly = true)
    public View graph(UUID userId, UUID hostId, LocalDate today) {
        if (userId == null || hostId == null) {
            return View.empty(factMaxAgeDays, 0);
        }
        Model model = build(userId, hostId, today);
        long pending = sections.countByUserIdAndHostIdAndStatus(userId, hostId, HostMapSection.PENDING);
        if (model.nodes.isEmpty() && model.allFacts.isEmpty()) {
            return View.empty(factMaxAgeDays, pending);
        }

        List<Acc> kept = new ArrayList<>(model.nodes.values());
        boolean truncated = kept.size() > MAX_NODES;
        if (truncated) {
            kept.sort(Comparator.comparingInt((Acc a) -> -(a.degree + a.facts.size())).thenComparing(a -> a.labelNorm));
            kept = new ArrayList<>(kept.subList(0, MAX_NODES));
        }
        Set<String> keptIds = new HashSet<>();
        kept.forEach(a -> keptIds.add(a.id));
        List<Node> nodes = new ArrayList<>();
        for (Acc acc : kept) {
            nodes.add(model.toNode(acc, keptIds, today));
        }
        nodes.sort(Comparator.comparingInt(Node::depth).thenComparing(Node::kind).thenComparing(Node::label));

        List<Edge> edges = new ArrayList<>();
        for (Link link : model.links) {
            if (keptIds.contains(link.source) && keptIds.contains(link.target)) {
                edges.add(new Edge("e" + edges.size(), link.source, link.target, link.nature));
            }
        }

        return new View(true, model.indexedAt, pending, factMaxAgeDays, model.nodes.size(), truncated,
                List.copyOf(nodes), List.copyOf(edges), model.deadlines(today), model.toMap());
    }

    /** La fiche d'une ressource de la carte de CE poste ; vide si elle n'y est pas. */
    @Transactional(readOnly = true)
    public Optional<Card> card(UUID userId, UUID hostId, String nodeId, LocalDate today) {
        if (userId == null || hostId == null || nodeId == null || !NODE_ID.matcher(nodeId).matches()) {
            return Optional.empty();
        }
        Model model = build(userId, hostId, today);
        Acc acc = model.byId.get(nodeId);
        if (acc == null) {
            return Optional.empty();
        }
        Set<String> all = model.byId.keySet();
        Node node = model.toNode(acc, all, today);

        List<HostMapFact> ordered = new ArrayList<>(acc.facts.values());
        ordered.sort(Comparator.comparingInt((HostMapFact f) -> kindRank(f.getKind()))
                .thenComparing(HostMapFact::getPath).thenComparingInt(HostMapFact::getLineNo));
        List<CardFact> cardFacts = new ArrayList<>();
        for (HostMapFact fact : ordered) {
            if (cardFacts.size() >= MAX_CARD_FACTS) {
                break;
            }
            cardFacts.add(new CardFact(fact.getPath(), fact.getHeading(), fact.getLineNo(), fact.getText(),
                    fact.getKind(), fact.getObservedOn(), fact.getDueOn(), isStale(fact.getObservedOn(), today)));
        }

        List<CardRelation> cardRelations = new ArrayList<>();
        for (Link link : model.links) {
            if (link.source.equals(nodeId)) {
                Acc other = model.byId.get(link.target);
                cardRelations.add(new CardRelation(link.nature, "out", other.id, other.label));
            } else if (link.target.equals(nodeId)) {
                Acc other = model.byId.get(link.source);
                cardRelations.add(new CardRelation(link.nature, "in", other.id, other.label));
            }
        }

        List<Source> sources = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (HostMapEntity entity : acc.entities) {
            if (seen.add(entity.getPath() + "\u0000" + entity.getHeading())) {
                sources.add(new Source(entity.getPath(), entity.getHeading()));
            }
        }
        return Optional.of(new Card(node, acc.facts.size(), List.copyOf(cardFacts), List.copyOf(cardRelations),
                List.copyOf(sources)));
    }

    // ------------------------------------------------------------------ construction

    private Model build(UUID userId, UUID hostId, LocalDate today) {
        Model model = new Model();
        List<HostMapEntity> rows = entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(userId, hostId);
        List<HostMapRelation> links = relations.findByUserIdAndHostId(userId, hostId);
        model.allFacts = facts.findByUserIdAndHostIdOrderByPathAscLineNoAsc(userId, hostId);
        for (HostMapSection section : sections.findByUserIdAndHostIdOrderByPathAscOrdinalAsc(userId, hostId)) {
            OffsetDateTime at = section.getExtractedAt() != null ? section.getExtractedAt() : section.getCreatedAt();
            if (at != null && (model.indexedAt == null || at.isAfter(model.indexedAt))) {
                model.indexedAt = at;
            }
        }

        // 1. Les ressources nommées par le modèle d'abord : ce sont elles qui accueillent les motifs.
        Map<String, Acc> byIdentifier = new HashMap<>();
        for (HostMapEntity entity : rows) {
            if (HostMapEntity.MODELE.equals(entity.getOrigin())) {
                Acc acc = model.nodeFor(entity.getLabel(), entity.getLabelNorm());
                acc.add(entity);
                for (String identifier : identifiers(entity.getIdentifiers())) {
                    byIdentifier.putIfAbsent(identifier, acc);
                }
            }
        }
        // 2. Les identifiants exacts : rangés dans la ressource qui les porte, sinon nœuds à part.
        for (HostMapEntity entity : rows) {
            if (HostMapEntity.MODELE.equals(entity.getOrigin())) {
                continue;
            }
            String key = norm(entity.getLabelNorm());
            Acc host = byIdentifier.get(key);
            if (host == null) {
                host = model.byNorm.get(key);
            }
            if (host == null) {
                host = model.nodeFor(entity.getLabel(), entity.getLabelNorm());
            }
            host.add(entity);
        }
        // 3. Les liens : une extrémité inconnue devient une ressource de type « autre ».
        Set<String> seenLinks = new HashSet<>();
        for (HostMapRelation relation : links) {
            Acc from = resolve(model, byIdentifier, relation.getFromLabel());
            Acc to = resolve(model, byIdentifier, relation.getToLabel());
            if (from == null || to == null || from == to) {
                continue;
            }
            String nature = norm(relation.getNature());
            if (seenLinks.add(from.id + ">" + to.id + ">" + nature)) {
                model.links.add(new Link(from.id, to.id, nature));
                from.degree++;
                to.degree++;
            }
        }
        // 4. La hiérarchie : « A dans B », « A heberge B ». Un seul parent, jamais de cycle.
        for (Link link : model.links) {
            if ("dans".equals(link.nature)) {
                model.attach(link.source, link.target);
            } else if ("heberge".equals(link.nature)) {
                model.attach(link.target, link.source);
            }
        }
        // 5. Les faits de chaque ressource.
        Map<UUID, List<HostMapFact>> bySection = new HashMap<>();
        Map<String, List<HostMapFact>> byFactIdentifier = new HashMap<>();
        for (HostMapFact fact : model.allFacts) {
            bySection.computeIfAbsent(fact.getSectionId(), k -> new ArrayList<>()).add(fact);
            for (String identifier : identifiers(fact.getIdentifiers())) {
                byFactIdentifier.computeIfAbsent(identifier, k -> new ArrayList<>()).add(fact);
            }
        }
        for (Acc acc : model.nodes.values()) {
            List<String> needles = new ArrayList<>();
            if (acc.labelNorm.length() >= MIN_LABEL_MATCH) {
                needles.add(acc.labelNorm);
            }
            needles.addAll(acc.identifiers);
            for (UUID sectionId : acc.sectionIds) {
                for (HostMapFact fact : bySection.getOrDefault(sectionId, List.of())) {
                    String text = fact.getText() == null ? "" : fact.getText().toLowerCase(Locale.ROOT);
                    for (String needle : needles) {
                        if (text.contains(needle)) {
                            acc.facts.putIfAbsent(fact.getId(), fact);
                            break;
                        }
                    }
                }
            }
            for (String identifier : acc.identifiers) {
                for (HostMapFact fact : byFactIdentifier.getOrDefault(identifier, List.of())) {
                    acc.facts.putIfAbsent(fact.getId(), fact);
                }
            }
            for (HostMapFact fact : acc.facts.values()) {
                model.nodeOfFact.putIfAbsent(fact.getId(), acc);
            }
        }
        return model;
    }

    private Acc resolve(Model model, Map<String, Acc> byIdentifier, String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String key = norm(label);
        Acc acc = model.byNorm.get(key);
        if (acc == null) {
            acc = byIdentifier.get(key);
        }
        if (acc == null) {
            acc = model.nodeFor(label.strip(), key);
            acc.kinds.merge("autre", 1, Integer::sum);
        }
        return acc;
    }

    private boolean isStale(LocalDate observedOn, LocalDate today) {
        return observedOn != null && today != null && observedOn.isBefore(today.minusDays(factMaxAgeDays));
    }

    private static int kindRank(String kind) {
        if (HostMapFact.PIEGE.equals(kind)) {
            return 0;
        }
        return HostMapFact.ECHEANCE.equals(kind) ? 1 : 2;
    }

    static String norm(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    static String nodeId(String labelNorm) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(labelNorm.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> identifiers(String column) {
        if (column == null || column.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : column.split("\n")) {
            if (!part.isBlank()) {
                out.add(part.strip().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ modèle de travail

    private record Link(String source, String target, String nature) {
    }

    /** Une ressource en cours d'assemblage. */
    private final class Acc {
        final String id;
        final String label;
        final String labelNorm;
        final List<HostMapEntity> entities = new ArrayList<>();
        final Map<String, Integer> kinds = new LinkedHashMap<>();
        final Map<String, Integer> motifKinds = new LinkedHashMap<>();
        final Set<String> identifiers = new LinkedHashSet<>();
        final Set<UUID> sectionIds = new LinkedHashSet<>();
        final Map<UUID, HostMapFact> facts = new LinkedHashMap<>();
        String parentId;
        int degree;
        String domain;
        String environment;
        String state;
        LocalDate stateObservedOn;

        Acc(String label, String labelNorm) {
            this.label = label;
            this.labelNorm = labelNorm;
            this.id = nodeId(labelNorm);
        }

        void add(HostMapEntity entity) {
            entities.add(entity);
            sectionIds.add(entity.getSectionId());
            String kind = norm(entity.getKind());
            if (HostMapEntity.MODELE.equals(entity.getOrigin())) {
                kinds.merge(kind, 1, Integer::sum);
            } else {
                motifKinds.merge(kind, 1, Integer::sum);
            }
            identifiers.addAll(identifiers(entity.getIdentifiers()));
            if (entity.getAttributes() != null) {
                try {
                    JsonNode attributes = objectMapper.readTree(entity.getAttributes());
                    if (domain == null && attributes.hasNonNull("domaine")) {
                        domain = attributes.get("domaine").asText();
                    }
                    if (environment == null && attributes.hasNonNull("environnement")) {
                        environment = attributes.get("environnement").asText();
                    }
                } catch (Exception ignored) {
                    // Des attributs illisibles ne retirent pas la ressource du plan.
                }
            }
            if (entity.getState() != null && !entity.getState().isBlank()) {
                LocalDate observed = entity.getObservedOn();
                if (state == null || (observed != null && (stateObservedOn == null || observed.isAfter(stateObservedOn)))) {
                    state = norm(entity.getState());
                    stateObservedOn = observed;
                }
            }
        }

        String kind() {
            Map<String, Integer> source = kinds.isEmpty() ? motifKinds : kinds;
            return source.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("autre");
        }
    }

    private final class Model {
        final Map<String, Acc> nodes = new LinkedHashMap<>();
        final Map<String, Acc> byNorm = new HashMap<>();
        final Map<String, Acc> byId = new HashMap<>();
        final List<Link> links = new ArrayList<>();
        final Map<UUID, Acc> nodeOfFact = new HashMap<>();
        List<HostMapFact> allFacts = List.of();
        OffsetDateTime indexedAt;

        Acc nodeFor(String label, String labelNorm) {
            String key = norm(labelNorm);
            Acc acc = byNorm.get(key);
            if (acc == null) {
                acc = new Acc(label, key);
                byNorm.put(key, acc);
                byId.put(acc.id, acc);
                nodes.put(acc.id, acc);
            }
            return acc;
        }

        /** Range {@code child} sous {@code parent}, sauf s'il a déjà un parent ou si cela fermerait un cycle. */
        void attach(String child, String parent) {
            Acc node = byId.get(child);
            if (node == null || node.parentId != null) {
                return;
            }
            String cursor = parent;
            int guard = 0;
            while (cursor != null && guard++ <= nodes.size()) {
                if (cursor.equals(child)) {
                    return;
                }
                Acc up = byId.get(cursor);
                cursor = up == null ? null : up.parentId;
            }
            node.parentId = parent;
        }

        int depth(Acc acc, Set<String> kept) {
            int depth = 0;
            String cursor = acc.parentId;
            while (cursor != null && kept.contains(cursor) && depth <= nodes.size()) {
                depth++;
                Acc up = byId.get(cursor);
                cursor = up == null ? null : up.parentId;
            }
            return depth;
        }

        Node toNode(Acc acc, Set<String> kept, LocalDate today) {
            int traps = 0;
            LocalDate observed = null;
            LocalDate nextDue = null;
            for (HostMapEntity entity : acc.entities) {
                observed = later(observed, entity.getObservedOn());
            }
            for (HostMapFact fact : acc.facts.values()) {
                if (HostMapFact.PIEGE.equals(fact.getKind())) {
                    traps++;
                }
                observed = later(observed, fact.getObservedOn());
                if (fact.getDueOn() != null && (nextDue == null || fact.getDueOn().isBefore(nextDue))) {
                    nextDue = fact.getDueOn();
                }
            }
            int children = 0;
            for (Acc other : nodes.values()) {
                if (acc.id.equals(other.parentId) && kept.contains(other.id)) {
                    children++;
                }
            }
            String parentId = acc.parentId != null && kept.contains(acc.parentId) ? acc.parentId : null;
            return new Node(acc.id, acc.label, acc.kind(), parentId, depth(acc, kept), children, acc.domain,
                    acc.environment, acc.state, List.copyOf(acc.identifiers), acc.facts.size(), traps, observed,
                    isStale(observed, today), nextDue, STATE_TO_MAP.equals(acc.state));
        }

        List<Deadline> deadlines(LocalDate today) {
            List<HostMapFact> due = new ArrayList<>();
            for (HostMapFact fact : allFacts) {
                if (fact.getDueOn() != null) {
                    due.add(fact);
                }
            }
            due.sort(Comparator.comparing(HostMapFact::getDueOn).thenComparing(HostMapFact::getPath)
                    .thenComparingInt(HostMapFact::getLineNo));
            List<Deadline> out = new ArrayList<>();
            for (HostMapFact fact : due) {
                if (out.size() >= MAX_DEADLINES) {
                    break;
                }
                Acc acc = nodeOfFact.get(fact.getId());
                out.add(new Deadline(acc == null ? null : acc.id, acc == null ? null : acc.label, fact.getText(),
                        fact.getDueOn(), today != null && fact.getDueOn().isBefore(today), fact.getPath(),
                        fact.getHeading(), fact.getLineNo()));
            }
            return List.copyOf(out);
        }

        List<ToMap> toMap() {
            List<ToMap> out = new ArrayList<>();
            for (Acc acc : nodes.values()) {
                if (out.size() >= MAX_TO_MAP) {
                    return List.copyOf(out);
                }
                if (STATE_TO_MAP.equals(acc.state)) {
                    HostMapEntity first = acc.entities.isEmpty() ? null : acc.entities.get(0);
                    out.add(new ToMap(acc.id, acc.label, null, first == null ? null : first.getPath(),
                            first == null ? null : first.getHeading(), null));
                }
            }
            for (HostMapFact fact : allFacts) {
                if (out.size() >= MAX_TO_MAP) {
                    break;
                }
                if (fact.getText() != null && TO_MAP_TEXT.matcher(fact.getText()).find()) {
                    Acc acc = nodeOfFact.get(fact.getId());
                    out.add(new ToMap(acc == null ? null : acc.id, acc == null ? null : acc.label, fact.getText(),
                            fact.getPath(), fact.getHeading(), fact.getLineNo()));
                }
            }
            return List.copyOf(out);
        }
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        return b != null && b.isAfter(a) ? b : a;
    }
}
