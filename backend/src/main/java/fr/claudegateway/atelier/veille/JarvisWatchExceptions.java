package fr.claudegateway.atelier.veille;

/** Les erreurs des veilles (F-230), chacune avec un <b>code stable</b> rendu au client. */
public final class JarvisWatchExceptions {

    private JarvisWatchExceptions() {
    }

    /** Une erreur de veille : un code stable et un message lisible. */
    public abstract static class WatchException extends RuntimeException {
        private final String code;

        protected WatchException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Champ invalide (400 {@code watch_invalid}). */
    public static class InvalidWatchException extends WatchException {
        public InvalidWatchException(String message) {
            super("watch_invalid", message);
        }
    }

    /** Sonde refusée par le contrôle de sécurité D5 (400 {@code watch_unsafe}) : la nature, jamais la valeur. */
    public static class UnsafeProbeException extends WatchException {
        public UnsafeProbeException(String message) {
            super("watch_unsafe", message);
        }
    }

    /** Terminal qui ne peut pas porter de veille (400 {@code watch_unsupported}). */
    public static class WatchUnsupportedException extends WatchException {
        public WatchUnsupportedException(String message) {
            super("watch_unsupported", message);
        }
    }

    /** Veille introuvable pour ce propriétaire (404 {@code watch_not_found}). */
    public static class WatchNotFoundException extends WatchException {
        public WatchNotFoundException() {
            super("watch_not_found", "Veille introuvable.");
        }
    }

    /** État incompatible avec le geste (409, code porté). */
    public static class WatchConflictException extends WatchException {
        public WatchConflictException(String code, String message) {
            super(code, message);
        }
    }
}
