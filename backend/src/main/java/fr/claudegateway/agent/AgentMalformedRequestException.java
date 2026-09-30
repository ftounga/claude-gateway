package fr.claudegateway.agent;

/**
 * La requête envoyée au fournisseur est <b>structurellement invalide</b> — séquence de messages
 * malformée : rôles non alternés, message de tête d'un mauvais rôle, bloc de contenu vide
 * (F-117 / SF-117-08).
 *
 * <p>Exception <b>neutre</b> : elle traduit le refus du fournisseur (côté Anthropic, un
 * {@code 400 invalid_request} portant sur {@code messages}/{@code role}/{@code content}) en un signal
 * du domaine, sans que la boucle ({@code AtelierChatService}) ait à connaître le fournisseur
 * (Provider Independence). Cousine de {@link AgentPromptTooLongException} — mais distincte : ce n'est
 * pas un dépassement de fenêtre (à compacter), c'est une <b>forme</b> à réparer. Distincte aussi d'un
 * refus temporaire {@code 429/529}, rejoué tel quel.</p>
 *
 * <p>La boucle l'intercepte : elle rebâtit la conversation depuis la base (le rejeu est réassaini par
 * {@link AgentMessageSanitizer}) et relance <b>une</b> fois ; si l'échec persiste, un message clair est
 * rendu — plus jamais un {@code provider_error} sans issue, ni la cascade d'un tour échoué qui empile
 * une séquence encore plus invalide.</p>
 */
public class AgentMalformedRequestException extends RuntimeException {

    public AgentMalformedRequestException(String message) {
        super(message);
    }

    public AgentMalformedRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
