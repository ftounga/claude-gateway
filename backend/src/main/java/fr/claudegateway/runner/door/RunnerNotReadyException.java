package fr.claudegateway.runner.door;

/**
 * Le poste n'est pas en état d'exécuter ce tour (F-161 / SF-161-01).
 *
 * <p><b>409, pas 400</b> : la requête est valide, c'est l'<b>état de la machine</b> qui ne l'est
 * pas. Et c'est un refus <b>gratuit</b> — il tombe avant le moindre appel fournisseur, ce qui est
 * toute la raison d'être de cette porte.</p>
 */
public class RunnerNotReadyException extends RuntimeException {

    private final String code;

    public RunnerNotReadyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
