package fr.claudegateway.atelier.plume;

/** Le coupe-circuit de Plume est fermé (F-193 / SF-193-01) : aucun appel n'est parti. */
public class PlumeDisabledException extends RuntimeException {

    public PlumeDisabledException() {
        super("Plume est désactivé");
    }
}
