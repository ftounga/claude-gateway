package fr.claudegateway.atelier;

import java.util.ArrayList;
import java.util.List;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;

/**
 * Sous-boucle d'<b>exploration</b> (F-39 / SF-39-14) : elle lit, cherche, et rend une réponse
 * courte — pour que lire quarante fichiers ne remplisse plus le contexte du travail principal.
 *
 * <p><b>Ce qu'elle absorbe ne remonte pas.</b> C'est tout l'intérêt : les fichiers lus et les étapes
 * intermédiaires restent chez elle, seule sa conclusion revient à l'agent principal comme résultat
 * d'outil.</p>
 *
 * <p><b>Lecture seule, et cela vaut aussi en cible {@code RUNNER}</b> (décision D2). Une sous-boucle
 * qui exécuterait une commande passerait par la porte de confirmation (SF-38-08), et l'utilisateur
 * se verrait demander d'autoriser une commande venue d'un agent dont il ignore l'existence.</p>
 *
 * <p><b>Sa dépense appartient au tour</b> (D4) : elle n'a ni quota propre, ni plafond propre. C'est
 * le seul modèle qui garde honnête le plafond par message — sans quoi déléguer serait un moyen de
 * le contourner.</p>
 */
class AtelierExploration {

    /** Une exploration qui n'aboutit pas en dix lectures ne se termine pas en vingt. */
    static final int MAX_ITERATIONS = 10;

    /** C'est un résumé : au-delà, on aurait aussi bien lu les fichiers. */
    static final int MAX_ANSWER_CHARS = 4_000;

    private static final String SYSTEM = """
            Tu explores un projet pour répondre à UNE question, et à elle seule.

            Tu ne peux que lire : liste les fichiers, lis-les, cherche dedans. Tu ne peux ni écrire, \
            ni exécuter de commande — n'essaie pas, et ne le proposes pas.

            Réponds court et factuel : ce que tu as trouvé, où, et rien d'autre. Cite les chemins et \
            les numéros de ligne utiles. Pas de préambule, pas de conclusion générale. Si tu ne \
            trouves pas, dis-le en une phrase.""";

    /** Issue d'une exploration : sa réponse, et ce qu'elle a consommé. */
    record Result(String answer, int inputTokens, int outputTokens) {
    }

    private AtelierExploration() {
    }

    /**
     * Mène l'exploration jusqu'à sa réponse, ou jusqu'à ses bornes.
     *
     * @param question    ce qu'on lui demande de trouver
     * @param scope       portée indicative (un chemin), ou {@code null}
     * @param readTools   outils de lecture, déjà filtrés par l'appelant
     * @param executor    exécution d'un outil de lecture, routée par l'appelant vers la bonne cible
     * @param stop        vrai quand le tour s'arrête (interruption, budget de temps) — consulté à
     *                    chaque itération, comme la boucle principale à ses frontières sûres
     */
    static Result run(AiAgentProvider provider, String model, String apiKey, String question,
            String scope, List<AgentTool> readTools, ToolExecutor executor,
            java.util.function.BooleanSupplier stop) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.userText(scope == null || scope.isBlank()
                ? question
                : question + "\n\nCommence par : " + scope));

        int inputTokens = 0;
        int outputTokens = 0;
        String answer = "";

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            if (stop.getAsBoolean()) {
                break;
            }
            AgentTurn turn = provider.nextTurn(
                    new AgentTurnRequest(model, SYSTEM, messages, readTools, apiKey));
            inputTokens += turn.inputTokens();
            outputTokens += turn.outputTokens();

            if (turn.truncated()) {
                answer = "Exploration interrompue : la réponse dépassait la taille maximale.";
                break;
            }
            if (turn.finished() || turn.toolCalls().isEmpty()) {
                answer = turn.text();
                break;
            }

            List<AgentContentBlock> assistantBlocks = new ArrayList<>();
            if (turn.text() != null && !turn.text().isBlank()) {
                assistantBlocks.add(new AgentContentBlock.Text(turn.text()));
            }
            List<AgentContentBlock> toolResults = new ArrayList<>();
            for (AgentToolCall call : turn.toolCalls()) {
                String callId = call.id() == null || call.id().isBlank()
                        ? java.util.UUID.randomUUID().toString()
                        : call.id();
                assistantBlocks.add(new AgentContentBlock.ToolUse(callId, call.name(), call.input()));
                ExecutedTool executed = executor.execute(call);
                toolResults.add(new AgentContentBlock.ToolResult(callId, executed.content(),
                        executed.isError()));
            }
            messages.add(AgentMessage.assistant(assistantBlocks));
            messages.add(AgentMessage.toolResults(toolResults));

            if (iteration == MAX_ITERATIONS - 1) {
                answer = (turn.text() == null || turn.text().isBlank() ? "" : turn.text() + "\n\n")
                        + "(exploration arrêtée à sa limite d'étapes ; la réponse peut être partielle)";
            }
        }

        if (answer == null || answer.isBlank()) {
            answer = "L'exploration n'a rien produit.";
        }
        if (answer.length() > MAX_ANSWER_CHARS) {
            answer = answer.substring(0, MAX_ANSWER_CHARS) + "\n… (réponse tronquée)";
        }
        return new Result(answer, inputTokens, outputTokens);
    }

    /** Exécution d'un outil de lecture, fournie par l'appelant (il seul sait router par cible). */
    interface ToolExecutor {
        ExecutedTool execute(AgentToolCall call);
    }

    /** Résultat d'un outil de la sous-boucle. */
    record ExecutedTool(String content, boolean isError) {
    }
}
