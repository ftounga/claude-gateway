package fr.claudegateway.atelier.routine;

/**
 * Les erreurs des routines (F-183), chacune avec un <b>code stable</b> rendu au client.
 */
public final class RoutineExceptions {

    private RoutineExceptions() {
    }

    /** Une erreur de routine : un code stable et un message lisible. */
    public abstract static class RoutineException extends RuntimeException {
        private final String code;

        protected RoutineException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Champ invalide (400 {@code routine_invalid}). */
    public static class InvalidRoutineException extends RoutineException {
        public InvalidRoutineException(String message) {
            super("routine_invalid", message);
        }
    }

    /** Sujet qui ne peut pas porter de routine (400 {@code routine_unsupported}). */
    public static class RoutineUnsupportedException extends RoutineException {
        public RoutineUnsupportedException(String message) {
            super("routine_unsupported", message);
        }
    }

    /** Routine introuvable pour ce propriétaire et ce sujet (404 {@code routine_not_found}). */
    public static class RoutineNotFoundException extends RoutineException {
        public RoutineNotFoundException() {
            super("routine_not_found", "Routine introuvable.");
        }
    }

    /** État incompatible avec le geste (409, code porté). */
    public static class RoutineConflictException extends RoutineException {
        public RoutineConflictException(String code, String message) {
            super(code, message);
        }
    }
}
