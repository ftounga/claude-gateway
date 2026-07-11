package fr.claudegateway.agent;

import java.util.List;

/**
 * Résultat d'UN tour d'agent (F-28).
 *
 * <p>Deux cas : soit l'assistant a terminé ({@code finished=true}, {@code text} porte la réponse),
 * soit il demande des outils ({@code finished=false}, {@code toolCalls} non vides). Les compteurs de
 * tokens servent à la comptabilisation du quota (F-10).</p>
 *
 * @param text         texte de l'assistant (réponse finale, ou texte intermédiaire éventuel)
 * @param toolCalls    appels d'outils demandés (vide si terminé)
 * @param finished     vrai si l'assistant a terminé (stop_reason end_turn)
 * @param inputTokens  tokens d'entrée consommés par ce tour
 * @param outputTokens tokens de sortie consommés par ce tour
 */
public record AgentTurn(String text, List<AgentToolCall> toolCalls, boolean finished,
        int inputTokens, int outputTokens) {
}
