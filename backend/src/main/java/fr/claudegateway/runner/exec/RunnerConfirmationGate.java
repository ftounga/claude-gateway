package fr.claudegateway.runner.exec;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Porte de validation des actions exécutées sur la machine de l'utilisateur (F-38 / SF-38-08,
 * décision D7). En cible {@code RUNNER}, une commande n'est <b>jamais</b> émise avant décision
 * explicite.
 *
 * <p><b>Pourquoi une porte neuve.</b> Le mécanisme F-33 déjà livré
 * ({@code AtelierSessionService.confirmToolUse}) relaie la décision au fournisseur Managed Agent, et
 * la politique est lue à l'<i>ouverture</i> de session. Or D2 interdit les Managed Agents en cible
 * {@code RUNNER} : la boucle concernée est {@code AtelierChatService.runLoop}, qui n'avait aucun
 * point de confirmation. Cette classe l'ajoute <b>sans toucher</b> au chemin sandbox existant.</p>
 *
 * <p><b>Le silence ne vaut pas autorisation</b> : sans réponse dans le délai imparti, la demande est
 * refusée. C'est la seule valeur par défaut acceptable pour une commande qui s'exécuterait sur une
 * vraie machine.</p>
 *
 * <p><b>Isolation</b> : une décision n'est acceptée que du propriétaire du workspace qui a posé la
 * demande — l'identifiant de corrélation seul ne suffit jamais à trancher.</p>
 */
@Component
public class RunnerConfirmationGate {

    /** Délai par défaut d'attente d'une décision (ms). Au-delà : refus. */
    public static final long DEFAULT_TIMEOUT_MS = 120_000L;

    private static final Logger log = LoggerFactory.getLogger(RunnerConfirmationGate.class);
    private static final int MAX_REASON_CHARS = 500;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public RunnerConfirmationGate(
            @Value("${app.runner.confirmation.timeout-ms:120000}") long timeoutMs) {
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
    }

    /**
     * Enregistre une demande d'autorisation puis <b>attend</b> la décision. Bloquant par
     * construction : la boucle tool-use ne peut pas continuer sans savoir si elle a le droit.
     *
     * @param userId      propriétaire du workspace (déjà vérifié en amont — isolation)
     * @param workspaceId workspace concerné
     * @param callId      identifiant de corrélation du contrat §1 (= {@code tool_use})
     * @param onRegistered exécuté <b>après</b> l'enregistrement et <b>avant</b> l'attente : c'est là
     *                     que la demande est relayée à l'écran. L'ordre importe — relayer avant
     *                     d'enregistrer exposerait une réponse rapide à un « rien à trancher »
     * @return la décision, jamais {@code null} ({@link Decision#TIMEOUT} en cas de silence)
     */
    public Outcome await(UUID userId, UUID workspaceId, String callId, Runnable onRegistered) {
        Pending entry = new Pending(userId, workspaceId, new CompletableFuture<>());
        if (pending.putIfAbsent(callId, entry) != null) {
            // Identifiant déjà en attente : on refuse plutôt que d'écraser une demande en cours.
            return new Outcome(Decision.DENY, "Demande d'autorisation déjà en cours.");
        }
        try {
            onRegistered.run();
            return entry.future().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            log.info("Aucune décision d'autorisation dans le délai (workspace={}) : commande refusée",
                    workspaceId);
            return new Outcome(Decision.TIMEOUT, "Aucune réponse dans le délai imparti.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Outcome(Decision.DENY, "Demande interrompue.");
        } catch (ExecutionException | RuntimeException ex) {
            // Y compris un échec du relais à l'écran : sans écran pour trancher, on ne lance rien.
            return new Outcome(Decision.DENY, "La demande d'autorisation n'a pas pu aboutir.");
        } finally {
            pending.remove(callId);
        }
    }

    /**
     * Tranche une demande en attente. Le workspace <b>et</b> le propriétaire doivent correspondre :
     * un identifiant de corrélation deviné ne permet pas d'autoriser l'exécution chez autrui.
     *
     * @throws NoPendingConfirmationException si rien n'attend cette réponse
     */
    public void resolve(UUID userId, UUID workspaceId, String callId, boolean allow, String reason) {
        Pending entry = pending.get(callId);
        if (entry == null || !entry.userId().equals(userId) || !entry.workspaceId().equals(workspaceId)) {
            throw new NoPendingConfirmationException("Aucune autorisation n'est en attente pour cette action.");
        }
        entry.future().complete(new Outcome(allow ? Decision.ALLOW : Decision.DENY, shorten(reason)));
    }

    /**
     * Libère toutes les demandes en attente d'un workspace, en <b>refus</b> : appelée à
     * l'interruption d'un tour (F-32 / SF-38-07). Une demande laissée pendante bloquerait la boucle
     * jusqu'à l'échéance, alors que l'utilisateur vient justement de demander l'arrêt.
     *
     * @return le nombre de demandes libérées
     */
    public int cancelWorkspace(UUID workspaceId) {
        int released = 0;
        for (Map.Entry<String, Pending> entry : pending.entrySet()) {
            if (entry.getValue().workspaceId().equals(workspaceId)) {
                entry.getValue().future().complete(
                        new Outcome(Decision.DENY, "Tour interrompu avant décision."));
                released++;
            }
        }
        return released;
    }

    private static String shorten(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_REASON_CHARS ? trimmed : trimmed.substring(0, MAX_REASON_CHARS);
    }

    /** Décision rendue sur une demande d'autorisation. */
    public enum Decision {
        /** L'utilisateur autorise l'action. */
        ALLOW,
        /** L'utilisateur refuse l'action (ou la demande a été libérée). */
        DENY,
        /** Personne n'a tranché dans le délai : refus. */
        TIMEOUT;

        /** Vrai uniquement pour {@link #ALLOW} : tout le reste interdit l'émission. */
        public boolean allows() {
            return this == ALLOW;
        }

        /** Libellé relayé à l'écran ({@code confirm_resolved}), aligné sur F-33. */
        public String label() {
            return switch (this) {
                case ALLOW -> "allow";
                case DENY -> "deny";
                case TIMEOUT -> "timeout";
            };
        }
    }

    /** Issue d'une demande : la décision et, le cas échéant, le motif à relayer au modèle. */
    public record Outcome(Decision decision, String reason) {
    }

    /** Demande en attente : qui l'a posée (isolation) et la promesse de décision. */
    private record Pending(UUID userId, UUID workspaceId, CompletableFuture<Outcome> future) {
    }
}
