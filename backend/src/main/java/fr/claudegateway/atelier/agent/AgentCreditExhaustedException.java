package fr.claudegateway.atelier.agent;

/**
 * Le compte du fournisseur d'agents n'a plus de crédit : aucune session ne peut être créée ni
 * poursuivie (F-30 SF-30-08).
 *
 * <p>Distincte d'un quota utilisateur épuisé ({@code QuotaExceededException}) : ici la limite est
 * celle de la <b>plateforme</b>, et réessayer ne peut pas aboutir tant que le compte n'est pas
 * rechargé. C'est précisément ce que le message générique « L'exécution a échoué, veuillez
 * réessayer » ne disait pas.</p>
 */
public class AgentCreditExhaustedException extends AgentProviderException {

    public AgentCreditExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
