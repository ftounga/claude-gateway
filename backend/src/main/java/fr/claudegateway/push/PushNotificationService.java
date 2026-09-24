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
        emit(userId, workspaceId, "Une réponse est prête", "Votre tâche est terminée.");
    }

    /**
     * Un tour attend une autorisation : notifie « Une autorisation est demandée ». Transition
     * <b>critique</b> — le silence vaut refus (timeoutMs), c'est celle qui justifie le push.
     */
    public void notifyAuthorizationRequested(UUID userId, UUID workspaceId) {
        emit(userId, workspaceId, "Une autorisation est demandée",
                "Ouvrez l'application pour autoriser ou refuser.");
    }

    private void emit(UUID userId, UUID workspaceId, String title, String body) {
        if (userId == null || !transport.isEnabled()) {
            return;
        }
        // Tout part sur l'exécuteur dédié : le fil du tour n'attend ni la base ni le réseau.
        executor.execute(() -> deliver(userId, workspaceId, title, body));
    }

    private void deliver(UUID userId, UUID workspaceId, String title, String body) {
        try {
            List<PushSubscription> subscriptions = repository.findByUserId(userId);
            if (subscriptions.isEmpty()) {
                return;
            }
            String payload = buildPayload(title, body, workspaceId);
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
     * Construit la charge <b>neutre</b> : un titre, un corps générique et une route d'ouverture
     * (identifiant de terminal opaque, jamais un nom ou une commande). C'est le service worker
     * (SF-153-03) qui affichera la notification et ouvrira le bon terminal.
     */
    private String buildPayload(String title, String body, UUID workspaceId) {
        String url = workspaceId != null ? "/atelier/" + workspaceId : "/forge";
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "title", title,
                    "body", body,
                    "url", url));
        } catch (Exception e) {
            // Repli sans dépendance JSON : les valeurs sont des littéraux + un UUID (rien à échapper).
            return "{\"title\":\"" + title + "\",\"body\":\"" + body + "\",\"url\":\"" + url + "\"}";
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
