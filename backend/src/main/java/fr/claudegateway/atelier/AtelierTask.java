package fr.claudegateway.atelier;

import java.util.ArrayList;
import java.util.List;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;

/**
 * Sous-boucle <b>d'action</b> {@code task} (F-150 / SF-150-02) : le pendant écrivain d'{@link
 * AtelierExploration}. Elle lit, écrit et exécute des commandes — <b>dans un worktree git isolé</b>
 * matérialisé côté runner (SF-150-01) — pour accomplir UNE sous-tâche, puis rend une <b>synthèse</b>.
 *
 * <p><b>Ce qu'elle absorbe ne remonte pas</b> (D5) : les fichiers lus/écrits et les traces d'outils
 * du worktree restent chez elle ; seule sa synthèse revient à la boucle principale comme résultat
 * d'outil. C'est le même levier de rentabilité qu'{@code explore} (coût en N², F-134), mais pour un
 * agent qui écrit.</p>
 *
 * <p><b>Gateway-First</b> : on réutilise le <b>patron</b> de boucle tool-use (comme {@code explore}),
 * on ne réimplémente pas un moteur d'IA. L'aiguillage réel des outils vers le runner (avec le worktree
 * comme projet), la porte de confirmation, l'audit et la cible RUNNER sont fournis par l'appelant via
 * l'{@link Executor} — cette classe ne connaît ni WebSocket, ni permission, ni chemin de worktree.</p>
 *
 * <p><b>Variante en lecture seule</b> (F-121 / SF-121-14) : avec {@code readOnly}, la même sous-boucle
 * sert une sous-tâche qui ne fait que LIRE — l'appelant ne lui donne que des outils de lecture et ne
 * lui monte aucun worktree (rien n'écrit : il n'y a rien à isoler). C'est cette garantie qui permet
 * d'en exécuter plusieurs <b>en parallèle</b> dans un même tour.</p>
 *
 * <p><b>Cache F-134</b> : {@link #SYSTEM} est <b>stable</b> ; rien de volatil (taskId, chemin de
 * worktree, horodatage) n'y entre — ces valeurs voyagent dans le <b>message</b>, jamais dans la
 * consigne système. <b>Sa dépense appartient au tour</b> (D4) : l'appelant l'additionne aux compteurs
 * du tour ; elle n'a ni quota ni plafond propres.</p>
 */
final class AtelierTask {

    /** Une sous-tâche qui n'aboutit pas en trente étapes ne se termine pas en soixante. */
    static final int MAX_ITERATIONS = 30;

    /** C'est une synthèse : au-delà, elle recopierait le travail au lieu de le résumer. */
    static final int MAX_ANSWER_CHARS = 6_000;

    private static final String SYSTEM = """
            Tu es un sous-agent qui accomplit UNE sous-tâche isolée, et elle seule. Tu travailles dans \
            un worktree git ISOLÉ, une copie de travail à part : tes écritures et tes commandes n'y \
            touchent jamais la copie de travail réelle de l'utilisateur.

            Tu disposes de la panoplie complète : lire, écrire, éditer et exécuter des commandes. Lis \
            un fichier avant de le modifier ; ne suppose rien d'un fichier que tu n'as pas lu.

            Concentre-toi sur la sous-tâche demandée, sans déborder. Quand elle est faite, réponds par \
            une SYNTHÈSE COURTE et factuelle : ce que tu as changé (fichiers, en une ligne chacun), ce \
            que tu as vérifié, et ce qui reste éventuellement à faire. Pas de préambule, pas de \
            recopie de fichiers entiers. Si tu n'y arrives pas, dis-le en une phrase.""";

    /**
     * Consigne de la sous-tâche <b>en lecture seule</b> (F-121 / SF-121-14). Elle dit la vérité de sa
     * panoplie : pas de worktree (rien n'écrit, il n'y a rien à isoler), pas d'écriture, pas de
     * commande. Littéral <b>stable</b> comme {@link #SYSTEM} — rien de volatil n'y entre (cache F-134).
     */
    private static final String SYSTEM_READ_ONLY = """
            Tu es un sous-agent qui accomplit UNE sous-tâche isolée, et elle seule, EN LECTURE SEULE.

            Tu peux lire et chercher dans le projet ; tu ne peux ni écrire, ni éditer, ni exécuter de \
            commande. N'affirme rien d'un fichier que tu n'as pas lu.

            Concentre-toi sur la sous-tâche demandée, sans déborder. Quand elle est faite, réponds par \
            une SYNTHÈSE COURTE et factuelle : ce que tu as constaté (fichiers concernés, en une ligne \
            chacun), sur quoi tu t'appuies, et ce qui reste éventuellement à vérifier. Pas de \
            préambule, pas de recopie de fichiers entiers. Si tu n'y arrives pas, dis-le en une phrase.""";

    /** Issue d'une sous-tâche : sa synthèse, et ce qu'elle a consommé. */
    record Result(String answer, int inputTokens, int outputTokens, int cacheReadTokens,
            int cacheWriteTokens) {
    }

    private AtelierTask() {
    }

    /**
     * Mène la sous-tâche jusqu'à sa synthèse, ou jusqu'à ses bornes.
     *
     * @param prompt    la sous-tâche à accomplir
     * @param scope     portée indicative (un chemin), ou {@code null}
     * @param tools     panoplie déjà construite par l'appelant (complète et routée vers le worktree,
     *                  ou de lecture seule quand {@code readOnly})
     * @param readOnly  vrai pour une sous-tâche <b>en lecture seule</b> (F-121 / SF-121-14) : la
     *                  consigne système le dit, et l'appelant ne lui donne que des outils de lecture
     * @param executor  exécution d'un outil, routée par l'appelant vers le runner sur le worktree
     * @param stop      vrai quand le tour s'arrête (interruption, budget de temps) — consulté à chaque
     *                  itération, comme la boucle principale à ses frontières sûres (D7 partagé)
     * @param reasoning raisonnement demandé à chaque tour de la sous-boucle (F-119 intacte)
     */
    static Result run(AiAgentProvider provider, String model, String apiKey, String prompt,
            String scope, List<AgentTool> tools, boolean readOnly, Executor executor,
            java.util.function.BooleanSupplier stop, AgentReasoning reasoning) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.userText(scope == null || scope.isBlank()
                ? prompt
                : prompt + "\n\nCommence par : " + scope));

        int inputTokens = 0;
        int outputTokens = 0;
        int cacheReadTokens = 0;
        int cacheWriteTokens = 0;
        String answer = "";

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            if (stop.getAsBoolean()) {
                answer = answer.isBlank() ? "Sous-tâche interrompue avant sa fin." : answer;
                break;
            }
            AgentTurn turn = provider.nextTurn(
                    new AgentTurnRequest(model, readOnly ? SYSTEM_READ_ONLY : SYSTEM, messages, tools, apiKey,
                            reasoning == null ? AgentReasoning.none() : reasoning));
            inputTokens += turn.inputTokens();
            outputTokens += turn.outputTokens();
            cacheReadTokens += turn.cacheReadTokens();
            cacheWriteTokens += turn.cacheWriteTokens();

            if (turn.truncated()) {
                answer = "Sous-tâche interrompue : la réponse dépassait la taille maximale.";
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
                        + "(sous-tâche arrêtée à sa limite d'étapes ; le travail peut être partiel)";
            }
        }

        if (answer == null || answer.isBlank()) {
            answer = "La sous-tâche n'a produit aucune synthèse.";
        }
        if (answer.length() > MAX_ANSWER_CHARS) {
            answer = answer.substring(0, MAX_ANSWER_CHARS) + "\n… (synthèse tronquée)";
        }
        return new Result(answer, inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens);
    }

    /** Exécution d'un outil de la sous-tâche, fournie par l'appelant (lui seul sait router + garder). */
    interface Executor {
        ExecutedTool execute(AgentToolCall call);
    }

    /** Résultat d'un outil de la sous-boucle. */
    record ExecutedTool(String content, boolean isError) {
    }
}
