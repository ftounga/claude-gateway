package fr.claudegateway.radar;

/** Entrée invalide pour le registre du Radar (F-99) : champ vide, trop long, référence hors périmètre. */
public class InvalidRadarInputException extends RuntimeException {

    public InvalidRadarInputException(String message) {
        super(message);
    }
}
