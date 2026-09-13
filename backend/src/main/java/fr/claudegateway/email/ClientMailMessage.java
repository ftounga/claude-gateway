package fr.claudegateway.email;

/**
 * Un courriel que l'utilisateur s'envoie (F-110), prêt à partir : destinataire <b>déjà résolu</b> par la
 * gateway, versions texte et HTML déjà rendues.
 *
 * @param to          destinataire résolu (adresse vérifiée du client ou adresse du compte)
 * @param displayName nom affiché de l'expéditeur, par exemple « claude-gateway pour CAGIP »
 * @param subject     objet, une ligne
 * @param text        version texte
 * @param html        version HTML
 */
public record ClientMailMessage(String to, String displayName, String subject, String text, String html) {
}
