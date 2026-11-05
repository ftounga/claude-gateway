package fr.claudegateway.atelier.deposit;

import java.time.Instant;
import java.util.UUID;

import fr.claudegateway.atelier.Workspace;

/**
 * <b>Un fichier vient d'être déposé</b> (F-247 / SF-247-12) : appelé après l'écriture et l'enregistrement du dépôt,
 * dans le fil de la requête. L'implémentation <b>ne fait qu'une remise en mémoire</b> (porte Z1b : le dépôt ne doit
 * pas ralentir) et ne lève jamais.
 */
public interface DepositReceivedListener {

    /**
     * @param workspace   le sujet du dépôt (déjà vérifié au compte : {@code requireOwned})
     * @param depositId   l'identifiant du dépôt enregistré
     * @param contentType le type annoncé par le client (peut être {@code null})
     * @param bytes       les octets reçus — à copier si on les garde
     * @param receivedAt  l'heure de réception
     */
    void received(Workspace workspace, UUID depositId, String contentType, byte[] bytes, Instant receivedAt);
}
