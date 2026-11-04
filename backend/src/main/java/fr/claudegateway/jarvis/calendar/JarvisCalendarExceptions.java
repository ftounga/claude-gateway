package fr.claudegateway.jarvis.calendar;

/** Les erreurs des agendas de Jarvis (F-243), traduites par {@link JarvisCalendarExceptionHandler}. */
public final class JarvisCalendarExceptions {

    private JarvisCalendarExceptions() {
    }

    /** Une erreur métier avec son code. Le message est neutre : jamais l'adresse, jamais un contenu d'agenda. */
    public abstract static class CalendarException extends RuntimeException {
        private final String code;

        protected CalendarException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 400 — une entrée invalide (adresse, libellé). */
    public static class InvalidFeedException extends CalendarException {
        public InvalidFeedException(String message) {
            super("invalid_calendar_feed", message);
        }
    }

    /** 400 — trop d'agendas. */
    public static class FeedsFullException extends CalendarException {
        public FeedsFullException(int max) {
            super("calendar_feeds_full", max + " agendas au plus : retirez-en un pour en ajouter un autre.");
        }
    }

    /** 400 — Google répond 404 : l'adresse est fausse ou a été réinitialisée. */
    public static class FeedGoneException extends CalendarException {
        public FeedGoneException() {
            super("calendar_feed_gone", "Google ne reconnaît pas cette adresse : elle a peut-être été réinitialisée. "
                    + "Copiez à nouveau l'« adresse secrète au format iCal » dans les paramètres de l'agenda.");
        }
    }

    /** 400 — le contenu n'est pas un agenda iCal. */
    public static class NotACalendarException extends CalendarException {
        public NotACalendarException() {
            super("calendar_unreadable", "Cette adresse ne donne pas un agenda iCal lisible.");
        }
    }

    /** 503 — Google ne répond pas (réseau, délai, erreur serveur). */
    public static class FeedUnavailableException extends CalendarException {
        public FeedUnavailableException() {
            super("calendar_unavailable", "L'agenda ne répond pas pour l'instant. Réessayez dans un moment.");
        }
    }

    /** 404 — un agenda introuvable sous ce compte (celui d'autrui compris). */
    public static class FeedNotFoundException extends CalendarException {
        public FeedNotFoundException() {
            super("not_found", "Introuvable.");
        }
    }
}
