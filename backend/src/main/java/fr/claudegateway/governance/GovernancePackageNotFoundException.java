package fr.claudegateway.governance;

/** Paquet inexistant, ou non publié pour qui n'est pas admin (F-51 / SF-51-01) → 404. */
public class GovernancePackageNotFoundException extends RuntimeException {

    public GovernancePackageNotFoundException(String message) {
        super(message);
    }
}
