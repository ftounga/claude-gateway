package fr.claudegateway.governance;

/**
 * Un paquet refusé à la rédaction (F-51 / SF-51-01) → 400.
 *
 * <p>Le message <b>nomme le champ fautif</b> : il est lu par l'admin en train de rédiger, qui doit
 * savoir quoi corriger sans relire la spec.</p>
 */
public class InvalidGovernancePackageException extends RuntimeException {

    public InvalidGovernancePackageException(String message) {
        super(message);
    }
}
