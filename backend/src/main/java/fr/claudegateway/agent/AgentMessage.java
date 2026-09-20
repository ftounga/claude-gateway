package fr.claudegateway.agent;

import java.util.List;

/**
 * Message d'une conversation d'agent (F-28), porteur de blocs de contenu (texte, tool_use,
 * tool_result). Neutre vis-à-vis du fournisseur.
 *
 * @param role    {@code user}, {@code assistant}, ou {@code system} pour une consigne glissée
 *                dans la conversation (F-134 / SF-134-05)
 * @param content blocs ordonnés du message ; <b>vide</b> pour une consigne d'effort
 * @param effort  niveau d'effort demandé à partir de ce point, ou {@code null} pour un message
 *                ordinaire (F-134 / SF-134-05)
 */
public record AgentMessage(String role, List<AgentContentBlock> content, String effort) {

    /** Forme ordinaire : un message porteur de contenu, sans consigne d'effort. */
    public AgentMessage(String role, List<AgentContentBlock> content) {
        this(role, content, null);
    }

    /** Message utilisateur texte. */
    public static AgentMessage userText(String text) {
        return new AgentMessage("user", List.of(new AgentContentBlock.Text(text)));
    }

    /** Message assistant (texte et/ou appels d'outils). */
    public static AgentMessage assistant(List<AgentContentBlock> content) {
        return new AgentMessage("assistant", content);
    }

    /** Message utilisateur porteur de résultats d'outils. */
    public static AgentMessage toolResults(List<AgentContentBlock> results) {
        return new AgentMessage("user", results);
    }

    /**
     * <b>Consigne d'effort</b> glissée dans la conversation (F-134 / SF-134-05) : elle change le
     * niveau de réflexion à partir de ce point, <b>sans invalider le cache</b>.
     *
     * <p><b>Pourquoi elle existe.</b> Le niveau d'effort se règle normalement à la racine de la
     * requête — mais le fournisseur le range <b>avant</b> la conversation, et le changer invalide
     * tout le cache des messages. Or nous le changions à chaque étape (F-118 le baisse en
     * continuation, F-119 le remonte sur difficulté) : l'optimisation de vitesse annulait
     * l'optimisation de coût.</p>
     *
     * <p>Le message ne porte <b>aucun contenu</b> : c'est ce qui le dispense des règles de
     * placement des autres messages système, et ce qui fait qu'il ne coûte presque rien.</p>
     */
    public static AgentMessage effort(String level) {
        return new AgentMessage("system", List.of(), level);
    }

    /** Vrai quand ce message n'est qu'une consigne d'effort, sans contenu à lire. */
    public boolean isEffortDirective() {
        return "system".equals(role) && effort != null && !effort.isBlank();
    }
}
