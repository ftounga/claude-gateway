package fr.claudegateway.radar;

/**
 * <b>Pas de fait sans preuve</b> (F-99, cadrage §4.1) : l'écriture demandée n'a pas de preuve, ou
 * désigne une preuve qui n'est pas du même périmètre. Rien n'est écrit.
 */
public class RadarEvidenceRequiredException extends RuntimeException {

    public RadarEvidenceRequiredException(String message) {
        super(message);
    }
}
