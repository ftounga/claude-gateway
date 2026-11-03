package fr.claudegateway.jarvis.mail;

/** Les erreurs du courrier de Jarvis (F-242), traduites par {@link JarvisMailExceptionHandler}. */
public final class JarvisMailExceptions {

    private JarvisMailExceptions() {
    }

    /** Une erreur métier avec son code. Le message est neutre : jamais de secret, jamais de contenu de mail. */
    public abstract static class MailException extends RuntimeException {
        private final String code;

        protected MailException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 400 — une entrée invalide (adresse, libellé, mot de passe). */
    public static class InvalidMailAccountException extends MailException {
        public InvalidMailAccountException(String message) {
            super("invalid_mail_account", message);
        }
    }

    /** 400 — deux boîtes au plus (D18). */
    public static class MailAccountsFullException extends MailException {
        public MailAccountsFullException(int max) {
            super("mail_accounts_full", max + " boîtes au plus : révoquez-en une pour en relier une autre.");
        }
    }

    /** 400 — le serveur refuse le mot de passe d'application. */
    public static class MailAuthException extends MailException {
        public MailAuthException() {
            super("mail_auth_failed", "Google refuse ce mot de passe d'application. Vérifiez l'adresse, que la "
                    + "validation en deux étapes est active, puis créez un nouveau mot de passe d'application.");
        }
    }

    /** 503 — le serveur ne répond pas (réseau, délai). */
    public static class MailUnavailableException extends MailException {
        public MailUnavailableException() {
            super("mail_unavailable", "La boîte ne répond pas pour l'instant. Réessayez dans un moment.");
        }
    }

    /** 404 — une boîte ou un courrier introuvable sous ce compte (celui d'autrui compris). */
    public static class MailNotFoundException extends MailException {
        public MailNotFoundException() {
            super("not_found", "Introuvable.");
        }
    }
}
