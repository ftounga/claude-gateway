package fr.claudegateway.quota;

/**
 * Poste inconnu, ou appartenant à un autre compte (F-133 / SF-133-04). Rendu en 404.
 *
 * <p>Les deux cas rendent volontairement la <b>même</b> réponse : distinguer « n'existe pas » de
 * « ne vous appartient pas » dirait à un appelant quels identifiants existent chez les autres.</p>
 */
public class CostBudgetHostNotFoundException extends RuntimeException {
}
