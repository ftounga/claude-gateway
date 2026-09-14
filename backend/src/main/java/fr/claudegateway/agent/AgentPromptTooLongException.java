package fr.claudegateway.agent;

/**
 * Le contexte envoyé au fournisseur <b>dépasse la fenêtre du modèle</b> (F-117 / SF-117-02).
 *
 * <p>Exception <b>neutre</b> : elle traduit le refus du fournisseur (côté Anthropic, un
 * {@code 400 "prompt is too long"}) en un signal du domaine, sans que la boucle
 * ({@code AtelierChatService}) ait à connaître le fournisseur (Provider Independence). Distincte d'un
 * refus temporaire {@code 429/529} — celui-ci est <b>rejoué tel quel</b> ; celle-ci demande de
 * <b>réduire le contexte</b> (compaction) avant de relancer.</p>
 *
 * <p>Jusqu'à F-117, ce 400 n'était pas rejouable et faisait mourir le tour sans filet. Le repli
 * (SF-117-02) l'intercepte, déclenche une compaction (SF-117-01) et relance une fois.</p>
 */
public class AgentPromptTooLongException extends RuntimeException {

    public AgentPromptTooLongException(String message) {
        super(message);
    }

    public AgentPromptTooLongException(String message, Throwable cause) {
        super(message, cause);
    }
}
