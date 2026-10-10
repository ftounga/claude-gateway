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

    /**
     * Délai par défaut d'attente d'une réponse à une <b>question</b> (ms) — F-164 / SF-164-07. Plus
     * long qu'une autorisation : une carte de plusieurs questions demande de lire et de choisir.
     */
    public static final long DEFAULT_QUESTION_TIMEOUT_MS = 600_000L;

    private static final Logger log = LoggerFactory.getLogger(RunnerConfirmationGate.class);
    private static final int MAX_REASON_CHARS = 500;

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final long timeoutMs;
    private final long questionTimeoutMs;

    /** F-185 / SF-185-05 : avance du rappel d'une question sans réponse, avant son échéance. */
    public static final long REMINDER_LEAD_MS = 120_000L;

    private volatile long reminderLeadMs = REMINDER_LEAD_MS;

    /**
     * Règle l'avance du rappel (tests à délais courts). Rend la porte, pour chaîner après le
     * constructeur — un second constructeur casserait le contexte Spring.
     */
    public RunnerConfirmationGate withReminderLead(long leadMs) {
        this.reminderLeadMs = Math.max(0L, leadMs);
        return this;
    }

    public RunnerConfirmationGate(
            @Value("${app.runner.confirmation.timeout-ms:120000}") long timeoutMs,
            @Value("${app.runner.question.timeout-ms:600000}") long questionTimeoutMs) {
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        this.questionTimeoutMs = questionTimeoutMs > 0 ? questionTimeoutMs : DEFAULT_QUESTION_TIMEOUT_MS;
    }

    /**
     * Délai au bout duquel une demande sans réponse est refusée, en millisecondes (F-47 / SF-47-02).
     *
     * <p>Exposé pour être <b>dit à l'écran</b> : sans cela, le client devrait coder 120 000 ms en
     * dur et mentirait le jour où la configuration change. C'est la porte qui tient le délai, c'est
     * elle qui l'annonce.</p>
     */
    public long timeoutMs() {
        return timeoutMs;
    }

    /**
     * Délai au bout duquel une <b>question</b> sans réponse expire, en millisecondes (F-164 /
     * SF-164-07). Distinct de {@link #timeoutMs()} : 2 minutes suffisent pour autoriser une commande,
     * pas pour lire et trancher une carte de plusieurs questions.
     */
    public long questionTimeoutMs() {
        return questionTimeoutMs;
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
        Pending entry = new Pending(userId, workspaceId, Kind.CONFIRMATION,
                new CompletableFuture<>(), new Outcome(Decision.DENY, "Tour interrompu avant décision."));
        if (pending.putIfAbsent(callId, entry) != null) {
            // Identifiant déjà en attente : on refuse plutôt que d'écraser une demande en cours.
            return new Outcome(Decision.DENY, "Demande d'autorisation déjà en cours.");
        }
        try {
            // Trace de l'EMISSION (F-47 / SF-47-02), et pas seulement de l'expiration : le
            // diagnostic de l'incident du 2026-09-08 a dû déduire que la demande était partie,
            // faute d'une ligne qui le dise. Rien de la commande n'est journalisé — elle peut
            // porter un secret, et un journal de production n'est pas l'endroit pour l'apprendre.
            log.info("Autorisation demandée (workspace={}, call={}) : décision attendue sous {} ms",
                    workspaceId, callId, timeoutMs);
            onRegistered.run();
            return (Outcome) entry.future().get(timeoutMs, TimeUnit.MILLISECONDS);
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
        resolve(userId, workspaceId, callId, allow, reason, false);
    }

    /**
     * Variante qui porte l'intention « <b>toujours autoriser cette commande</b> » (F-121 / SF-121-02) :
     * {@code persistRule} remonte à la boucle en attente, qui écrit alors une règle persistante. La
     * porte ne persiste rien elle-même — elle ne connaît ni l'outil ni la commande —, elle relaie
     * seulement la décision à celui qui les a.
     */
    public void resolve(UUID userId, UUID workspaceId, String callId, boolean allow, String reason,
            boolean persistRule) {
        Pending entry = pending.get(callId);
        if (entry == null || entry.kind() != Kind.CONFIRMATION
                || !entry.userId().equals(userId) || !entry.workspaceId().equals(workspaceId)) {
            // Le discriminant de genre en fait partie : une réponse de question ne tranche jamais une
            // autorisation (et l'inverse), même à identifiant de corrélation deviné.
            throw new NoPendingConfirmationException("Aucune autorisation n'est en attente pour cette action.");
        }
        entry.future().complete(new Outcome(allow ? Decision.ALLOW : Decision.DENY, shorten(reason),
                allow && persistRule));
    }

    /**
     * Enregistre une <b>question structurée</b> posée à l'utilisateur puis <b>attend</b> sa réponse
     * (F-164 / SF-164-01). Même primitive que {@link #await} — même isolation, même annulation à
     * l'interruption — mais son propre délai ({@link #questionTimeoutMs()}, SF-164-07) et un payload
     * attendu qui est une <b>réponse</b>, pas un allow/deny.
     *
     * <p><b>Pauses répétées dans un même tour</b> : l'entrée est indexée par {@code callId} et retirée
     * à la fin ({@code finally}). La boucle traite ses appels d'outil en série ; chaque
     * {@code demander} a son propre {@code callId}, bloque, reprend, puis le suivant. Rien n'empêche
     * donc N pauses successives — c'est le sens de la généralisation demandée au cadrage.</p>
     *
     * @return la réponse, jamais {@code null} ({@link AnswerOutcome.Status#TIMEOUT} en cas de silence)
     */
    public AnswerOutcome awaitAnswer(UUID userId, UUID workspaceId, String callId, Runnable onRegistered) {
        return awaitAnswer(userId, workspaceId, callId, onRegistered, null);
    }

    /**
     * Attend la réponse, avec un <b>rappel</b> (F-185 / SF-185-05) : si rien n'est venu à « délai −
     * avance », {@code onReminder} est appelé une fois, puis l'attente se poursuit jusqu'au même
     * délai total. Sans rappel ({@code null}) ou si le délai ne dépasse pas l'avance : une seule attente.
     */
    public AnswerOutcome awaitAnswer(UUID userId, UUID workspaceId, String callId, Runnable onRegistered,
            Runnable onReminder) {
        Pending entry = new Pending(userId, workspaceId, Kind.QUESTION,
                new CompletableFuture<>(), AnswerOutcome.interrupted());
        if (pending.putIfAbsent(callId, entry) != null) {
            // Identifiant déjà en attente : on refuse plutôt que d'écraser une question en cours.
            return AnswerOutcome.failed();
        }
        try {
            log.info("Question posée (workspace={}, call={}) : réponse attendue sous {} ms",
                    workspaceId, callId, questionTimeoutMs);
            onRegistered.run();
            long lead = reminderLeadMs;
            if (onReminder != null && lead > 0 && questionTimeoutMs > lead) {
                try {
                    return (AnswerOutcome) entry.future().get(questionTimeoutMs - lead, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ex) {
                    log.info("Question toujours sans réponse (workspace={}) : rappel", workspaceId);
                    try {
                        onReminder.run();
                    } catch (RuntimeException reminderFailure) {
                        // Le rappel est best-effort : il ne change jamais l'attente.
                        log.warn("Rappel de question impossible : {}", reminderFailure.getMessage());
                    }
                    return (AnswerOutcome) entry.future().get(lead, TimeUnit.MILLISECONDS);
                }
            }
            return (AnswerOutcome) entry.future().get(questionTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            log.info("Aucune réponse à la question dans le délai (workspace={})", workspaceId);
            return AnswerOutcome.timedOut();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AnswerOutcome.interrupted();
        } catch (ExecutionException | RuntimeException ex) {
            // Y compris un échec du relais à l'écran, ou une réponse d'un mauvais genre (cast) : la
            // reprise reste sûre, le modèle apprendra simplement qu'aucune réponse n'a abouti.
            return AnswerOutcome.failed();
        } finally {
            pending.remove(callId);
        }
    }

    /**
     * Tranche une <b>question</b> en attente avec le compte rendu des réponses de l'utilisateur
     * (F-164 / SF-164-01). Comme {@link #resolve}, le workspace <b>et</b> le propriétaire doivent
     * correspondre, et le genre doit être une question : une réponse ne tranche jamais une autorisation.
     *
     * @throws NoPendingConfirmationException si aucune question n'attend cette réponse
     */
    public void answerQuestions(UUID userId, UUID workspaceId, String callId, String content) {
        Pending entry = pending.get(callId);
        if (entry == null || entry.kind() != Kind.QUESTION
                || !entry.userId().equals(userId) || !entry.workspaceId().equals(workspaceId)) {
            throw new NoPendingConfirmationException("Aucune question n'est en attente pour cette réponse.");
        }
        entry.future().complete(AnswerOutcome.answered(content));
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
                // La valeur d'annulation dépend du genre de l'attente : un refus pour une autorisation,
                // une réponse « interrompue » pour une question — chacune complète son propre payload.
                entry.getValue().future().complete(entry.getValue().cancelValue());
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

    /**
     * Issue d'une demande : la décision, le motif éventuel à relayer au modèle, et si l'utilisateur a
     * demandé de <b>persister</b> une règle « toujours autoriser cette commande » (F-121 / SF-121-02).
     */
    public record Outcome(Decision decision, String reason, boolean persistRule) {

        /** Forme historique (sans persistance), conservée pour les appelants qui l'attendent. */
        public Outcome(Decision decision, String reason) {
            this(decision, reason, false);
        }
    }

    /**
     * Genre d'attente portée par la porte : une <b>autorisation</b> (allow/deny historique) ou une
     * <b>question structurée</b> (F-164). Le genre est vérifié à la résolution : une réponse d'un genre
     * ne peut jamais trancher une attente de l'autre, même à identifiant deviné.
     */
    private enum Kind {
        CONFIRMATION,
        QUESTION
    }

    /**
     * Réponse à une question structurée (F-164 / SF-164-01), rendue par {@link #awaitAnswer}.
     *
     * @param status  issue de l'attente
     * @param content compte rendu des réponses de l'utilisateur, prêt à être rendu au modèle ; jamais
     *                {@code null} pour {@link Status#ANSWERED}, {@code null} pour tout le reste
     */
    public record AnswerOutcome(Status status, String content) {

        /** Issue d'une question posée. */
        public enum Status {
            /** L'utilisateur a répondu. */
            ANSWERED,
            /** Personne n'a répondu dans le délai : le tour reprend sans réponse. */
            TIMEOUT,
            /** Le tour a été interrompu (ou l'attente libérée) avant réponse. */
            INTERRUPTED,
            /** La question n'a pas pu aboutir (relais impossible, doublon d'identifiant). */
            FAILED
        }

        /** Vrai uniquement quand l'utilisateur a effectivement répondu. */
        public boolean answered() {
            return status == Status.ANSWERED;
        }

        /** Réponse de l'utilisateur. */
        public static AnswerOutcome answered(String content) {
            return new AnswerOutcome(Status.ANSWERED, content);
        }

        /** Silence : personne n'a répondu dans le délai. */
        public static AnswerOutcome timedOut() {
            return new AnswerOutcome(Status.TIMEOUT, null);
        }

        /** Attente libérée par une interruption du tour. */
        public static AnswerOutcome interrupted() {
            return new AnswerOutcome(Status.INTERRUPTED, null);
        }

        /** La question n'a pas pu aboutir. */
        public static AnswerOutcome failed() {
            return new AnswerOutcome(Status.FAILED, null);
        }
    }

    /**
     * Attente en cours : qui l'a posée (isolation), son genre, la promesse de payload, et la valeur à
     * compléter si le tour est interrompu ({@code cancelWorkspace}). Le payload est générique — un
     * {@link Outcome} pour une autorisation, un {@link AnswerOutcome} pour une question — pour que la
     * même primitive de pause/isolation/annulation serve les deux.
     */
    private record Pending(UUID userId, UUID workspaceId, Kind kind,
            CompletableFuture<Object> future, Object cancelValue) {
    }
}
