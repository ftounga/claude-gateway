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
}
