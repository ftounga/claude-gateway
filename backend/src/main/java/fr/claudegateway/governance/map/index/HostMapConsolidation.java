package fr.claudegateway.governance.map.index;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>La carte se tient</b> (F-174 / SF-174-06, D9) : ce qui, dans la carte, mériterait d'être
 * consolidé — <b>proposé, jamais appliqué</b>.
 *
 * <p>Quatre familles, toutes calculées sur l'index, sans appel au modèle :</p>
 * <ul>
 *   <li>{@code DOUBLON} — le même fait écrit plusieurs fois (texte normalisé identique) ;</li>
 *   <li>{@code CONTRADICTION} — deux sections de même titre dans un fichier (« Cause réelle » écrite
 *   deux fois), ou une même ressource dite dans deux états différents ;</li>
 *   <li>{@code PERIME} — des faits dont la date de constat a dépassé l'âge de confiance (F-139) ;</li>
 *   <li>{@code ECHEANCE_DEPASSEE} — une échéance passée qui figure encore comme à venir.</li>
 * </ul>
 *
 * <p><b>D1</b> : rien n'est écrit. Chaque proposition porte la demande à confier à la Forge ; c'est
 * l'utilisateur qui la déclenche (écran F-173), et le tour de la Forge qui modifie la carte, sous ses
 * yeux.</p>
 */
@Service
public class HostMapConsolidation {

    /** Propositions rendues au plus : au-delà, la carte a besoin d'une session, pas d'une liste. */
    static final int MAX_PROPOSALS = 100;

    /** Faits cités par proposition, au plus. */
    static final int MAX_FACTS_PER_PROPOSAL = 12;

    private static final Pattern CONSTAT =
            Pattern.compile("[,;(]?\\s*constat[ée]e?\\s+le\\s+\\d{4}-\\d{2}-\\d{2}\\)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET = Pattern.compile("^[-*+]\\s*(\\[[ xX]\\]\\s*)?");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Un fait cité par une proposition. */
    public record FactRef(String path, String heading, int lineNo, String text) {
    }

    /** Une proposition de consolidation. */
    public record Proposal(String kind, String path, String summary, List<FactRef> facts, String request) {
    }

    /** Ce que la carte gagnerait à consolider. */
    public record View(boolean indexed, int total, List<Proposal> proposals) {
    }

    private final HostMapFactRepository facts;
    private final HostMapSectionRepository sections;
    private final HostMapEntityRepository entities;
    private final int factMaxAgeDays;

    public HostMapConsolidation(HostMapFactRepository facts, HostMapSectionRepository sections,
            HostMapEntityRepository entities,
            @Value("${app.governance.map.fact-max-age-days:120}") int factMaxAgeDays) {
        this.facts = facts;
        this.sections = sections;
        this.entities = entities;
        this.factMaxAgeDays = factMaxAgeDays;
    }

    /** Les propositions pour la carte de CE poste. */
    @Transactional(readOnly = true)
    public View proposals(UUID userId, UUID hostId, LocalDate today) {
        if (userId == null || hostId == null) {
            return new View(false, 0, List.of());
        }
        List<HostMapFact> all = facts.findByUserIdAndHostIdOrderByPathAscLineNoAsc(userId, hostId);
        if (all.isEmpty()) {
            return new View(false, 0, List.of());
        }
        List<Proposal> proposals = new ArrayList<>();
        proposals.addAll(duplicates(all));
        proposals.addAll(sameHeadings(userId, hostId));
        proposals.addAll(conflictingStates(userId, hostId));
        proposals.addAll(stale(all, today));
        proposals.addAll(pastDeadlines(all, today));
        int total = proposals.size();
        return new View(true, total, proposals.size() > MAX_PROPOSALS
                ? List.copyOf(proposals.subList(0, MAX_PROPOSALS)) : List.copyOf(proposals));
    }

    // ------------------------------------------------------------------ familles

    private List<Proposal> duplicates(List<HostMapFact> all) {
        Map<String, List<HostMapFact>> byText = new LinkedHashMap<>();
        for (HostMapFact fact : all) {
            String key = normalize(fact.getText());
            if (key.length() >= 12) { // « - oui », « | x | » ne sont pas des doublons qui comptent.
                byText.computeIfAbsent(key, k -> new ArrayList<>()).add(fact);
            }
        }
        List<Proposal> proposals = new ArrayList<>();
        for (List<HostMapFact> group : byText.values()) {
            if (group.size() < 2) {
                continue;
            }
            proposals.add(new Proposal("DOUBLON", group.get(0).getPath(),
                    "Le même fait est écrit " + group.size() + " fois.", refs(group),
                    "Dans la carte, le fait « " + shorten(group.get(0).getText()) + " » est écrit "
                            + group.size() + " fois (" + places(group) + "). Garde-le une seule fois, "
                            + "au bon endroit, avec sa date de constat la plus récente. Montre-moi le "
                            + "changement avant de l'écrire."));
        }
        return proposals;
    }

    private List<Proposal> sameHeadings(UUID userId, UUID hostId) {
        Map<String, List<HostMapSection>> byHeading = new LinkedHashMap<>();
        for (HostMapSection section : sections.findByUserIdAndHostIdOrderByPathAscOrdinalAsc(userId, hostId)) {
            if (section.getHeading() == null || section.getHeading().isBlank()) {
                continue;
            }
            String key = section.getPath() + "\u0000" + normalize(section.getHeading());
            byHeading.computeIfAbsent(key, k -> new ArrayList<>()).add(section);
        }
        List<Proposal> proposals = new ArrayList<>();
        for (List<HostMapSection> group : byHeading.values()) {
            if (group.size() < 2) {
                continue;
            }
            HostMapSection first = group.get(0);
            List<HostMapFact> cited = new ArrayList<>();
            for (HostMapSection section : group) {
                cited.addAll(facts.findBySectionIdOrderByLineNoAsc(section.getId()));
            }
            proposals.add(new Proposal("CONTRADICTION", first.getPath(),
                    "La section « " + first.getHeading() + " » existe " + group.size()
                            + " fois dans " + first.getPath() + " : elles peuvent se contredire.",
                    refs(cited),
                    "Dans " + first.getPath() + ", la section « " + first.getHeading() + " » existe "
                            + group.size() + " fois. Compare-les, garde ce qui est vrai aujourd'hui en "
                            + "une seule section, et signale-moi toute contradiction avant d'écrire."));
        }
        return proposals;
    }

    private List<Proposal> conflictingStates(UUID userId, UUID hostId) {
        Map<String, Map<String, HostMapEntity>> byLabel = new LinkedHashMap<>();
        for (HostMapEntity entity : entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(userId, hostId)) {
            if (entity.getState() == null || entity.getState().isBlank()) {
                continue;
            }
            byLabel.computeIfAbsent(entity.getLabelNorm(), k -> new LinkedHashMap<>())
                    .putIfAbsent(entity.getState().toLowerCase(Locale.ROOT), entity);
        }
        List<Proposal> proposals = new ArrayList<>();
        for (Map<String, HostMapEntity> states : byLabel.values()) {
            if (states.size() < 2) {
                continue;
            }
            HostMapEntity first = states.values().iterator().next();
            List<FactRef> places = new ArrayList<>();
            states.values().forEach(e -> places.add(new FactRef(e.getPath(), e.getHeading(), 0,
                    e.getLabel() + " : " + e.getState())));
            proposals.add(new Proposal("CONTRADICTION", first.getPath(),
                    "« " + first.getLabel() + " » est dit " + String.join(" et ", states.keySet()) + ".",
                    places,
                    "Dans la carte, « " + first.getLabel() + " » est décrit dans des états différents ("
                            + String.join(", ", states.keySet()) + "). Vérifie l'état réel, puis "
                            + "corrige la carte pour qu'elle n'en dise plus qu'un, daté. Montre-moi le "
                            + "changement avant de l'écrire."));
        }
        return proposals;
    }

    private List<Proposal> stale(List<HostMapFact> all, LocalDate today) {
        if (today == null || factMaxAgeDays <= 0) {
            return List.of();
        }
        Map<String, List<HostMapFact>> bySection = new LinkedHashMap<>();
        for (HostMapFact fact : all) {
            LocalDate observed = fact.getObservedOn();
            if (observed != null && !observed.isAfter(today)
                    && observed.plusDays(factMaxAgeDays).isBefore(today)) {
                bySection.computeIfAbsent(fact.getPath() + " § " + nullToEmpty(fact.getHeading()),
                        k -> new ArrayList<>()).add(fact);
            }
        }
        List<Proposal> proposals = new ArrayList<>();
        for (Map.Entry<String, List<HostMapFact>> entry : bySection.entrySet()) {
            List<HostMapFact> group = entry.getValue();
            proposals.add(new Proposal("PERIME", group.get(0).getPath(),
                    group.size() + " fait(s) constaté(s) il y a plus de " + factMaxAgeDays + " jours.",
                    refs(group),
                    "Dans la carte (" + entry.getKey() + "), " + group.size() + " fait(s) datent de plus "
                            + "de " + factMaxAgeDays + " jours. Re-vérifie-les sur le poste quand c'est "
                            + "possible, mets à jour leur date de constat, et dis-moi lesquels sont "
                            + "devenus faux."));
        }
        return proposals;
    }

    private List<Proposal> pastDeadlines(List<HostMapFact> all, LocalDate today) {
        if (today == null) {
            return List.of();
        }
        List<HostMapFact> past = all.stream()
                .filter(f -> f.getDueOn() != null && f.getDueOn().isBefore(today))
                .sorted(Comparator.comparing(HostMapFact::getDueOn))
                .toList();
        List<Proposal> proposals = new ArrayList<>();
        for (HostMapFact fact : past) {
            proposals.add(new Proposal("ECHEANCE_DEPASSEE", fact.getPath(),
                    "Échéance du " + fact.getDueOn() + " dépassée.", refs(List.of(fact)),
                    "Dans la carte, l'échéance du " + fact.getDueOn() + " est dépassée : « "
                            + shorten(fact.getText()) + " ». Vérifie ce qu'il en est (renouvelé ? "
                            + "expiré ?) et mets la carte à jour."));
        }
        return proposals;
    }

    // ------------------------------------------------------------------ outils

    /** Le texte d'un fait sans ce qui ne le distingue pas : puce, date de constat, casse, espaces. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String bare = BULLET.matcher(text.strip()).replaceFirst("");
        bare = CONSTAT.matcher(bare).replaceAll("");
        bare = SPACES.matcher(bare.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
        while (!bare.isEmpty() && ".,;:".indexOf(bare.charAt(bare.length() - 1)) >= 0) {
            bare = bare.substring(0, bare.length() - 1).strip();
        }
        return bare;
    }

    private static List<FactRef> refs(List<HostMapFact> group) {
        List<FactRef> refs = new ArrayList<>();
        for (HostMapFact fact : group) {
            if (refs.size() >= MAX_FACTS_PER_PROPOSAL) {
                break;
            }
            refs.add(new FactRef(fact.getPath(), fact.getHeading(), fact.getLineNo(), fact.getText()));
        }
        return refs;
    }

    private static String places(List<HostMapFact> group) {
        Set<String> places = new LinkedHashSet<>();
        group.forEach(f -> places.add(f.getPath() + " ligne " + f.getLineNo()));
        return String.join(", ", places);
    }

    private static String shorten(String text) {
        String flat = text == null ? "" : text.strip();
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
