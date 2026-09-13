package fr.claudegateway.governance.integrite;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Ce que l'intégrité a <b>déjà inspecté</b> (F-95 / SF-95-03) — pour ne pas relire la machine deux
 * fois pour le même tour.
 *
 * <p>Un tour refusé repart : le modèle rejoue sa réponse, et la fin de tour est contrôlée à nouveau
 * — jusqu'à trois fois (F-50). Sans mémoire, la même inspection partirait trois fois sur la machine
 * de l'utilisateur, soit jusqu'à soixante-douze allers-retours pour une seule réponse. Cette mémoire
 * est donc une <b>économie</b>, et rien d'autre.</p>
 *
 * <p><b>L'inclusion, pas l'égalité.</b> Si le tour n'a rien écrit de <i>nouveau</i> depuis la
 * dernière inspection, il n'y a rien de nouveau à regarder. Corriger — donc écrire — rend
 * l'inspection suivante, et c'est exactement ce qu'on veut : la correction est vérifiée, la
 * répétition ne l'est pas.</p>
 *
 * <p><b>Pourquoi une classe de plus, et pas {@code JugeMemo}.</b> Les deux ont la même forme et ne
 * mémorisent pas la même question : partager l'entrée ferait taire le juge parce que l'intégrité est
 * passée, et inversement. Une mémoire partagée entre deux contrôles indépendants les rendrait
 * dépendants de leur ordre d'exécution — exactement ce que F-50 évite en donnant à chacun son
 * verdict.</p>
 *
 * <p><b>Ce n'est pas une garantie, et c'est assumé</b> : elle vit en mémoire du processus. Un autre
 * pod réinspecte, un redémarrage oublie. Au pire cela coûte une inspection — alors qu'en faire une
 * table ajouterait une écriture à chaque tour.</p>
 *
 * <p><b>Isolation.</b> La clé est le couple {@code (userId, workspaceId)}, jamais le seul projet.</p>
 */
@Component
public class IntegriteMemo {

    /** Entrées retenues. Au-delà, la moins récemment utilisée sort. */
    public static final int MAX_ENTRIES = 500;

    /** Durée de vie d'une entrée. Au-delà, on réinspecte : la machine a pu changer. */
    public static final Duration TTL = Duration.ofMinutes(30);

    /** Chemins retenus par entrée : même borne que le contexte de F-50 qui les apporte. */
    public static final int MAX_PATHS = 200;

    private record Key(UUID userId, UUID workspaceId) {
    }

    private record Entry(Set<String> paths, Instant seenAt) {
    }

    /** Accès synchronisé : la boucle est appelée depuis plusieurs tours à la fois. */
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    /** Vrai si ces écritures ont <b>déjà</b> été inspectées pour ce projet. */
    public synchronized boolean dejaInspecte(UUID userId, UUID workspaceId,
            Collection<String> paths) {
        if (userId == null || workspaceId == null || paths == null || paths.isEmpty()) {
            return false;
        }
        Entry entry = entries.get(new Key(userId, workspaceId));
        if (entry == null || expired(entry)) {
            return false;
        }
        return entry.paths().containsAll(paths);
    }

    /** Retient que ces écritures ont été inspectées. */
    public synchronized void retenir(UUID userId, UUID workspaceId, Collection<String> paths) {
        if (userId == null || workspaceId == null || paths == null || paths.isEmpty()) {
            return;
        }
        Key key = new Key(userId, workspaceId);
        Entry previous = entries.get(key);
        Set<String> merged = new LinkedHashSet<>();
        if (previous != null && !expired(previous)) {
            merged.addAll(previous.paths());
        }
        for (String path : paths) {
            if (merged.size() >= MAX_PATHS) {
                break;
            }
            merged.add(path);
        }
        entries.put(key, new Entry(Set.copyOf(merged), Instant.now()));
    }

    /** Vide la mémoire — utilisé par les tests, et par rien d'autre. */
    public synchronized void clear() {
        entries.clear();
    }

    private static boolean expired(Entry entry) {
        return entry.seenAt().plus(TTL).isBefore(Instant.now());
    }
}
