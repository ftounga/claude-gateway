package fr.claudegateway.radar;

/**
 * Le sujet a été absorbé par une fusion (F-99 / SF-99-03) : on ne le corrige plus, on ne le fusionne
 * ni ne le sépare plus. 409 ; sa page indique la cible.
 */
public class RadarSubjectMergedException extends RuntimeException {

    public RadarSubjectMergedException(String message) {
        super(message);
    }
}
