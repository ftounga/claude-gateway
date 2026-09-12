package fr.claudegateway.atelier.live;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>Un tour vivant</b> et son tampon ordonné (F-84 / SF-84-01).
 *
 * <p>C'est le renversement de F-84 : les événements du tour vont <b>d'abord dans ce tampon</b>, et
 * l'émetteur SSE n'en est qu'un <b>consommateur</b>. Un envoi qui échoue détache ce consommateur ;
 * il n'interrompt rien. Le tour ne se termine plus que parce qu'il a fini, parce qu'il a atteint son
 * plafond, ou parce que l'utilisateur l'a <b>interrompu</b> explicitement (F-32 / SF-38-07).</p>
 *
 * <h2>Bornes de mémoire</h2>
 *
 * <p>Un tampon qui grossit sans fin sur un tour de trente étapes est une fuite. Il est donc
 * <b>doublement borné</b> : {@value #MAX_EVENTS} événements et {@value #MAX_CHARS} caractères de
 * charge utile. Au dépassement, les <b>plus anciens</b> sont retirés et {@link #droppedThrough()}
 * retient jusqu'où. Ce qui est perdu est <b>dit</b>, jamais deviné : un spectateur qui demande un
 * rejeu depuis un curseur antérieur reçoit d'abord un événement {@code truncated}. Une sortie de
 * commande volumineuse fait donc défiler le tampon — c'est exactement ce qu'un terminal fait de
 * toute façon, et le fil persisté ({@code atelier_messages}) reste, lui, complet.</p>
 *
 * <h2>Ordre</h2>
 *
 * <p>Publication et branchement sont sérialisés par un verrou propre au tour : un spectateur qui
 * arrive pendant qu'un événement part reçoit soit tout le rejeu puis la suite, soit le rejeu incluant
 * cet événement — jamais un doublon, jamais un trou. C'est ce qui permet à <b>deux vues de recevoir
 * la même suite</b>.</p>
 */
public final class LiveTurn {

    /** Nombre maximal d'événements conservés pour le rejeu. */
    public static final int MAX_EVENTS = 500;

    /** Poids maximal du tampon, en caractères de JSON. */
    public static final int MAX_CHARS = 512_000;

    /** Nom de l'événement qui dit qu'un rejeu commence après un trou assumé. */
    public static final String TRUNCATED = "truncated";

    /** Curseur d'un spectateur qui n'a rien vu : il reçoit tout ce que le tampon conserve encore. */
    public static final long FROM_START = 0L;

    private static final Logger log = LoggerFactory.getLogger(LiveTurn.class);

    private final UUID turnId = UUID.randomUUID();
    private final UUID userId;
    private final UUID workspaceId;
    private final long startedAtMs = System.currentTimeMillis();
    private final ObjectMapper objectMapper;

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<TurnEvent> buffer = new ArrayDeque<>();
    private final List<TurnSubscriber> subscribers = new ArrayList<>();

    private long lastSeq;
    private long droppedThrough;
    private int bufferedChars;
    private boolean finished;

    LiveTurn(UUID userId, UUID workspaceId, ObjectMapper objectMapper) {
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.objectMapper = objectMapper;
    }

    public UUID turnId() {
        return turnId;
    }

    public UUID userId() {
        return userId;
    }

    public UUID workspaceId() {
        return workspaceId;
    }

    /** Instant d'ouverture du tour, en millisecondes depuis l'époque. */
    public long startedAtMs() {
        return startedAtMs;
    }

    /** Numéro du dernier événement publié — le curseur d'un spectateur à jour. */
    public long cursor() {
        lock.lock();
        try {
            return lastSeq;
        } finally {
            lock.unlock();
        }
    }

    /** Numéro du dernier événement <b>retiré</b> du tampon ; {@code 0} tant que rien n'a été retiré. */
    public long droppedThrough() {
        lock.lock();
        try {
            return droppedThrough;
        } finally {
            lock.unlock();
        }
    }

    /** Vrai tant que le tour n'est pas terminé. */
    public boolean live() {
        lock.lock();
        try {
            return !finished;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Publie un événement : il est numéroté, conservé, puis poussé à chaque spectateur. Un
     * spectateur en échec est <b>détaché</b>, jamais une cause d'arrêt.
     *
     * @return le numéro attribué, ou le curseur inchangé si la charge utile n'a pas pu être
     *         sérialisée (un événement illisible ne fait jamais échouer un tour)
     */
    public long publish(String name, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            // Jamais la charge utile dans le journal : elle peut porter un contenu de fichier.
            log.warn("Événement de tour non sérialisable (événement={}) : ignoré", name);
            return cursor();
        }
        return publishJson(name, json);
    }

    /** Publie une charge utile déjà sérialisée — le chemin du relais entre pods (SF-84-02). */
    public long publishJson(String name, String json) {
        lock.lock();
        try {
            TurnEvent event = new TurnEvent(++lastSeq, name, json);
            buffer.addLast(event);
            bufferedChars += event.weight();
            trim();
            fanOut(event);
            return event.seq();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Branche un spectateur : il reçoit <b>d'abord</b> ce qu'il a manqué depuis {@code cursor}, puis
     * le direct — sans doublon ni trou (SF-84-02).
     *
     * <p>Si le curseur demandé est antérieur à ce que le tampon conserve encore, un événement
     * {@code truncated} le dit avant le rejeu : mieux vaut annoncer un trou que le maquiller.</p>
     *
     * @param cursor dernier numéro déjà vu ({@code 0} = tout ce qui reste)
     * @return {@code false} si le spectateur est parti pendant le rejeu (il n'est alors pas branché),
     *         ou si le tour est déjà terminé
     */
    public boolean attach(TurnSubscriber subscriber, long cursor) {
        lock.lock();
        try {
            if (finished) {
                return false;
            }
            if (cursor < droppedThrough && !deliverTruncated(subscriber, cursor)) {
                return false;
            }
            for (TurnEvent event : buffer) {
                if (event.seq() > cursor && !subscriber.deliver(event)) {
                    return false;
                }
            }
            subscribers.add(subscriber);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Détache un spectateur sans rien changer au tour — le geste explicite d'une vue qui part. */
    public void detach(TurnSubscriber subscriber) {
        lock.lock();
        try {
            subscribers.remove(subscriber);
        } finally {
            lock.unlock();
        }
    }

    /** Nombre de spectateurs branchés — mesure de test et de journal, jamais une décision métier. */
    public int subscriberCount() {
        lock.lock();
        try {
            return subscribers.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clôt le tour : plus rien ne sera publié, tous les spectateurs sont prévenus puis oubliés.
     * Idempotent.
     */
    public void finish() {
        List<TurnSubscriber> toFinish;
        lock.lock();
        try {
            if (finished) {
                return;
            }
            finished = true;
            toFinish = List.copyOf(subscribers);
            subscribers.clear();
        } finally {
            lock.unlock();
        }
        for (TurnSubscriber subscriber : toFinish) {
            subscriber.finish();
        }
    }

    // ------------------------------------------------------------------ interne

    /** Pousse l'événement à tous les spectateurs ; ceux qui échouent sont retirés de la liste. */
    private void fanOut(TurnEvent event) {
        subscribers.removeIf(subscriber -> !subscriber.deliver(event));
    }

    /** Dit à un spectateur que son rejeu commence après un trou, et jusqu'où. */
    private boolean deliverTruncated(TurnSubscriber subscriber, long cursor) {
        String json = "{\"fromSeq\":" + cursor + ",\"droppedThrough\":" + droppedThrough + "}";
        return subscriber.deliver(new TurnEvent(droppedThrough, TRUNCATED, json));
    }

    /** Ramène le tampon sous ses deux bornes, en retirant toujours les plus anciens. */
    private void trim() {
        while (!buffer.isEmpty() && (buffer.size() > MAX_EVENTS || bufferedChars > MAX_CHARS)) {
            TurnEvent dropped = buffer.removeFirst();
            bufferedChars -= dropped.weight();
            droppedThrough = dropped.seq();
        }
    }
}
