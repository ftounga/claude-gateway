package fr.claudegateway.atelier.live;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * existait déjà de fait — un projet n'exécute qu'un tour à la fois — rendue explicite. Depuis
 * F-84 / SF-84-06, un <b>envoi</b> sur un tour vivant ne l'ouvre plus : il y devient une précision
 * ({@link #openOrSteer}). Seul un tour déjà scellé est encore remplacé.</p>
 */
@Component
public class LiveTurnRegistry {

    private static final Logger log = LoggerFactory.getLogger(LiveTurnRegistry.class);

    private final Map<String, LiveTurn> turns = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public LiveTurnRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Ce qu'est devenu un envoi (F-84 / SF-84-06) : une précision déposée dans le tour vivant
     * ({@code receipt} acceptée), un refus de ce tour ({@code receipt} pleine), ou un tour neuf
     * ({@code receipt} nulle).
     */
    public record Entry(LiveTurn turn, SteerReceipt receipt) {

        /** Vrai si l'envoi est devenu une précision du tour vivant. */
        public boolean steered() {
            return receipt != null && receipt.accepted();
        }
    }

    /**
     * <b>Un envoi sur un projet dont le tour tourne devient une précision</b> (F-84 / SF-84-06,
     * décision du PO du 2026-09-13).
     *
     * <p>Tour vivant et non scellé ⇒ la précision y est déposée, et <b>aucun</b> tour n'est ouvert —
     * même quand la file est pleine : un refus vaut mieux que deux boucles sur le même projet.
     * Sinon ⇒ un tour neuf. Le choix est <b>atomique</b> : deux envois simultanés ne peuvent pas
     * ouvrir deux tours.</p>
     *
     * <p>Isolation : la recherche se fait par {@code (userId, workspaceId)} — l'envoi d'un autre
     * utilisateur ne tombe jamais dans ce tour.</p>
     */
    public synchronized Entry openOrSteer(UUID userId, UUID workspaceId, String message) {
        LiveTurn current = turns.get(key(userId, workspaceId));
        if (current != null && current.live()) {
            SteerReceipt receipt = current.offerSteer(message);
            if (receipt.status() != SteerReceipt.Status.ENDED) {
                return new Entry(current, receipt);
            }
        }
        return new Entry(open(userId, workspaceId), null);
    }

    /** Ouvre un tour pour ce projet, en fermant celui qui tournait encore. */
    public synchronized LiveTurn open(UUID userId, UUID workspaceId) {
        LiveTurn turn = new LiveTurn(userId, workspaceId, objectMapper);
        LiveTurn previous = turns.put(key(userId, workspaceId), turn);
        if (previous != null) {
            // Un tour SCELLÉ a déjà rendu sa réponse (SF-84-06) : il ne reste que sa clôture, le
            // remplacer n'abandonne rien.
            if (previous.live() && !previous.sealed()) {
                // Constaté le 2026-09-13 (F-84 / SF-84-04) : un écran qui n'a pas vu le tour en cours
                // — flux retenu par un proxy — a laissé renvoyer la demande. Le tour précédent perd
                // ses spectateurs et devient introuvable, mais sa boucle continue jusqu'à sa fin.
                // Rien ne le disait ; désormais le journal le dit.
                log.warn("Tour d'atelier remplacé par un nouvel envoi alors qu'il tournait encore "
                        + "(workspace={}, tour={}, événements={}) : il n'est plus suivi",
                        workspaceId, previous.turnId(), previous.cursor());
            }
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
     * Les tours vivants <b>de cet utilisateur</b> sur ce pod (F-112 / SF-112-05). Sert à lister les
     * autorisations en attente côté MCP ; l'isolation {@code user_id} filtre à la source. La liste
     * ne couvre que ce pod — un tour vivant ailleurs n'y figure pas (dégradation assumée, comme le
     * rejeu multi-pod).
     */
    public java.util.List<LiveTurn> liveTurnsOf(UUID userId) {
        if (userId == null) {
            return java.util.List.of();
        }
        return turns.values().stream()
                .filter(turn -> userId.equals(turn.userId()) && turn.live())
                .toList();
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
