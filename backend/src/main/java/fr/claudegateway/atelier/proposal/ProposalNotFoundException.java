package fr.claudegateway.atelier.proposal;

/** Erreur d'une proposition de gouvernance (F-177 / SF-177-02) ; le message est lisible tel quel. */
public class ProposalNotFoundException extends RuntimeException {

    public ProposalNotFoundException(String message) {
        super(message);
    }
}
