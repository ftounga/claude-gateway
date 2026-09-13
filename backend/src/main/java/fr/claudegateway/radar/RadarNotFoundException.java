package fr.claudegateway.radar;

/**
 * Objet du Radar introuvable <b>dans le périmètre</b> (F-99). Inconnu ou d'un autre poste :
 * indiscernables, 404.
 */
public class RadarNotFoundException extends RuntimeException {

    public RadarNotFoundException(String message) {
        super(message);
    }
}
