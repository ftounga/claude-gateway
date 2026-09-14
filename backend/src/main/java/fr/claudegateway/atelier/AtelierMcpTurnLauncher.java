package fr.claudegateway.atelier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierConfirmRequest;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierConfirmResolved;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierSteer;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierStepEvent;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.live.PendingApproval;
import fr.claudegateway.byok.ByokKeyRequiredException;
import fr.claudegateway.quota.QuotaExceededException;

/**
 * Lance un tour d'atelier <b>sans navigateur</b>, pour le serveur MCP (F-112 / SF-112-05).
 *
 * <p>Le flux web (F-84) exécute la boucle tool-use sur un thread de flux SSE et publie chaque étape
 * dans le <b>tour vivant</b> ({@link LiveTurn}). MCP a besoin du même tour vivant — pour que
 * {@code tour_suivre} le rejoue par curseur, et pour que l'écran du terminal le voie exactement comme
 * un tour lancé au clavier — mais <b>sans flux</b> : l'outil rend un identifiant tout de suite (la
 * « tâche »), et la boucle continue en tâche de fond.</p>
 *
 * <p>Ce service <b>réutilise</b> le cœur de F-84 : {@link LiveTurnRegistry#openOrSteer} (un envoi sur
 * un projet dont le tour tourne devient une précision), {@link LiveTurn} (le tampon rejouable) et
 * {@link AtelierChatService#chatStreaming}. Il publie des charges utiles JSON <b>de mêmes noms</b> que
 * le flux web, via des {@link Map} — sans coupler au contrôleur. L'isolation {@code user_id} est
 * garantie en aval par {@code chatStreaming} ({@code requireOwned}).</p>
 */
@Service
public class AtelierMcpTurnLauncher {

    private static final Logger log = LoggerFactory.getLogger(AtelierMcpTurnLauncher.class);

    private final AtelierChatService chatService;
    private final LiveTurnRegistry liveTurns;
    private final Executor chatStreamExecutor;

    public AtelierMcpTurnLauncher(AtelierChatService chatService, LiveTurnRegistry liveTurns,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.chatService = chatService;
        this.liveTurns = liveTurns;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    /** Ce qu'est devenu l'envoi MCP : un tour neuf, ou une précision d'un tour déjà vivant. */
    public record LaunchResult(UUID turnId, boolean steered, String steerId, boolean steerRejected) {
    }

    /**
     * Lance (ou précise) un tour pour ce projet et rend tout de suite son identifiant.
     *
     * @param userId      propriétaire — jamais un paramètre reçu du client
     * @param workspaceId projet
     * @param message     la demande
     * @param originLabel libellé du client MCP, publié dans le fil (« Tour lancé depuis … »)
     */
    public LaunchResult launch(UUID userId, UUID workspaceId, String message, String originLabel) {
        String text = message == null ? "" : message.trim();
        LiveTurnRegistry.Entry entry = liveTurns.openOrSteer(userId, workspaceId, text);
        if (entry.receipt() != null) {
            // Un tour tourne déjà : l'envoi devient une précision (F-84 / SF-84-06), aucun tour neuf.
            return new LaunchResult(entry.turn().turnId(), entry.steered(),
                    entry.steered() ? entry.receipt().steerId() : null, !entry.steered());
        }
        LiveTurn turn = entry.turn();
        chatStreamExecutor.execute(() -> runLoop(turn, userId, workspaceId, text, originLabel));
        return new LaunchResult(turn.turnId(), false, null, false);
    }

    /**
     * La boucle tool-use, en tâche de fond, publiant chaque étape dans le tour vivant — reflet du
     * relais du flux web (F-84 / SF-84-01), sans l'émetteur SSE.
     */
    private void runLoop(LiveTurn turn, UUID userId, UUID workspaceId, String message,
            String originLabel) {
        String outcome = "echec_fatal";
        try {
            turn.publish("started", Map.of(
                    "turnId", turn.turnId().toString(), "startedAt", turn.startedAtMs()));
            // Origine MCP dans le fil (cadrage §6.6) : visible par tout spectateur du tour.
            turn.publish("text", Map.of("text",
                    "_Tour lancé depuis " + originLabel + " (MCP)._\n"));
            AtelierProgressListener listener = mcpListener(turn);
            String demand = message;
            for (;;) {
                AtelierChatResult result = chatService.chatStreaming(userId, workspaceId, demand, listener);
                if (result.interrupted()) {
                    turn.publishSteersDropped(turn.sealAndDrain(), "interrupted");
                    turn.publish("done", done(result, false));
                    break;
                }
                java.util.Optional<LiveTurn.Steer> followUp = turn.pollFollowUpOrSeal();
                turn.publish("done", done(result, followUp.isPresent()));
                if (followUp.isEmpty()) {
                    break;
                }
                turn.publish(LiveTurn.STEER_FOLLOWUP, Map.of("steerId", followUp.get().steerId()));
                demand = followUp.get().text();
            }
            outcome = "done";
        } catch (AtelierAccessDeniedException ex) {
            outcome = fail(turn, "forbidden");
        } catch (QuotaExceededException ex) {
            outcome = fail(turn, "quota_exceeded");
        } catch (ByokKeyRequiredException ex) {
            outcome = fail(turn, "byok_key_required");
        } catch (WorkspaceNotFoundException ex) {
            outcome = fail(turn, "workspace_not_found");
        } catch (AIProviderUnavailableException ex) {
            outcome = fail(turn, "provider_unavailable");
        } catch (AIProviderException ex) {
            outcome = fail(turn, "provider_error");
        } catch (RuntimeException ex) {
            log.warn("Échec inattendu de la boucle d'atelier MCP ({})", ex.getClass().getSimpleName());
            outcome = fail(turn, "internal_error");
        } finally {
            liveTurns.close(turn);
            log.info("Tour MCP clos (workspace={}, tour={}, événements={}, issue={})",
                    workspaceId, turn.turnId(), turn.cursor(), outcome);
        }
    }

    private AtelierProgressListener mcpListener(LiveTurn turn) {
        return new AtelierProgressListener() {
            @Override
            public void onAction(AtelierStepEvent step) {
                turn.publish("action", step);
            }

            @Override
            public void onText(String text) {
                turn.publish("text", Map.of("text", text));
            }

            @Override
            public void onOutput(String chunk) {
                turn.publish("output", Map.of("output", chunk));
            }

            @Override
            public void onProgress(long tokens) {
                turn.publish("progress", Map.of("tokens", tokens));
            }

            @Override
            public void onPlan(AtelierPlan plan) {
                turn.publish("plan", Map.of("steps", plan.steps().stream()
                        .map(s -> Map.of("title", s.title(), "status", s.status().label()))
                        .toList()));
            }

            @Override
            public void onConfirmRequest(AtelierConfirmRequest request) {
                // La demande devient l'ÉTAT du tour (F-84 / SF-84-03) : elle est LISTÉE par MCP,
                // jamais accordée (garde §6.1).
                turn.publishApprovalRequest(Map.of(
                        "toolUseId", request.toolUseId(), "tool", request.tool(),
                        "detail", request.detail(), "timeoutMs", request.timeoutMs()),
                        new PendingApproval(request.toolUseId(), request.tool(), request.detail(),
                                request.timeoutMs(), System.currentTimeMillis()));
            }

            @Override
            public void onConfirmResolved(AtelierConfirmResolved resolved) {
                turn.publishApprovalResolved(Map.of("toolUseId", resolved.toolUseId()),
                        resolved.toolUseId());
            }

            @Override
            public void onRunnerOffline(UUID hostId) {
                turn.publish("runner_offline", Map.of(
                        "hostId", hostId.toString(), "at", System.currentTimeMillis()));
            }

            @Override
            public List<AtelierSteer> takeSteers() {
                return turn.takeSteers().stream()
                        .map(steer -> new AtelierSteer(steer.steerId(), steer.text()))
                        .toList();
            }

            @Override
            public void onSteerApplied(AtelierSteer steer, int step) {
                turn.publish(LiveTurn.STEER_APPLIED, Map.of("steerId", steer.steerId(), "step", step));
            }
        };
    }

    private static Map<String, Object> done(AtelierChatResult result, boolean followUp) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reply", result.reply());
        map.put("actions", result.actions());
        map.put("messageId", result.messageId() == null ? null : result.messageId().toString());
        map.put("inputTokens", result.inputTokens());
        map.put("outputTokens", result.outputTokens());
        map.put("activeSeconds", result.activeSeconds());
        map.put("budgetReached", result.budgetReached());
        map.put("followUp", followUp);
        return map;
    }

    private static String fail(LiveTurn turn, String code) {
        turn.publishSteersDropped(turn.sealAndDrain(), code);
        turn.publish("error", Map.of("error", code));
        return code;
    }
}
