package fr.claudegateway.runner.host;

/**
 * Le poste est possédé mais <b>pas activé dans l'espace</b> dont relève l'API appelée
 * (F-106 / SF-106-01), 409. La possession est toujours vérifiée avant : un poste d'autrui reste
 * « introuvable ».
 */
public class HostNotInSpaceException extends RuntimeException {

    private final ClientSpace space;

    public HostNotInSpaceException(ClientSpace space) {
        super(space == ClientSpace.VIGIE
                ? "Ce client n'est pas activé dans la Vigie."
                : "Ce client n'est pas activé dans la Forge.");
        this.space = space;
    }

    public ClientSpace getSpace() {
        return space;
    }
}
