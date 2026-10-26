package fr.claudegateway.push;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/**
 * L'<b>émetteur Web Push</b> (F-153 / SF-153-02) : branché sur les deux transitions de tour déjà
 * portées par le service (F-84) — un tour <b>terminé</b>, un tour <b>en attente d'autorisation</b> —
 * il pousse une notification système aux <b>seuls appareils du propriétaire</b>.
 *
 * <p><b>Scellé par {@code user_id}</b> (D3) : on ne charge que les abonnements du compte concerné.
 * <b>Titre neutre</b> (D4) : la charge ne porte aucun contenu de tour, nom de projet ni commande —
 * le détail n'apparaît qu'après ouverture authentifiée de l'app (SF-153-03). L'endpoint mort
 * (404/410) est <b>purgé</b>.</p>
 *
 * <p><b>Jamais bloquant pour le tour</b> (règle async CLAUDE.md) : l'émission (lecture + POST
 * réseau) part sur un exécuteur dédié, borné ; le fil du tour rend la main aussitôt. Si le push
 * n'est pas configuré, l'émetteur ne fait rien (repli in-tab SF-153-01).</p>
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    private final PushSubscriptionRepository repository;
    private final WebPushTransport transport;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final TerminalWatch watch;
    private final NotificationJournal journal;
    private final PushPreferences preferences;
    private final Clock clock;

    /** F-185 / SF-185-03 : le même événement, pour le même terminal, n'est pas renvoyé dans ce délai. */
    static final Duration DEDUP_WINDOW = Duration.ofSeconds(30);

    /** Dernier envoi par (compte, terminal, événement) — mémoire du pod : un tour vit sur un seul pod. */
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();

    public PushNotificationService(PushSubscriptionRepository repository,
            WebPushTransport transport, ObjectMapper objectMapper, TerminalWatch watch,
            NotificationJournal journal, PushPreferences preferences, Clock clock) {
        this.repository = repository;
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.watch = watch == null ? TerminalWatch.NONE : watch;
        this.journal = journal == null ? NotificationJournal.NONE : journal;
        this.preferences = preferences == null ? PushPreferences.ALL : preferences;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        ThreadFactory daemon = runnable -> {
            Thread t = new Thread(runnable, "web-push-emitter");
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(daemon);
    }

    /** Un tour s'est terminé : notifie « Une réponse est prête » (si le push est configuré). */
    public void notifyTurnDone(UUID userId, UUID workspaceId) {
        notify(userId, workspaceId, PushEvent.TURN_DONE);
    }

    /**
     * Un tour attend une autorisation : notifie « Une autorisation est demandée ». Transition
     * <b>critique</b> — le silence vaut refus (timeoutMs), c'est celle qui justifie le push.
     */
    public void notifyAuthorizationRequested(UUID userId, UUID workspaceId) {
        notify(userId, workspaceId, PushEvent.AUTHORIZATION_REQUESTED);
    }

    /**
     * L'agent pose une question structurée (outil {@code demander}, F-164 / SF-164-05) et attend la
     * réponse : notifie « Une question vous attend ». Comme pour l'autorisation, la charge reste
     * <b>neutre</b> (D4) — aucun contenu de la question ne quitte l'application.
     */
    public void notifyQuestionAsked(UUID userId, UUID workspaceId) {
        notify(userId, workspaceId, PushEvent.QUESTION_ASKED);
    }

    /**
     * Notifie un événement du catalogue (F-185 / SF-185-02) aux appareils du propriétaire —
     * best-effort, jamais bloquant ; inactif si le push n'est pas configuré.
     */
    public void notify(UUID userId, UUID workspaceId, PushEvent event) {
        if (userId == null || event == null) {
            return;
        }
        // F-185 / SF-185-04 : même sans push configuré, l'événement va au centre de notifications.
        // Tout part sur l'exécuteur dédié : le fil du tour n'attend ni la base ni le réseau.
        executor.execute(() -> deliver(userId, workspaceId, event));
    }

    private void deliver(UUID userId, UUID workspaceId, PushEvent event) {
        try {
            if (duplicate(userId, workspaceId, event)) {
                return;
            }
            boolean watched = !event.alwaysDelivered() && watched(userId, workspaceId);
            record(userId, workspaceId, event, watched);
            if (watched || !transport.isEnabled() || !allowed(userId, event)) {
                return;
            }
            List<PushSubscription> subscriptions = repository.findByUserId(userId);
            if (subscriptions.isEmpty()) {
                return;
            }
            sendAll(subscriptions, buildPayload(event, workspaceId), PushDelivery.forEvent(event));
        } catch (RuntimeException e) {
            // L'émission est best-effort : jamais fatale. Aucune donnée sensible dans le log.
            log.warn("Émission Web Push interrompue : {}", e.getMessage());
        }
    }

    /** Pousse la charge à chaque appareil ; un endpoint mort (404/410) est purgé (révocation implicite). */
    private void sendAll(List<PushSubscription> subscriptions, String payload, PushDelivery delivery) {
        for (PushSubscription subscription : subscriptions) {
            WebPushTransport.Result result = transport.send(subscription, payload, delivery);
            if (result == WebPushTransport.Result.EXPIRED) {
                repository.deleteByEndpoint(subscription.getEndpoint());
            }
        }
    }

    /**
     * <b>Notification de test</b> (F-217 / SF-217-01, D4) : « Tester le son » depuis les préférences. Vise les
     * <b>seuls appareils du compte</b> ({@code user_id}), hors cloche, hors anti-doublon et hors préférences —
     * c'est un geste explicite. Rend aussitôt le nombre d'appareils visés ; l'envoi part sur l'exécuteur dédié.
     *
     * @return le nombre d'abonnements du compte (0 si aucun) ; rien n'est envoyé si le push n'est pas configuré
     */
    public int sendTest(UUID userId) {
        if (userId == null) {
            return 0;
        }
        List<PushSubscription> subscriptions = repository.findByUserId(userId);
        if (subscriptions.isEmpty() || !transport.isEnabled()) {
            return subscriptions.size();
        }
        String payload = buildTestPayload();
        executor.execute(() -> {
            try {
                sendAll(subscriptions, payload, PushDelivery.TEST);
            } catch (RuntimeException e) {
                log.warn("Notification de test interrompue : {}", e.getMessage());
            }
        });
        return subscriptions.size();
    }

    /** Le push peut-il réellement émettre (VAPID configuré) ? */
    public boolean configured() {
        return transport.isEnabled();
    }

    /**
     * D7 de F-153 (F-185 / SF-185-03) : le terminal est sous les yeux de l'utilisateur, l'écran
     * suffit. Une lecture en échec ne fait rien taire : mieux vaut un doublon qu'un silence.
     */
    private boolean watched(UUID userId, UUID workspaceId) {
        if (workspaceId == null) {
            return false;
        }
        try {
            return watch.watching(userId, workspaceId);
        } catch (RuntimeException e) {
            log.warn("Présence illisible, notification envoyée : {}", e.getMessage());
            return false;
        }
    }

    /**
     * Préférences du compte (SF-185-06) : sourdine et heures calmes. Les critiques passent toujours ;
     * une lecture en échec laisse passer (mieux vaut un doublon qu'un silence).
     */
    private boolean allowed(UUID userId, PushEvent event) {
        if (event.critical()) {
            return true;
        }
        try {
            return preferences.allowsPush(userId, event);
        } catch (RuntimeException e) {
            log.warn("Préférences illisibles, notification envoyée : {}", e.getMessage());
            return true;
        }
    }

    /** Le centre de notifications (SF-185-04) ; un échec n'empêche jamais le push. */
    private void record(UUID userId, UUID workspaceId, PushEvent event, boolean watched) {
        try {
            journal.record(userId, workspaceId, event, watched);
        } catch (RuntimeException e) {
            log.warn("Centre de notifications : inscription impossible : {}", e.getMessage());
        }
    }

    /** Anti-doublon : le même événement pour le même terminal, déjà envoyé il y a moins de 30 s. */
    private boolean duplicate(UUID userId, UUID workspaceId, PushEvent event) {
        Instant now = clock.instant();
        String key = userId + ":" + workspaceId + ":" + event.name();
        Instant previous = lastSent.get(key);
        if (previous != null && previous.plus(DEDUP_WINDOW).isAfter(now)) {
            return true;
        }
        lastSent.put(key, now);
        // Borne la mémoire : on oublie ce qui est sorti de la fenêtre.
        if (lastSent.size() > 1_000) {
            lastSent.values().removeIf(at -> at.plus(DEDUP_WINDOW).isBefore(now));
        }
        return false;
    }

    /**
     * Construit la charge <b>neutre</b> au format attendu par le service worker Angular
     * (`ngsw-worker.js`, F-152) : une clé {@code notification} que le SW affiche telle quelle, même
     * l'application fermée, et un {@code data.onActionClick} qui ouvre le bon terminal au clic
     * (SF-153-03). Le titre et le corps sont génériques, l'{@code url} porte un identifiant de
     * terminal <b>opaque</b> — jamais un nom de projet ni une commande (D4).
     *
     * <p>F-185 / SF-185-02 : {@code data.event} porte le code de l'événement.</p>
     *
     * <p>F-217 / SF-217-01 (D1) — une notification <b>qui sonne</b> : {@code silent: false} explicite,
     * {@code renotify}, {@code vibrate}, {@code timestamp} ; ce qui attend une action reste affiché jusqu'au
     * geste ({@code requireInteraction}). Le {@code tag} est propre à l'événement : une information remplace
     * la précédente du même type pour ce terminal ; une demande d'action porte en plus son horodatage — une
     * occurrence, une notification, donc un son, y compris sur Safari et Firefox qui ignorent
     * {@code renotify} et remplacent en silence. ngsw recopie ces options telles quelles
     * ({@code NOTIFICATION_OPTION_NAMES}).</p>
     */
    String buildPayload(PushEvent event, UUID workspaceId) {
        String url = workspaceId != null ? "/atelier/" + workspaceId : "/forge";
        long timestamp = clock.millis();
        String tag = "cg-" + (workspaceId != null ? workspaceId : "forge") + "-" + event.name()
                + (event.requiresAction() ? "-" + timestamp : "");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        data.put("event", event.name());
        if (workspaceId != null) {
            // Identifiant opaque (déjà dans l'URL) : l'écran ferme les notifications de ce terminal (SF-217-02).
            data.put("workspaceId", workspaceId.toString());
        }
        data.put("onActionClick", Map.of("default", Map.of("operation", "openWindow", "url", url)));
        return payload(event.title(), event.body(), tag, timestamp, event.requiresAction(),
                event.requiresAction() ? ACTION_VIBRATE : INFO_VIBRATE, data);
    }

    /** Motif de vibration (Android) de ce qui attend une action : trois impulsions. */
    static final List<Integer> ACTION_VIBRATE = List.of(200, 100, 200, 100, 200);

    /** Motif de vibration (Android) d'une information : une impulsion. */
    static final List<Integer> INFO_VIBRATE = List.of(200);

    static final String TEST_TITLE = "Notification de test";
    static final String TEST_BODY = "Si vous l'entendez, le son de vos notifications est bien réglé.";
    static final String TEST_URL = "/settings";

    /** La charge de la notification de test (D4) : neutre, sonore, elle ouvre les paramètres au clic. */
    String buildTestPayload() {
        long timestamp = clock.millis();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", TEST_URL);
        data.put("event", "TEST");
        data.put("onActionClick", Map.of("default", Map.of("operation", "openWindow", "url", TEST_URL)));
        return payload(TEST_TITLE, TEST_BODY, "cg-test-" + timestamp, timestamp, false, ACTION_VIBRATE, data);
    }

    private String payload(String title, String body, String tag, long timestamp, boolean requireInteraction,
            List<Integer> vibrate, Map<String, Object> data) {
        // Format ngsw : { notification: { title, body, icon, tag, …, data: { url, event, onActionClick } } }.
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("title", title);
        notification.put("body", body);
        notification.put("icon", "/icons/icon-192.png");
        notification.put("tag", tag);
        notification.put("renotify", true);
        notification.put("silent", false);
        notification.put("requireInteraction", requireInteraction);
        notification.put("vibrate", vibrate);
        notification.put("timestamp", timestamp);
        notification.put("data", data);
        try {
            return objectMapper.writeValueAsString(Map.of("notification", notification));
        } catch (Exception e) {
            // Repli sans dépendance JSON : littéraux, UUID et nombres — rien à échapper hormis l'apostrophe,
            // qui n'en demande pas en JSON.
            Object url = data.get("url");
            return "{\"notification\":{\"title\":\"" + title + "\",\"body\":\"" + body
                    + "\",\"icon\":\"/icons/icon-192.png\",\"tag\":\"" + tag
                    + "\",\"renotify\":true,\"silent\":false,\"requireInteraction\":" + requireInteraction
                    + ",\"vibrate\":" + vibrate.toString().replace(" ", "") + ",\"timestamp\":" + timestamp
                    + ",\"data\":{\"url\":\"" + url + "\",\"event\":\"" + data.get("event")
                    + "\",\"onActionClick\":{\"default\":{\"operation\":\"openWindow\",\"url\":\""
                    + url + "\"}}}}}";
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
