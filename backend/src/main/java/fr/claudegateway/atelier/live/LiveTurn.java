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

    /** Nom de l'événement qui porte une demande d'autorisation (F-38 / SF-38-08). */
    public static final String CONFIRM_REQUEST = "confirm_request";

    /** Nom de l'événement qui porte sa résolution. */
    public static final String CONFIRM_RESOLVED = "confirm_resolved";

    /**
     * Nom de l'<b>aparté</b> qui dit à un spectateur ce que le tour attend <b>à l'instant</b>
     * (F-84 / SF-84-03), avec son temps restant exact. Ce n'est pas un événement du tour : il ne
     * consomme aucun numéro d'ordre et n'entre pas au tampon.
     */
    public static final String CONFIRM_STATE = "confirm_state";

    /** Une précision vient d'être déposée dans le tour (F-84 / SF-84-06). */
    public static final String STEER_QUEUED = "steer_queued";

    /** Une précision a été lue par le modèle, à l'étape dite (F-84 / SF-84-06). */
    public static final String STEER_APPLIED = "steer_applied";

    /** Une précision arrivée pendant la réponse finale ouvre le tour de suite (F-84 / SF-84-06). */
    public static final String STEER_FOLLOWUP = "steer_followup";

    /** Le tour s'est arrêté (interruption, erreur) avec des précisions non lues (F-84 / SF-84-06). */
    public static final String STEERS_DROPPED = "steers_dropped";

    /**
     * Précisions en attente au plus (F-121 / SF-121-11 : 5 → 10). Au-delà, la précision n'est plus
     * refusée : elle est <b>coalescée</b> dans la dernière en attente — c'est le <b>volume</b>
     * ({@value #MAX_PENDING_STEER_CHARS} caractères) qui borne réellement la file, pas le comptage.
     */
    public static final int MAX_PENDING_STEERS = 10;

    /**
     * Volume cumulé des précisions en attente, en caractères (F-121 / SF-121-11). C'est le
     * <b>vrai</b> plafond : ce qui coûte, c'est le texte ajouté à la conversation, pas le nombre de
     * messages. Au-delà, {@link SteerReceipt.Status#FULL} — et là, c'est un nouveau tour qu'il faut.
     */
    public static final int MAX_PENDING_STEER_CHARS = 8_000;

    private static final Logger log = LoggerFactory.getLogger(LiveTurn.class);

    private final UUID turnId = UUID.randomUUID();
    private final UUID userId;
    private final UUID workspaceId;
    private final long startedAtMs = System.currentTimeMillis();
    private final ObjectMapper objectMapper;

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<TurnEvent> buffer = new ArrayDeque<>();
    private final List<TurnSubscriber> subscribers = new ArrayList<>();

    private PendingApproval pendingApproval;
    private final Deque<Steer> steers = new ArrayDeque<>();
    private boolean sealed;
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
        String json = serialize(name, payload);
        return json == null ? cursor() : publishJson(name, json);
    }

    /** Sérialise une charge utile ; {@code null} quand elle est illisible — jamais une exception. */
    private String serialize(String name, Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            // Jamais la charge utile dans le journal : elle peut porter un contenu de fichier.
            log.warn("Événement de tour non sérialisable (événement={}) : ignoré", name);
            return null;
        }
    }

    /** Publie une charge utile déjà sérialisée — le chemin du relais entre pods (SF-84-02). */
    public long publishJson(String name, String json) {
        lock.lock();
        try {
            if (json == null) {
                return lastSeq;
            }
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
     * Publie une demande d'autorisation <b>et</b> l'enregistre comme état du tour, en un seul geste
     * (F-84 / SF-84-03).
     *
     * <p>Un seul geste, et sous le même verrou que la publication : il n'existe aucun instant où la
     * demande serait partie sans être l'état du tour, ni l'inverse. Un spectateur qui se branche ne
     * peut donc jamais tomber entre les deux.</p>
     */
    public long publishApprovalRequest(Object payload, PendingApproval pending) {
        lock.lock();
        try {
            pendingApproval = pending;
            return publishJson(CONFIRM_REQUEST, serialize(CONFIRM_REQUEST, payload));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Publie la résolution d'une demande et <b>retire</b> l'état — dans cet ordre, pour qu'un écran
     * qui se branche entre les deux ne trouve jamais une attente déjà close.
     */
    public long publishApprovalResolved(Object payload, String toolUseId) {
        lock.lock();
        try {
            if (pendingApproval != null
                    && (toolUseId == null || toolUseId.equals(pendingApproval.toolUseId()))) {
                pendingApproval = null;
            }
            return publishJson(CONFIRM_RESOLVED, serialize(CONFIRM_RESOLVED, payload));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ce que le tour attend <b>à l'instant</b>, ou vide (F-84 / SF-84-03).
     *
     * <p>Une attente <b>expirée</b> n'est jamais rendue : elle ne peut plus être tranchée, et
     * l'afficher inviterait à un clic sans effet.</p>
     */
    public java.util.Optional<PendingApproval> pendingApproval() {
        lock.lock();
        try {
            if (pendingApproval == null || !pendingApproval.stillOpen()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(pendingApproval);
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------ F-84 / SF-84-06 : préciser pendant le tour

    /**
     * Une précision déposée dans le tour.
     *
     * @param steerId    identifiant porté par les événements qui la concernent
     * @param text       le message, tel que l'utilisateur l'a envoyé (espaces de bord retirés)
     * @param queuedAtMs instant du dépôt, en millisecondes depuis l'époque
     */
    public record Steer(String steerId, String text, long queuedAtMs) {
    }

    /** Charge utile de {@link #STEER_QUEUED}. */
    record SteerQueued(String steerId, String text, long queuedAt) {
    }

    /** Charge utile de {@link #STEERS_DROPPED}. */
    record SteersDropped(List<String> steerIds, String reason) {
    }

    /**
     * Dépose une <b>précision</b> (F-84 / SF-84-06) : elle est mise en file <b>et annoncée</b> dans
     * le tampon du tour, sous le même verrou — une vue qui se branche la voit toujours, et jamais
     * avant qu'elle soit en file.
     *
     * <p>Un tour <b>fini ou scellé</b> la refuse ({@link SteerReceipt.Status#ENDED}) : il ne lira plus
     * rien, et l'appelant ouvre alors un tour neuf.</p>
     *
     * <p><b>F-121 / SF-121-11 — cap assoupli, puis coalescé.</b> Jusqu'à
     * {@value #MAX_PENDING_STEERS} précisions attendent séparément. Au-delà, la précision n'est pas
     * refusée : elle est <b>fondue dans la dernière en attente</b> (même {@code steerId}, même
     * horodatage de dépôt, textes concaténés) — l'utilisateur qui pense à voix haute n'est pas
     * rabroué pour un problème de comptage. Le seul refus ({@link SteerReceipt.Status#FULL}) vient
     * du <b>volume</b> : au-delà de {@value #MAX_PENDING_STEER_CHARS} caractères cumulés, le tour
     * n'est pas touché et c'est un nouveau tour qu'il faut.</p>
     *
     * <p>Une attente d'autorisation n'est <b>pas</b> modifiée : une précision ne vaut ni accord ni
     * refus.</p>
     */
    public SteerReceipt offerSteer(String text) {
        lock.lock();
        try {
            if (finished || sealed) {
                return new SteerReceipt(SteerReceipt.Status.ENDED, null, turnId);
            }
            String trimmed = text == null ? "" : text.trim();
            if (pendingSteerChars() + trimmed.length() > MAX_PENDING_STEER_CHARS) {
                return new SteerReceipt(SteerReceipt.Status.FULL, null, turnId);
            }
            Steer steer;
            if (steers.size() >= MAX_PENDING_STEERS) {
                // Coalescence : la file garde sa taille, la dernière précision s'allonge. On
                // réannonce `steer_queued` sous le MÊME identifiant — l'écran, qui suit les
                // précisions par leur `steerId`, n'en crée pas une de plus et les verra toutes deux
                // passer « prise en compte » ensemble.
                Steer last = steers.removeLast();
                steer = new Steer(last.steerId(),
                        last.text().isEmpty() ? trimmed : last.text() + "\n" + trimmed,
                        last.queuedAtMs());
            } else {
                steer = new Steer(UUID.randomUUID().toString(), trimmed, System.currentTimeMillis());
            }
            steers.addLast(steer);
            publish(STEER_QUEUED, new SteerQueued(steer.steerId(), steer.text(), steer.queuedAtMs()));
            return new SteerReceipt(SteerReceipt.Status.ACCEPTED, steer.steerId(), turnId);
        } finally {
            lock.unlock();
        }
    }

    /** Volume cumulé des précisions en attente, en caractères. Appelé sous le verrou. */
    private int pendingSteerChars() {
        int chars = 0;
        for (Steer steer : steers) {
            chars += steer.text().length();
        }
        return chars;
    }

    /** Prend <b>toutes</b> les précisions en attente, dans l'ordre de dépôt — la boucle, à chaque étape. */
    public List<Steer> takeSteers() {
        lock.lock();
        try {
            List<Steer> taken = List.copyOf(steers);
            steers.clear();
            return taken;
        } finally {
            lock.unlock();
        }
    }

    /**
     * La boucle vient de rendre sa réponse : prend la <b>première</b> précision restante pour ouvrir
     * le tour de suite, ou <b>scelle</b> le tour s'il n'y en a aucune — en un seul geste, sous le
     * verrou. Il n'existe donc aucun instant où une précision serait acceptée par un tour qui ne la
     * lira jamais.
     *
     * <p>Les précisions suivantes restent en file : le tour de suite les lira à son étape 1.</p>
     */
    public java.util.Optional<Steer> pollFollowUpOrSeal() {
        lock.lock();
        try {
            if (steers.isEmpty()) {
                sealed = true;
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(steers.removeFirst());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Scelle le tour et rend les précisions qu'il ne lira jamais (interruption, erreur) — l'appelant
     * les annonce, pour qu'aucune ne se perde en silence.
     */
    public List<Steer> sealAndDrain() {
        lock.lock();
        try {
            sealed = true;
            List<Steer> dropped = List.copyOf(steers);
            steers.clear();
            return dropped;
        } finally {
            lock.unlock();
        }
    }

    /** Publie {@link #STEERS_DROPPED} pour ces précisions ; rien si la liste est vide. */
    public void publishSteersDropped(List<Steer> dropped, String reason) {
        if (dropped == null || dropped.isEmpty()) {
            return;
        }
        publish(STEERS_DROPPED,
                new SteersDropped(dropped.stream().map(Steer::steerId).toList(), reason));
    }

    /** Vrai une fois le tour scellé : il ne prend plus aucune précision. */
    public boolean sealed() {
        lock.lock();
        try {
            return sealed;
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
            // Ce que le tour attend À L'INSTANT, avec son temps restant EXACT (F-84 / SF-84-03).
            // Le rejeu vient de livrer le `confirm_request` tel qu'il fut, délai d'origine compris ;
            // cet aparté le corrige. Sans lui, un écran arrivé après coup afficherait deux minutes
            // là où il en reste vingt secondes — le contraire de la règle de SF-47-02.
            if (pendingApproval != null && pendingApproval.stillOpen()
                    && !subscriber.deliver(new TurnEvent(0L, CONFIRM_STATE,
                            pendingApproval.toJson()))) {
                return false;
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
