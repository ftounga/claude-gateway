package fr.claudegateway.atelier.proposal;

/** Erreur d'une proposition de gouvernance (F-177 / SF-177-02) ; le message est lisible tel quel. */
public class InvalidProposalException extends RuntimeException {

    public InvalidProposalException(String message) {
        super(message);
    }
}
