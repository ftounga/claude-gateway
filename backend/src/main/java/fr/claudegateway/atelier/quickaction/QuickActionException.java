package fr.claudegateway.atelier.quickaction;

/**
 * Les erreurs des actions rapides (F-275), avec un <b>code stable</b> rendu au client et un message lisible (aussi
 * rendu tel quel au modèle par l'outil {@code action_rapide}).
 */
public class QuickActionException extends RuntimeException {

    /** Les trois issues : champ invalide (400), introuvable (404), libellé déjà pris (409). */
    public enum Kind { INVALID, NOT_FOUND, CONFLICT }

    private final Kind kind;

    public QuickActionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public static QuickActionException invalid(String message) {
        return new QuickActionException(Kind.INVALID, message);
    }

    public static QuickActionException notFound(String message) {
        return new QuickActionException(Kind.NOT_FOUND, message);
    }

    public static QuickActionException conflict(String message) {
        return new QuickActionException(Kind.CONFLICT, message);
    }

    public Kind kind() {
        return kind;
    }

    /** Le code stable rendu au client. */
    public String code() {
        return switch (kind) {
            case INVALID -> "QUICK_ACTION_INVALID";
            case NOT_FOUND -> "QUICK_ACTION_NOT_FOUND";
            case CONFLICT -> "QUICK_ACTION_CONFLICT";
        };
    }
}
