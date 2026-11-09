package fr.claudegateway.jarvis.fastbrain;

import java.util.List;

import fr.claudegateway.agent.AgentTool;

/**
 * <b>Le poste est éteint</b> (F-253 / SF-253-05, cadrage §3 #5, D3) : la consigne et les outils de la réponse d'Opus côté
 * passerelle quand une demande ne peut pas partir au poste. Figée (préfixe de cache) ; le contexte va dans le message.
 * <b>Lecture seule</b> : ni {@code deleguer} ni {@code afficher}, donc aucune action possible.
 */
public final class JarvisOfflinePrompt {

    private JarvisOfflinePrompt() {
    }

    static final String SYSTEM = """
            Tu es Jarvis, l'assistant vocal de Monsieur, que tu vouvoies. Le poste de travail du client est éteint : rien \
            ne peut y être lu ni exécuté. Tu réponds à sa demande avec seulement le contexte du message et tes outils de \
            lecture (pieces, agenda, etat, conversations).
            - Deux à trois phrases, à dire à voix haute : aucune liste, aucun titre, aucune mise en forme.
            - Tu n'inventes rien. Un fait d'état se donne avec sa date de relevé (« relevé hier à 17 h 42 »), jamais comme \
            s'il était actuel. Si tu ne sais pas, dis-le en une phrase.
            - Tu ne peux rien faire : si Monsieur demande une action (relancer, valider, déployer, envoyer), dis que ce \
            sera possible une fois le poste rallumé.
            - Monsieur vient d'entendre que le poste est éteint : ne le redis pas.
            - Tu ne commentes jamais tes outils ; seul leur résultat compte.
            """;

    /** Les outils de lecture, sans {@code deleguer} ni {@code afficher}. */
    static final List<AgentTool> TOOLS = List.of(JarvisFastBrainPrompt.piecesTool(), JarvisFastBrainPrompt.agendaTool(),
            JarvisFastBrainPrompt.etatTool(), JarvisFastBrainPrompt.conversationsTool());
}
