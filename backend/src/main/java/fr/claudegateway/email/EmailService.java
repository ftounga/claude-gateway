package fr.claudegateway.email;

/**
 * Abstraction d'envoi d'e-mails transactionnels de la plateforme.
 *
 * <p>V1 : une seule implémentation par défaut ({@link LoggingEmailService}) qui journalise
 * l'action sans SMTP réel. Une implémentation SMTP (configurée via {@code MAIL_*}) pourra la
 * remplacer plus tard sans toucher au code métier (les services dépendent de cette interface).</p>
 */
public interface EmailService {

    /**
     * Envoie à {@code toEmail} le lien permettant de vérifier son adresse.
     *
     * @param toEmail          adresse destinataire
     * @param verificationLink URL complète de vérification (embarque un token à usage unique)
     */
    void sendEmailVerification(String toEmail, String verificationLink);

    /**
     * Envoie à {@code toEmail} le lien permettant de réinitialiser son mot de passe.
     *
     * @param toEmail   adresse destinataire
     * @param resetLink URL complète de réinitialisation (embarque un token à usage unique)
     */
    void sendPasswordReset(String toEmail, String resetLink);

    /**
     * Envoie à {@code toEmail} le code à 6 chiffres qui vérifie l'adresse de réception d'un client
     * (F-110 / SF-110-01). Délais SMTP bornés (F-77) : un relais lent échoue en quelques secondes.
     *
     * @param toEmail    adresse à vérifier
     * @param clientName nom du client (poste), cité dans l'objet
     * @param code       code à usage unique, jamais journalisé en production
     */
    void sendReceptionAddressCode(String toEmail, String clientName, String code);

    /**
     * Envoie un courriel que l'utilisateur s'envoie à lui-même (F-110 / SF-110-02), en
     * {@code multipart/alternative} (texte + HTML), avec le nom affiché de l'expéditeur. Appelé par le
     * travailleur de la file, jamais dans un tour : délais SMTP bornés (F-77).
     *
     * @param message courriel prêt à partir, destinataire déjà résolu par la gateway
     * @throws org.springframework.mail.MailException si le relais refuse ou ne répond pas
     */
    void sendClientMail(ClientMailMessage message);
}
