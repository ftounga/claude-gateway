package fr.claudegateway.diagnostic;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.repoindex.RepoIndexPathRepository;
import fr.claudegateway.atelier.resolution.ResolutionMemoryRepository;
import fr.claudegateway.governance.map.HostMapFileRepository;

/**
 * <b>L'application lit son propre état</b> (F-156 / SF-156-03).
 *
 * <p>Pour les capacités dont le signal est une <b>table</b>, une table <b>vide</b> est la preuve
 * directe que la capacité n'a <b>jamais été alimentée</b> : index du dépôt livré mais jamais amorcé,
 * mémoire de résolutions jamais écrite, carte du poste absente. C'est exactement ce qui nous est
 * arrivé trois fois cette semaine — et cela se constate <b>sans lire une ligne de source</b>.</p>
 *
 * <p><b>Une table inconnue ne vaut pas « dormante »</b> : elle vaut « je ne sais pas ». Conclure par
 * défaut ferait annoncer des capacités dormantes qui tournent très bien.</p>
 */
@Component
public class TableSignalResolver {

    private static final Logger log = LoggerFactory.getLogger(TableSignalResolver.class);

    private final Map<String, Function<UUID, Long>> counters;

    public TableSignalResolver(RepoIndexPathRepository repoIndex,
                               ResolutionMemoryRepository resolutions,
                               HostMapFileRepository hostMaps) {
        this.counters = Map.of(
                "repo_index_paths", repoIndex::countByUserId,
                "resolution_memory", resolutions::countByUserId,
                "host_map_files", hostMaps::countByUserId);
    }

    /**
     * Combien de lignes ce compte a dans cette table.
     *
     * @return vide quand la table est inconnue du résolveur, ou quand le comptage a échoué —
     *         dans les deux cas le verdict sera <b>indéterminé</b>, jamais « dormante »
     */
    public Optional<Long> count(String table, UUID userId) {
        Function<UUID, Long> counter = counters.get(table);
        if (counter == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(counter.apply(userId));
        } catch (RuntimeException e) {
            log.warn("Comptage impossible sur la table « {} »", table, e);
            return Optional.empty();
        }
    }

    /** Les tables que ce résolveur sait compter — utile pour dire ce qu'il ignore. */
    public java.util.Set<String> knownTables() {
        return counters.keySet();
    }
}
