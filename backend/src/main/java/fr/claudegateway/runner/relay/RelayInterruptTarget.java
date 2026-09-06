package fr.claudegateway.runner.relay;

import java.util.UUID;

/**
 * Ce qu'un pod sait faire, <b>localement</b>, quand une interruption de tour lui parvient par le
 * relais (F-38 / SF-38-13, contrat du relais §6).
 *
 * <p>Cette interface existe pour que le paquet du relais n'importe rien de l'atelier : les
 * contrôleurs internes vivent tous dans {@code fr.claudegateway.runner.relay} (invariant T5), et
 * c'est l'atelier qui vient s'y brancher — pas l'inverse. L'implémentation est
 * {@code AtelierChatService}, seul détenteur des marques d'interruption.</p>
 */
public interface RelayInterruptTarget {

    /**
     * Applique les trois gestes d'une interruption sur <b>ce</b> pod, dans l'ordre exact de
     * {@code interruptChat} : marquer le tour, libérer la porte de confirmation, annuler les appels
     * en vol. Aucune vérification d'appartenance ici : elle a déjà eu lieu sur le pod qui a reçu la
     * requête de l'utilisateur, et le {@code userId} transporté ne sert que de clef de marque.
     *
     * @return le nombre de demandes d'autorisation libérées et d'appels annulés
     */
    RelayInterruptOutcome interruptLocally(UUID userId, UUID workspaceId, String reason);

    /**
     * Dépose une précision sur <b>ce</b> pod (F-39 / SF-39-19). Même raison d'être que
     * {@link #interruptLocally} : la boucle tourne peut-être ailleurs que là où le geste atterrit.
     */
    void steerLocally(UUID userId, UUID workspaceId, String message);

    /**
     * Effet mesurable d'une interruption sur un pod : rien du tout sur ceux qui ne faisaient rien,
     * ce qui est le cas courant d'une diffusion.
     */
    record RelayInterruptOutcome(int released, int cancelled) {
    }
}
