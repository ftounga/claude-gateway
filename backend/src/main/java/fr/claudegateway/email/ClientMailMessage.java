package fr.claudegateway.email;

import java.util.List;

/**
 * Un courriel que l'utilisateur s'envoie (F-110), prêt à partir : destinataire <b>déjà résolu</b> par la
 * gateway, versions texte et HTML déjà rendues, pièces jointes déjà lues et contrôlées (SF-110-03).
 *
 * @param to          destinataire résolu (adresse vérifiée du client ou adresse du compte)
 * @param displayName nom affiché de l'expéditeur, par exemple « claude-gateway pour CAGIP »
 * @param subject     objet, une ligne
 * @param text        version texte
 * @param html        version HTML
 * @param attachments pièces jointes, dans l'ordre ; vide s'il n'y en a pas
 */
public record ClientMailMessage(String to, String displayName, String subject, String text, String html,
        List<Attachment> attachments) {

    public ClientMailMessage {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** Un courriel sans pièce jointe. */
    public ClientMailMessage(String to, String displayName, String subject, String text, String html) {
        this(to, displayName, subject, text, html, List.of());
    }

    /**
     * Une pièce jointe (F-110 / SF-110-03).
     *
     * @param name        nom du fichier tel qu'il apparaîtra dans la messagerie
     * @param contentType type MIME
     * @param content     octets
     */
    public record Attachment(String name, String contentType, byte[] content) {
    }
}
