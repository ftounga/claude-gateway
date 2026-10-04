package fr.claudegateway.governance.map.index;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.governance.map.HostMapFile;
import fr.claudegateway.governance.map.HostMapFileRepository;

/**
 * <b>L'écriture de l'index</b> (F-174 / SF-174-02).
 *
 * <p>Deux gestes, chacun dans sa transaction :</p>
 * <ol>
 *   <li>{@link #indexFile} — la couche <b>déterministe</b> : découpe le fichier en sections, garde
 *   celles dont l'empreinte n'a pas changé, supprime celles qui ont disparu (avec ce qu'on en avait
 *   tiré), crée les nouvelles avec leurs faits et leurs identifiants exacts, en attente de lecture
 *   par le modèle. Puis note la version du fichier que l'index reflète.</li>
 *   <li>{@link #applyExtraction} — range ce que le modèle a lu d'une section, si cette section existe
 *   encore telle qu'il l'a lue.</li>
 * </ol>
 *
 * <p><b>D1</b> : rien n'est jamais écrit sur la carte ni sur le poste ; l'index se reconstruit
 * depuis {@code host_map_files}.</p>
 */
@Service
public class HostMapIndexer {

    private final HostMapFileRepository files;
    private final HostMapSectionRepository sections;
    private final HostMapFactRepository facts;
    private final HostMapEntityRepository entities;
    private final HostMapRelationRepository relations;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HostMapIndexer(HostMapFileRepository files, HostMapSectionRepository sections,
            HostMapFactRepository facts, HostMapEntityRepository entities,
            HostMapRelationRepository relations, ObjectMapper objectMapper, Clock clock) {
        this.files = files;
        this.sections = sections;
        this.facts = facts;
        this.entities = entities;
        this.relations = relations;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Ce qu'une ré-indexation a changé. */
    public record FileOutcome(int kept, int added, int removed) {
    }

    /**
     * Met l'index d'un fichier à jour (couche déterministe). Idempotent : un fichier déjà indexé à
     * cette version ne change rien.
     */
    @Transactional
    public FileOutcome indexFile(UUID fileId) {
        HostMapFile file = files.findById(fileId).orElse(null);
        if (file == null) {
            return new FileOutcome(0, 0, 0);
        }
        UUID userId = file.getUserId();
        UUID hostId = file.getHostId();
        List<HostMapSectionSplitter.Section> fresh = HostMapSectionSplitter.split(file.getContent());

        // Les sections connues, par empreinte (une empreinte peut revenir deux fois dans un fichier).
        Map<String, Deque<HostMapSection>> known = new HashMap<>();
        for (HostMapSection section : sections.findByUserIdAndHostIdAndPathOrderByOrdinalAsc(userId,
                hostId, file.getPath())) {
            known.computeIfAbsent(section.getFingerprint(), k -> new ArrayDeque<>()).add(section);
        }
        int kept = 0;
        int added = 0;
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (HostMapSectionSplitter.Section candidate : fresh) {
            Deque<HostMapSection> same = known.get(candidate.fingerprint());
            HostMapSection existing = same == null ? null : same.pollFirst();
            if (existing != null) {
                // Texte inchangé : rien à ré-extraire, seule la place a pu bouger.
                if (existing.getOrdinal() != candidate.ordinal()) {
                    existing.setOrdinal(candidate.ordinal());
                    sections.save(existing);
                }
                kept++;
                continue;
            }
            createSection(userId, hostId, file.getPath(), candidate, now);
            added++;
        }
        List<UUID> gone = new ArrayList<>();
        known.values().forEach(rest -> rest.forEach(section -> gone.add(section.getId())));
        if (!gone.isEmpty()) {
            facts.deleteBySectionIds(gone);
            entities.deleteBySectionIds(gone);
            relations.deleteBySectionIds(gone);
            sections.deleteAllById(gone);
        }
        file.setIndexedDigest(file.getDigest());
        files.save(file);
        return new FileOutcome(kept, added, gone.size());
    }

    private void createSection(UUID userId, UUID hostId, String path,
            HostMapSectionSplitter.Section candidate, OffsetDateTime now) {
        HostMapSection section = sections.save(HostMapSection.builder()
                .userId(userId).hostId(hostId).path(path)
                .heading(truncate(candidate.heading(), 500))
                .ordinal(candidate.ordinal())
                .fingerprint(candidate.fingerprint())
                .status(candidate.facts().isEmpty() ? HostMapSection.SKIPPED : HostMapSection.PENDING)
                .createdAt(now)
                .build());
        Map<String, HostMapPatterns.Identifier> sectionIdentifiers = new LinkedHashMap<>();
        Map<String, LocalDate> identifierObserved = new HashMap<>();
        for (HostMapSectionSplitter.FactLine line : candidate.facts()) {
            List<HostMapPatterns.Identifier> identifiers = HostMapPatterns.identifiers(line.text());
            LocalDate observed = HostMapPatterns.observedOn(line.text());
            LocalDate due = HostMapPatterns.deadline(line.text());
            String kind = due != null ? HostMapFact.ECHEANCE
                    : HostMapPatterns.isPitfall(line.text()) ? HostMapFact.PIEGE : HostMapFact.FAIT;
            facts.save(HostMapFact.builder()
                    .userId(userId).hostId(hostId).sectionId(section.getId()).path(path)
                    .heading(section.getHeading())
                    .lineNo(line.lineNo())
                    .text(line.text())
                    .kind(kind)
                    .dueOn(due)
                    .observedOn(observed)
                    .identifiers(HostMapLikes.identifiersColumn(
                            identifiers.stream().map(HostMapPatterns.Identifier::value).toList()))
                    .build());
            for (HostMapPatterns.Identifier identifier : identifiers) {
                String key = identifier.value().toLowerCase(Locale.ROOT);
                sectionIdentifiers.putIfAbsent(key, identifier);
                if (observed != null) {
                    identifierObserved.merge(key, observed, (a, b) -> a.isAfter(b) ? a : b);
                }
            }
        }
        for (Map.Entry<String, HostMapPatterns.Identifier> entry : sectionIdentifiers.entrySet()) {
            HostMapPatterns.Identifier identifier = entry.getValue();
            String label = truncate(identifier.value(), 300);
            entities.save(HostMapEntity.builder()
                    .userId(userId).hostId(hostId).sectionId(section.getId()).path(path)
                    .heading(section.getHeading())
                    .kind(identifier.kind())
                    .label(label)
                    .labelNorm(label.toLowerCase(Locale.ROOT))
                    .identifiers(HostMapLikes.identifiersColumn(List.of(identifier.value())))
                    .observedOn(identifierObserved.get(entry.getKey()))
                    .origin(HostMapEntity.MOTIF)
                    .build());
        }
    }

    /**
     * Range la lecture du modèle pour une section, <b>si elle existe encore avec la même empreinte</b>
     * (le fichier a pu changer pendant l'appel : la lecture d'un texte disparu ne se range pas).
     *
     * @return vrai si la lecture a été rangée
     */
    @Transactional
    public boolean applyExtraction(UUID sectionId, String fingerprint,
            HostMapSectionExtractor.Result result) {
        HostMapSection section = sections.findById(sectionId).orElse(null);
        if (section == null || !Objects.equals(section.getFingerprint(), fingerprint)) {
            return false;
        }
        section.setInputTokens(section.getInputTokens() + result.inputTokens());
        section.setOutputTokens(section.getOutputTokens() + result.outputTokens());
        section.setModel(result.model());
        HostMapSectionExtractor.Extraction extraction = result.extraction();
        if (extraction == null) {
            section.setAttempts(section.getAttempts() + 1);
            sections.save(section);
            return false;
        }
        // Une lecture remplace la précédente : on ne garde que les entités par motif.
        entities.deleteBySectionIdAndOrigin(section.getId(), HostMapEntity.MODELE);
        relations.deleteBySectionIds(List.of(section.getId()));

        List<HostMapFact> sectionFacts = facts.findBySectionIdOrderByLineNoAsc(section.getId());
        Map<Integer, HostMapFact> byLine = new HashMap<>();
        sectionFacts.forEach(fact -> byLine.put(fact.getLineNo(), fact));
        for (Integer line : extraction.pitfallLines()) {
            HostMapFact fact = byLine.get(line);
            if (fact != null && HostMapFact.FAIT.equals(fact.getKind())) {
                fact.setKind(HostMapFact.PIEGE);
                facts.save(fact);
            }
        }
        for (HostMapSectionExtractor.ModelDeadline deadline : extraction.deadlines()) {
            HostMapFact fact = byLine.get(deadline.line());
            if (fact != null && fact.getDueOn() == null) {
                fact.setKind(HostMapFact.ECHEANCE);
                fact.setDueOn(deadline.date());
                facts.save(fact);
            }
        }
        LocalDate observed = sectionFacts.stream().map(HostMapFact::getObservedOn)
                .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        for (HostMapSectionExtractor.ModelEntity entity : extraction.entities()) {
            Map<String, String> attributes = new LinkedHashMap<>();
            if (entity.domain() != null) {
                attributes.put("domaine", entity.domain());
            }
            if (entity.environment() != null) {
                attributes.put("environnement", entity.environment());
            }
            entities.save(HostMapEntity.builder()
                    .userId(section.getUserId()).hostId(section.getHostId())
                    .sectionId(section.getId()).path(section.getPath()).heading(section.getHeading())
                    .kind(truncate(entity.kind().toLowerCase(Locale.ROOT), 32))
                    .label(entity.label())
                    .labelNorm(entity.label().toLowerCase(Locale.ROOT))
                    .identifiers(HostMapLikes.identifiersColumn(entity.identifiers()))
                    .attributes(attributes.isEmpty() ? null : json(attributes))
                    .state(entity.state())
                    .observedOn(observed)
                    .origin(HostMapEntity.MODELE)
                    .build());
        }
        for (HostMapSectionExtractor.ModelRelation relation : extraction.relations()) {
            relations.save(HostMapRelation.builder()
                    .userId(section.getUserId()).hostId(section.getHostId())
                    .sectionId(section.getId()).path(section.getPath())
                    .fromLabel(relation.from()).toLabel(relation.to()).nature(relation.nature())
                    .build());
        }
        section.setStatus(HostMapSection.DONE);
        section.setExtractedAt(OffsetDateTime.now(clock));
        sections.save(section);
        return true;
    }

    /** Marque une section en échec définitif : la couche déterministe reste et sert. */
    @Transactional
    public void markFailed(UUID sectionId) {
        sections.findById(sectionId).ifPresent(section -> {
            section.setStatus(HostMapSection.FAILED);
            sections.save(section);
        });
    }

    private String json(Map<String, String> attributes) {
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
