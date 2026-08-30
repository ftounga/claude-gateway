package fr.claudegateway.runner.exec;

/**
 * Aucune demande d'autorisation n'attend cette réponse (F-38 / SF-38-08) : identifiant inconnu,
 * demande déjà tranchée, délai expiré — ou décision arrivée sur un autre réplica que celui qui tient
 * le tour (contrat de messages §8).
 *
 * <p>Le dire explicitement (409) plutôt que de répondre « c'est fait » : sur un réglage de sécurité,
 * laisser croire qu'une autorisation est passée alors qu'elle s'est perdue serait le pire des
 * silences.</p>
 */
public class NoPendingConfirmationException extends RuntimeException {

    public NoPendingConfirmationException(String message) {
        super(message);
    }
}
