package fr.claudegateway.radar;

/**
 * Le runner du poste n'a pas pu répondre au Radar (F-100) : hors ligne, délai dépassé, réponse
 * illisible. Rien n'est enregistré. 409.
 */
public class RadarRunnerUnavailableException extends RuntimeException {

    public RadarRunnerUnavailableException(String message) {
        super(message);
    }
}
