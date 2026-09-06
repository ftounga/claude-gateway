package fr.claudegateway.agent;

import java.util.List;

/**
 * Résultat d'UN tour d'agent (F-28).
 *
 * <p>Deux cas : soit l'assistant a terminé ({@code finished=true}, {@code text} porte la réponse),
 * soit il demande des outils ({@code finished=false}, {@code toolCalls} non vides). Les compteurs de
 * tokens servent à la comptabilisation du quota (F-10).</p>
 *
 * <p><b>Troisième cas, à ne pas confondre avec le premier</b> (SF-28-18) : la réponse a été
 * <b>coupée</b> au plafond de tokens de sortie. Elle est alors incomplète — une phrase d'intention
 * sans le bloc {@code tool_use} qui allait suivre, ou un appel d'outil dont les arguments s'arrêtent
 * au milieu. {@code finished} vaut vrai (le fournisseur n'attend rien de nous), mais rien de ce
 * qu'elle contient n'est exploitable : c'est ce que dit {@code truncated}.</p>
 *
 * @param text         texte de l'assistant (réponse finale, ou texte intermédiaire éventuel)
 * @param toolCalls    appels d'outils demandés (vide si terminé)
 * @param finished     vrai si l'assistant a terminé (stop_reason end_turn)
 * @param inputTokens  tokens d'entrée consommés par ce tour
 * @param outputTokens tokens de sortie consommés par ce tour
 * @param truncated    vrai si la réponse a été coupée au plafond de sortie ({@code max_tokens}) :
 *                     son contenu est incomplet et ne doit être ni exécuté, ni pris pour une réponse
 * @param reasoning    blocs de raisonnement rendus par ce tour, dans leur ordre d'émission (F-39 /
 *                     SF-39-10). À remettre <b>en tête</b> du message assistant rejoué à l'itération
 *                     suivante, sans les modifier : le fournisseur vérifie leur signature
 * @see #truncated()
 */
public record AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
        int inputTokens, int outputTokens, boolean truncated, List<AgentContentBlock> reasoning) {

    public AgentTurn {
        reasoning = reasoning == null ? List.of() : List.copyOf(reasoning);
    }

    /** Tour sans raisonnement — forme conservée pour les appelants qui l'attendent. */
    public AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
            int inputTokens, int outputTokens, boolean truncated) {
        this(text, toolCalls, finished, inputTokens, outputTokens, truncated, List.of());
    }

    /** Tour complet (non tronqué) — forme historique, conservée pour les appelants qui l'attendent. */
    public AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
            int inputTokens, int outputTokens) {
        this(text, toolCalls, finished, inputTokens, outputTokens, false, List.of());
    }
}
