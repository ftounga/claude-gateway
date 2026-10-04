package fr.claudegateway.governance.map.index;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import fr.claudegateway.governance.map.HostMapFileRepository;

/**
 * <b>La tenue de l'index de la carte</b> (F-174 / SF-174-02, D3).
 *
 * <p>Une passe = (1) ré-indexer, par la couche déterministe, les fichiers dont la copie a changé
 * depuis la dernière indexation — c'est le rafraîchissement existant de {@code host_map_files}
 * ({@code HostMapStore}, empreinte) qui les signale ; (2) soumettre au modèle quelques sections en
 * attente. Les cartes déjà en base n'ont jamais été indexées : elles le sont par les premières passes
 * (rétro-remplissage), sans geste particulier.</p>
 *
 * <p><b>Borné</b> à chaque passe (fichiers, sections), <b>éteint</b> par le coupe-circuit D4, et
 * <b>jamais bloquant</b> : appelé par un travailleur planifié, hors de tout tour.</p>
 */
@Service
public class HostMapIndexService {

    private static final Logger log = LoggerFactory.getLogger(HostMapIndexService.class);

    private final HostMapIndexProperties properties;
    private final HostMapFileRepository files;
    private final HostMapSectionRepository sections;
    private final HostMapFactRepository facts;
    private final HostMapIndexer indexer;
    private final HostMapSectionExtractor extractor;
    /** Les embeddings des faits (SF-174-03) : éteint sans clé. */
    private final HostMapSemantic semantic;

    public HostMapIndexService(HostMapIndexProperties properties, HostMapFileRepository files,
            HostMapSectionRepository sections, HostMapFactRepository facts, HostMapIndexer indexer,
            HostMapSectionExtractor extractor, HostMapSemantic semantic) {
        this.properties = properties;
        this.files = files;
        this.sections = sections;
        this.facts = facts;
        this.indexer = indexer;
        this.extractor = extractor;
        this.semantic = semantic;
    }

    /** Ce qu'une passe a fait. */
    public record RunOutcome(int filesIndexed, int sectionsExtracted, int sectionsFailed) {
    }

    /** Une passe. Ne lève jamais. */
    public RunOutcome runOnce() {
        if (!properties.isEnabled()) {
            return new RunOutcome(0, 0, 0);
        }
        int indexed = 0;
        try {
            for (UUID fileId : files.findStaleIndexIds(PageRequest.of(0, properties.filesPerRun()))) {
                try {
                    indexer.indexFile(fileId);
                    indexed++;
                } catch (RuntimeException ex) {
                    log.warn("Carte : fichier non indexé ({})", ex.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Carte : balayage des fichiers à indexer en échec ({})",
                    ex.getClass().getSimpleName());
        }
        int extracted = 0;
        int failed = 0;
        try {
            List<HostMapSection> pending = sections.findByStatusOrderByCreatedAtAsc(
                    HostMapSection.PENDING, PageRequest.of(0, properties.sectionsPerRun()));
            for (HostMapSection section : pending) {
                List<HostMapSectionSplitter.FactLine> lines = facts
                        .findBySectionIdOrderByLineNoAsc(section.getId()).stream()
                        .map(fact -> new HostMapSectionSplitter.FactLine(fact.getLineNo(), fact.getText()))
                        .toList();
                HostMapSectionExtractor.Result result = extractor.extract(section.getUserId(),
                        section.getPath(), section.getHeading(), lines);
                if (result.providerUnavailable()) {
                    break; // Pas de clé : rien ne passera dans cette passe, on n'use pas d'essai.
                }
                if (indexer.applyExtraction(section.getId(), section.getFingerprint(), result)) {
                    extracted++;
                } else if (!result.ok() && section.getAttempts() + 1 >= properties.maxAttempts()) {
                    indexer.markFailed(section.getId());
                    failed++;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Carte : extraction des sections en échec ({})", ex.getClass().getSimpleName());
        }
        // 3. Les embeddings des faits (SF-174-03, D5) : sans clé, rien.
        int embedded = semantic == null ? 0 : semantic.embedPending(properties.embeddingsPerRun());
        if (indexed > 0 || extracted > 0 || failed > 0 || embedded > 0) {
            log.info("Carte : index tenu — fichiers={} sections_lues={} sections_en_echec={} faits_vectorises={}",
                    indexed, extracted, failed, embedded);
        }
        return new RunOutcome(indexed, extracted, failed);
    }
}
