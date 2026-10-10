package fr.claudegateway.push;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
            String payload = buildPayload(event, workspaceId);
            for (PushSubscription subscription : subscriptions) {
                WebPushTransport.Result result = transport.send(subscription, payload);
                if (result == WebPushTransport.Result.EXPIRED) {
                    // Endpoint mort côté service push : on retire la ligne (révocation implicite).
                    repository.deleteByEndpoint(subscription.getEndpoint());
                }
            }
        } catch (RuntimeException e) {
            // L'émission est best-effort : jamais fatale. Aucune donnée sensible dans le log.
            log.warn("Émission Web Push interrompue : {}", e.getMessage());
        }
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
     * <p>F-185 / SF-185-02 : {@code data.event} porte le code de l'événement ; le {@code tag} par
     * terminal fait qu'une notification <b>remplace</b> la précédente du même terminal au lieu de
     * s'empiler.</p>
     */
    String buildPayload(PushEvent event, UUID workspaceId) {
        String url = workspaceId != null ? "/atelier/" + workspaceId : "/forge";
        String tag = "cg-" + (workspaceId != null ? workspaceId : "forge");
        // Format ngsw : { notification: { title, body, icon, tag, data: { url, event, onActionClick } } }.
        Map<String, Object> notification = Map.of(
                "title", event.title(),
                "body", event.body(),
                "icon", "/icons/icon-192.png",
                "tag", tag,
                "renotify", true,
                "data", Map.of(
                        "url", url,
                        "event", event.name(),
                        "onActionClick", Map.of(
                                "default", Map.of("operation", "openWindow", "url", url))));
        try {
            return objectMapper.writeValueAsString(Map.of("notification", notification));
        } catch (Exception e) {
            // Repli sans dépendance JSON : les valeurs sont des littéraux + un UUID (rien à échapper
            // hormis l'apostrophe, qui n'en demande pas en JSON).
            return "{\"notification\":{\"title\":\"" + event.title() + "\",\"body\":\"" + event.body()
                    + "\",\"icon\":\"/icons/icon-192.png\",\"tag\":\"" + tag
                    + "\",\"renotify\":true,\"data\":{\"url\":\"" + url + "\",\"event\":\"" + event.name()
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
