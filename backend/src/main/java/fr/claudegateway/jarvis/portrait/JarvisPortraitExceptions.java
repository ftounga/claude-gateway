package fr.claudegateway.jarvis.portrait;

/** Les erreurs du portrait (F-241 / SF-241-01), traduites par {@link JarvisPortraitExceptionHandler}. */
public final class JarvisPortraitExceptions {

    private JarvisPortraitExceptions() {
    }

    /** Une erreur métier avec son code. */
    public abstract static class PortraitException extends RuntimeException {
        private final String code;

        protected PortraitException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 400 — une entrée invalide (clé inconnue, souvenir vide ou trop long, origine inconnue). */
    public static class InvalidPortraitException extends PortraitException {
        public InvalidPortraitException(String message) {
            super("invalid_portrait", message);
        }
    }

    /** 400 — le portrait dépasse la borne de 1 500 jetons (D4). */
    public static class PortraitTooLargeException extends PortraitException {
        public PortraitTooLargeException(String message) {
            super("portrait_too_large", message);
        }
    }

    /** 409 — 30 souvenirs au plus (D2). */
    public static class SouvenirsFullException extends PortraitException {
        public SouvenirsFullException() {
            super("souvenirs_full", JarvisPortraitRules.MEMORY_FULL);
        }
    }

    /** 404 — un souvenir introuvable sous ce compte (celui d'autrui compris). */
    public static class SouvenirNotFoundException extends PortraitException {
        public SouvenirNotFoundException() {
            super("not_found", "Souvenir introuvable.");
        }
    }
}
