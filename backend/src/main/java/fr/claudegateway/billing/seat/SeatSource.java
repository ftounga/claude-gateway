package fr.claudegateway.billing.seat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Ce que la facturation a besoin de savoir des <b>postes</b> pour compter des mois-postes
 * (F-65 / SF-65-01) : lesquels sont facturables, depuis quand ils existent, comment ils s'appellent.
 *
 * <p>L'interface existe pour que le module de facturation ne dépende pas du module runner : c'est ce
 * dernier qui vient s'y brancher ({@code RunnerHostSeatSource}), et lui seul sait qu'« être
 * facturable » se lit aujourd'hui sur {@code runner_hosts.mission_status}. Le jour où l'unité
 * facturée changerait de forme, la facturation n'aurait rien à réapprendre.</p>
 */
public interface SeatSource {

    /**
     * Postes <b>facturables</b> d'un utilisateur : tous ceux dont la mission n'est pas clôturée.
     *
     * <p>Une mission <b>en attente</b> en fait partie : elle est gardée ouverte — poste appairé,
     * projets rattachés, reprise sans rien réinstaller. Seule la clôture sort du décompte, et c'est
     * le geste par lequel le consultant cesse de payer.</p>
     *
     * @param userId propriétaire (contexte de sécurité), jamais un paramètre client
     * @return les postes facturables, éventuellement vide, jamais {@code null}
     */
    List<BillableSeat> billableSeats(UUID userId);

    /**
     * Nom lisible d'un poste possédé, pour l'afficher dans le détail des mois-postes.
     *
     * @param userId propriétaire (contexte de sécurité)
     * @param hostId poste dont on veut le nom
     * @return le nom, ou {@code null} si le poste n'existe plus ou n'appartient pas à l'utilisateur
     */
    String seatName(UUID userId, UUID hostId);

    /**
     * Un poste facturable.
     *
     * @param hostId    identifiant du poste
     * @param name      nom lisible choisi par le propriétaire
     * @param createdAt création du poste — elle date son entrée dans la facturation quand aucune
     *                  ligne de mois-poste ne le fait
     */
    record BillableSeat(UUID hostId, String name, OffsetDateTime createdAt) {
    }
}
