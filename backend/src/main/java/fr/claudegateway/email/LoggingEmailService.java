package fr.claudegateway.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Implémentation par défaut (dev/V1) de l'{@link EmailService} : <b>aucun SMTP réel</b>.
 * Elle journalise l'action et le lien à destination du développeur.
 *
 * <p><b>Arbitrage assumé</b> : le lien journalisé embarque un token à usage unique et court.
 * C'est le fallback dev explicitement retenu tant qu'aucun SMTP n'est branché (V1). Le marqueur
 * {@code [EMAIL:DEV-STUB]} signale que ce comportement est <b>réservé au développement</b> :
 * l'implémentation SMTP de production enverra l'e-mail et ne journalisera jamais le lien.
 * Aucun autre secret (mot de passe, hash) n'est journalisé.</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.email", name = "provider", havingValue = "logging", matchIfMissing = true)
public class LoggingEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailService.class);

    @Override
    public void sendEmailVerification(String toEmail, String verificationLink) {
        log.info("[EMAIL:DEV-STUB] Vérification d'adresse pour {} -> {}", toEmail, verificationLink);
    }

    @Override
    public void sendPasswordReset(String toEmail, String resetLink) {
        log.info("[EMAIL:DEV-STUB] Réinitialisation de mot de passe pour {} -> {}", toEmail, resetLink);
    }

    @Override
    public void sendReceptionAddressCode(String toEmail, String clientName, String code) {
        // Même arbitrage que les liens ci-dessus : sans SMTP, le développeur doit pouvoir lire le code.
        log.info("[EMAIL:DEV-STUB] Code de vérification de l'adresse de réception ({}) pour {} -> {}",
                clientName, toEmail, code);
    }

    @Override
    public void sendClientMail(ClientMailMessage message) {
        // Jamais le corps, même en dev : c'est une donnée du client. La taille suffit à voir que ça part.
        log.info("[EMAIL:DEV-STUB] Courriel du client ({}) pour {} ({} caractères, {} pièce(s) jointe(s))",
                message.displayName(), message.to(), message.text() == null ? 0 : message.text().length(),
                message.attachments().size());
    }
}
