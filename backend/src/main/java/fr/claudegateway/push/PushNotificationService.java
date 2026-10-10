package fr.claudegateway.push;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    public PushNotificationService(PushSubscriptionRepository repository,
            WebPushTransport transport, ObjectMapper objectMapper) {
        this.repository = repository;
        this.transport = transport;
        this.objectMapper = objectMapper;
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
        if (userId == null || event == null || !transport.isEnabled()) {
            return;
        }
        // Tout part sur l'exécuteur dédié : le fil du tour n'attend ni la base ni le réseau.
        executor.execute(() -> deliver(userId, workspaceId, event));
    }

    private void deliver(UUID userId, UUID workspaceId, PushEvent event) {
        try {
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
