package fr.claudegateway.jarvis.mail;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>Le courrier de Jarvis</b> (F-242) — réglages. Préréglage Google (D2) : {@code imap.gmail.com:993} et
 * {@code smtp.gmail.com:465} en SSL, valables pour gmail.com et Workspace. Les hôtes ne sont surchargés que par les
 * tests (serveur local) : l'API n'a aucun champ serveur.
 *
 * <p>Un seul constructeur (record compact) : un second casserait le démarrage du contexte Spring.</p>
 *
 * @param enabled     coupe-circuit de la relève ({@code APP_JARVIS_MAIL_ENABLED}, D4)
 * @param imapHost    hôte IMAP
 * @param imapPort    port IMAP
 * @param smtpHost    hôte SMTP
 * @param smtpPort    port SMTP
 * @param ssl         SSL implicite (vrai chez Google ; faux pour un serveur de test en clair)
 * @param timeout     délai de connexion et de lecture
 * @param maxAccounts boîtes par compte (D18, défaut 2)
 * @param pollEvery   période de relève d'une boîte (D4, défaut 5 min)
 */
@ConfigurationProperties(prefix = "app.jarvis.mail")
public record JarvisMailProperties(
        Boolean enabled,
        String imapHost,
        Integer imapPort,
        String smtpHost,
        Integer smtpPort,
        Boolean ssl,
        Duration timeout,
        Integer maxAccounts,
        Duration pollEvery) {

    public static final String GOOGLE_IMAP = "imap.gmail.com";
    public static final String GOOGLE_SMTP = "smtp.gmail.com";

    public JarvisMailProperties {
        enabled = enabled == null || enabled;
        imapHost = imapHost == null || imapHost.isBlank() ? GOOGLE_IMAP : imapHost.strip();
        imapPort = imapPort == null || imapPort <= 0 ? 993 : imapPort;
        smtpHost = smtpHost == null || smtpHost.isBlank() ? GOOGLE_SMTP : smtpHost.strip();
        smtpPort = smtpPort == null || smtpPort <= 0 ? 465 : smtpPort;
        ssl = ssl == null || ssl;
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(20) : timeout;
        maxAccounts = maxAccounts == null || maxAccounts <= 0 ? 2 : maxAccounts;
        pollEvery = pollEvery == null || pollEvery.isNegative() || pollEvery.isZero() ? Duration.ofMinutes(5)
                : pollEvery;
    }

    /** Préréglage Google, relève allumée. */
    public static JarvisMailProperties defaults() {
        return new JarvisMailProperties(null, null, null, null, null, null, null, null, null);
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean isSsl() {
        return Boolean.TRUE.equals(ssl);
    }
}
