package fr.claudegateway.mail;

/**
 * <b>Le reçu d'un courriel mis en file</b> (F-110 / SF-110-02) : ce que porte le bloc « Courriel envoyé » du
 * terminal. Jamais le corps.
 *
 * @param emailId           identifiant de la ligne, pour relire l'état de remise
 * @param recipient         destinataire résolu par la gateway
 * @param recipientVerified faux quand c'est le repli sur l'adresse du compte
 * @param clientName        nom du client
 * @param subject           objet
 * @param attachmentCount   nombre de pièces jointes
 * @param status            état à l'émission du reçu
 */
public record ClientMailReceipt(String emailId, String recipient, boolean recipientVerified, String clientName,
        String subject, int attachmentCount, String status) {

    static ClientMailReceipt of(ClientEmail email) {
        return new ClientMailReceipt(email.getId() == null ? null : email.getId().toString(), email.getRecipient(),
                email.isRecipientVerified(), email.getClientName(), email.getSubject(), email.getAttachmentCount(),
                email.getStatus() == null ? null : email.getStatus().name());
    }
}
