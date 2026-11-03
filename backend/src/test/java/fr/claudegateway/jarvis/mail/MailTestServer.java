package fr.claudegateway.jarvis.mail;

import org.springframework.test.context.DynamicPropertyRegistry;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * <b>Le serveur IMAP + SMTP de test</b> (F-242) — un GreenMail par JVM de test, ports dynamiques, jamais arrêté :
 * toutes les classes qui l'enregistrent partagent les mêmes réglages, donc le même contexte Spring. Chaque test crée
 * ses propres adresses (aléatoires) : aucune interférence entre tests.
 */
public final class MailTestServer {

    private static GreenMail server;

    private MailTestServer() {
    }

    public static synchronized GreenMail get() {
        if (server == null) {
            server = new GreenMail(new ServerSetup[] {
                    new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP).dynamicPort(),
                    new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP).dynamicPort() });
            server.start();
        }
        return server;
    }

    /** Pose hôtes et ports du serveur de test dans le contexte Spring. */
    public static void register(DynamicPropertyRegistry registry) {
        GreenMail mail = get();
        registry.add("app.jarvis.mail.imap-host", () -> "127.0.0.1");
        registry.add("app.jarvis.mail.imap-port", () -> mail.getImap().getPort());
        registry.add("app.jarvis.mail.smtp-host", () -> "127.0.0.1");
        registry.add("app.jarvis.mail.smtp-port", () -> mail.getSmtp().getPort());
        registry.add("app.jarvis.mail.ssl", () -> false);
    }

    /** Un utilisateur de test avec son mot de passe (une adresse neuve à chaque appel). */
    public static String newUser(String prefix, String password) {
        String address = prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        get().setUser(address, address, password);
        return address;
    }
}
