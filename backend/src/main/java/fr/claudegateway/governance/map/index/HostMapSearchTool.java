package fr.claudegateway.governance.map.index;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.governance.map.HostMapFactsBlock;

/**
 * <b>L'outil {@code carte_chercher}</b>, côté gateway (F-174 / SF-174-05, D8).
 *
 * <p>Il répond sur l'index et la copie {@code host_map_files} déjà rangés : <b>aucun aller-retour
 * vers le poste</b>. Il rend des ressources (type, identifiants, état), leurs liens, et des faits
 * sourcés {@code [fichier § section]} avec leurs marques (piège, échéance). Rien n'est retiré à
 * l'agent : {@code read_file} et {@code bash} restent permis — l'outil lui évite seulement de fouiller
 * 480 Ko de texte pour retrouver une ligne.</p>
 */
@Service
public class HostMapSearchTool {

    /** Faits rendus au plus. */
    static final int MAX_FACTS = 20;
    /** Ressources listées au plus. */
    static final int MAX_ENTITIES = 15;
    /** Liens listés au plus. */
    static final int MAX_RELATIONS = 15;
    /** Borne de la réponse entière. */
    static final int MAX_CHARS = 8_000;

    static final String FOOTER =
            "(La carte est un pointeur : avant d'affirmer un fait critique, revérifie-le sur le poste.)\n";

    private final HostMapSearch search;
    private final HostMapFactRepository facts;
    private final HostMapEntityRepository entities;
    private final HostMapRelationRepository relations;

    public HostMapSearchTool(HostMapSearch search, HostMapFactRepository facts,
            HostMapEntityRepository entities, HostMapRelationRepository relations) {
        this.search = search;
        this.facts = facts;
        this.entities = entities;
        this.relations = relations;
    }

    /**
     * Répond à une recherche sur la carte de CE poste, ou {@code null} si l'index n'a rien pour ce poste
     * (l'appelant retombe alors sur la recherche lexicale dans les fichiers).
     *
     * @param query      requête libre (peut être vide si {@code type} ou {@code identifier} est donné)
     * @param type       type de ressource à lister (« compte_aws », « cluster »…), facultatif
     * @param identifier identifiant exact, facultatif
     */
    @Transactional(readOnly = true)
    public String run(UUID userId, UUID hostId, String query, String type, String identifier,
            LocalDate today) {
        if (userId == null || hostId == null || !search.hasIndex(userId, hostId)) {
            return null;
        }
        String question = String.join(" ", nonBlank(query), nonBlank(identifier)).strip();
        Map<UUID, HostMapSearch.Hit> hits = new LinkedHashMap<>();
        if (!nonBlank(identifier).isEmpty()) {
            for (HostMapFact fact : facts.findByIdentifierPattern(userId, hostId,
                    HostMapLikes.exactIdentifier(identifier.strip()))) {
                if (hits.size() < MAX_FACTS) {
                    hits.putIfAbsent(fact.getId(), new HostMapSearch.Hit(fact, HostMapSearch.Reason.IDENTIFIER));
                }
            }
        }
        List<HostMapEntity> listed = new ArrayList<>();
        if (!question.isEmpty()) {
            HostMapSearch.Result result = search.search(userId, hostId, question, MAX_FACTS, today);
            result.hits().forEach(hit -> {
                if (hits.size() < MAX_FACTS) {
                    hits.putIfAbsent(hit.fact().getId(), hit);
                }
            });
            listed.addAll(result.touched());
        }
        String kind = nonBlank(type).toLowerCase(Locale.ROOT);
        if (!kind.isEmpty()) {
            List<HostMapEntity> ofKind = entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(userId, hostId)
                    .stream().filter(e -> kind.equals(e.getKind())).toList();
            listed.removeIf(e -> !kind.equals(e.getKind()));
            listed.addAll(ofKind);
            if (question.isEmpty()) {
                // Sans requête : les faits qui nomment ces ressources.
                for (HostMapEntity entity : ofKind) {
                    if (hits.size() >= MAX_FACTS) {
                        break;
                    }
                    for (HostMapFact fact : facts.findByTextPattern(userId, hostId,
                            HostMapLikes.contains(entity.getLabelNorm()))) {
                        if (hits.size() >= MAX_FACTS) {
                            break;
                        }
                        hits.putIfAbsent(fact.getId(), new HostMapSearch.Hit(fact, HostMapSearch.Reason.ENTITY));
                    }
                }
            }
        }
        return render(question.isEmpty() ? kind : question, merge(listed), relationsOf(userId, hostId, listed),
                new ArrayList<>(hits.values()), today);
    }

    /** Une ressource par nom, identifiants réunis, sources réunies. */
    private static List<HostMapEntity> merge(List<HostMapEntity> listed) {
        Map<String, HostMapEntity> byLabel = new LinkedHashMap<>();
        for (HostMapEntity entity : listed) {
            HostMapEntity known = byLabel.get(entity.getLabelNorm());
            if (known == null) {
                byLabel.put(entity.getLabelNorm(), HostMapEntity.builder().kind(entity.getKind())
                        .label(entity.getLabel()).labelNorm(entity.getLabelNorm())
                        .identifiers(entity.getIdentifiers()).attributes(entity.getAttributes())
                        .state(entity.getState()).path(entity.getPath()).heading(entity.getHeading()).build());
                continue;
            }
            Set<String> ids = new LinkedHashSet<>(split(known.getIdentifiers()));
            ids.addAll(split(entity.getIdentifiers()));
            known.setIdentifiers(HostMapLikes.identifiersColumn(ids));
            if (known.getState() == null) {
                known.setState(entity.getState());
            }
            if (known.getAttributes() == null) {
                known.setAttributes(entity.getAttributes());
            }
            if ("autre".equals(known.getKind()) || known.getKind() == null) {
                known.setKind(entity.getKind());
            }
        }
        return new ArrayList<>(byLabel.values());
    }

    private List<HostMapRelation> relationsOf(UUID userId, UUID hostId, List<HostMapEntity> listed) {
        if (listed.isEmpty()) {
            return List.of();
        }
        Set<String> labels = new LinkedHashSet<>();
        listed.forEach(e -> labels.add(e.getLabelNorm()));
        List<HostMapRelation> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (HostMapRelation relation : relations.findByUserIdAndHostId(userId, hostId)) {
            String from = relation.getFromLabel().toLowerCase(Locale.ROOT);
            String to = relation.getToLabel().toLowerCase(Locale.ROOT);
            if ((labels.contains(from) || labels.contains(to))
                    && seen.add(from + "|" + relation.getNature() + "|" + to)) {
                found.add(relation);
            }
            if (found.size() >= MAX_RELATIONS) {
                break;
            }
        }
        return found;
    }

    static String render(String asked, List<HostMapEntity> listed, List<HostMapRelation> links,
            List<HostMapSearch.Hit> hits, LocalDate today) {
        StringBuilder out = new StringBuilder();
        out.append("Carte de ce poste — « ").append(asked).append(" » : ").append(hits.size())
                .append(" fait(s), ").append(Math.min(listed.size(), MAX_ENTITIES)).append(" ressource(s).\n");
        if (!listed.isEmpty()) {
            out.append("\nRessources :\n");
            for (HostMapEntity entity : listed.subList(0, Math.min(listed.size(), MAX_ENTITIES))) {
                out.append("- ").append(entity.getLabel()).append(" (").append(entity.getKind()).append(')');
                List<String> ids = split(entity.getIdentifiers());
                ids.removeIf(id -> id.equals(entity.getLabelNorm()));
                if (!ids.isEmpty()) {
                    out.append(" — identifiants : ").append(String.join(", ", ids));
                }
                if (entity.getState() != null) {
                    out.append(" — état : ").append(entity.getState());
                }
                if (entity.getAttributes() != null) {
                    out.append(" — ").append(entity.getAttributes());
                }
                out.append("  [").append(entity.getPath());
                if (entity.getHeading() != null && !entity.getHeading().isBlank()) {
                    out.append(" § ").append(entity.getHeading());
                }
                out.append("]\n");
            }
        }
        if (!links.isEmpty()) {
            out.append("\nLiens :\n");
            for (HostMapRelation relation : links) {
                out.append("- ").append(relation.getFromLabel()).append(" —").append(relation.getNature())
                        .append("→ ").append(relation.getToLabel()).append("  [").append(relation.getPath())
                        .append("]\n");
            }
        }
        if (hits.isEmpty()) {
            out.append("\nAucun fait de la carte ne répond. Ce n'est pas une preuve d'absence : la carte peut "
                    + "être incomplète — vérifie sur le poste si la question l'exige.\n");
        } else {
            out.append("\nFaits :\n");
            for (HostMapSearch.Hit hit : hits) {
                String line = HostMapFactsBlock.line(hit.fact(), HostMapFactsBlock.marks(hit.fact(), today));
                if (out.length() + line.length() + FOOTER.length() > MAX_CHARS) {
                    out.append("… (réponse tronquée : précise la requête)\n");
                    break;
                }
                out.append(line);
            }
        }
        return out.append(FOOTER).toString();
    }

    private static List<String> split(String column) {
        List<String> values = new ArrayList<>();
        if (column == null) {
            return values;
        }
        for (String value : column.split("\n")) {
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static String nonBlank(String value) {
        return value == null ? "" : value.strip();
    }
}
