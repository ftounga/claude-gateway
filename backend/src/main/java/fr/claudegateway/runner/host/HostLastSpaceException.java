package fr.claudegateway.runner.host;

/**
 * Retrait refusé : c'est le <b>dernier</b> espace du poste (F-106 / SF-106-01), 409.
 *
 * <p>Un poste retiré de ses deux espaces deviendrait invisible tout en restant facturable. Pour s'en
 * séparer, il reste la clôture de mission ou la suppression du poste.</p>
 */
public class HostLastSpaceException extends RuntimeException {

    public HostLastSpaceException() {
        super("Un client vit dans au moins un espace. Pour vous en séparer, clôturez la mission ou "
                + "supprimez le poste.");
    }
}
