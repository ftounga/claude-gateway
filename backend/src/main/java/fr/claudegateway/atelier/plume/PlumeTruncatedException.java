package fr.claudegateway.atelier.plume;

/**
 * La demande enrichie a dépassé le plafond de sortie, même au nouvel essai (F-202 / SF-202-01) : on
 * ne rend <b>jamais</b> un fragment.
 */
public class PlumeTruncatedException extends RuntimeException {

    public PlumeTruncatedException() {
        super("Demande enrichie tronquée");
    }
}
