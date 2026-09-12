package fr.claudegateway.atelier.live;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Les <b>tours vivants de ce pod</b> (F-84 / SF-84-01).
 *
 * <p>En mémoire, et volontairement : le tour tourne ici, sur le pod qui l'exécute. Aucune écriture
 * d'événement en base sur le chemin chaud — F-76 avait déjà écarté cette voie, et l'architecture
 * confirmée par le PO le 2026-09-12 la maintient écartée (le spectateur arrivé sur un autre pod est
 * <b>relayé</b>, cf. SF-84-02 et ADR-016).</p>
 *
 * <p><b>Isolation</b> : la clef est le couple {@code (userId, workspaceId)}. Un tour n'est donc pas
 * seulement filtré par utilisateur, il est <b>introuvable</b> depuis une autre identité — on ne se
 * rebranche jamais sur le tour d'autrui, et cela tient à la structure de la clef, pas à un test
 * d'égalité qu'on pourrait oublier d'écrire.</p>
 *
 * <p><b>Un tour vivant par projet</b> : ouvrir un tour ferme le précédent. C'est la règle qui
 * existait déjà de fait — un projet n'exécute qu'un tour à la fois — rendue explicite.</p>
 */
@Component
public class LiveTurnRegistry {

    private final Map<String, LiveTurn> turns = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public LiveTurnRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Ouvre un tour pour ce projet, en fermant celui qui tournait encore. */
    public LiveTurn open(UUID userId, UUID workspaceId) {
        LiveTurn turn = new LiveTurn(userId, workspaceId, objectMapper);
        LiveTurn previous = turns.put(key(userId, workspaceId), turn);
        if (previous != null) {
            previous.finish();
        }
        return turn;
    }

    /** Le tour vivant de ce projet <b>pour cet utilisateur</b>, s'il y en a un ici. */
    public Optional<LiveTurn> find(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(turns.get(key(userId, workspaceId))).filter(LiveTurn::live);
    }

    /**
     * Ferme ce tour et le retire du registre. Idempotent, et sans effet si un tour <b>plus récent</b>
     * a déjà pris sa place — on ne ferme jamais le tour d'un autre message.
     */
    public void close(LiveTurn turn) {
        if (turn == null) {
            return;
        }
        turns.remove(key(turn.userId(), turn.workspaceId()), turn);
        turn.finish();
    }

    private static String key(UUID userId, UUID workspaceId) {
        return userId + ":" + workspaceId;
    }
}
