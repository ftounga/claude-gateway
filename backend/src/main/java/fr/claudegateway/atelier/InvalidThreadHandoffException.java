package fr.claudegateway.atelier;

/**
 * Passation corrigée refusée (F-117 / SF-117-09) : vide, trop longue, ou privée d'une des cinq rubriques
 * du gabarit. Mappée en {@code 400 invalid_handoff} ; le message dit quoi corriger.
 */
public class InvalidThreadHandoffException extends RuntimeException {

    public InvalidThreadHandoffException(String message) {
        super(message);
    }
}
