package fr.claudegateway.governance.juge;

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
 * Ce que le juge a <b>déjà regardé</b> (F-94 / SF-94-03) — pour ne pas reposer deux fois la même
 * question.
 *
 * <p>Un tour refusé repart : le modèle rejoue sa réponse, et la fin de tour est contrôlée à nouveau
 * — jusqu'à trois fois (F-50). Sans mémoire, le même jeu d'écritures partirait trois fois chez le
 * fournisseur pour trois fois la même réponse. Cette mémoire est donc une <b>économie</b>, et rien
 * d'autre.</p>
 *
 * <p><b>Ce n'est pas une garantie, et c'est assumé.</b> Elle vit en mémoire du processus : un autre
 * pod repose la question, un redémarrage l'oublie. Au pire, cela coûte un appel — alors qu'en faire
 * une table aurait ajouté une écriture à chaque tour pour économiser un appel qui, lui, n'arrive que
 * sur les tours qui écrivent.</p>
 *
 * <p><b>Bornée deux fois</b> : en nombre d'entrées (LRU) et en durée de vie. Un cache de gouvernance
 * qui grossirait sans fin finirait par coûter plus que ce qu'il économise.</p>
 *
 * <p><b>Isolation.</b> La clé est le couple {@code (userId, workspaceId)} — jamais le seul projet.
 * Une entrée d'un utilisateur ne répond donc jamais pour un autre.</p>
 */
@Component
public class JugeMemo {

    /** Entrées retenues. Au-delà, la moins récemment utilisée sort. */
    public static final int MAX_ENTRIES = 500;

    /** Durée de vie d'une entrée. Au-delà, la question se repose — la machine a pu changer. */
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

    /**
     * Vrai si ces écritures ont <b>déjà</b> été soumises au juge pour ce projet.
     *
     * <p>La comparaison est une <b>inclusion</b>, pas une égalité : si le tour n'a rien écrit de
     * nouveau depuis la dernière question, il n'y a rien de nouveau à demander.</p>
     */
    public synchronized boolean dejaJuge(UUID userId, UUID workspaceId, Collection<String> paths) {
        if (userId == null || workspaceId == null || paths == null || paths.isEmpty()) {
            return false;
        }
        Entry entry = entries.get(new Key(userId, workspaceId));
        if (entry == null || expired(entry)) {
            return false;
        }
        return entry.paths().containsAll(paths);
    }

    /** Retient que ces écritures ont été soumises. */
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
