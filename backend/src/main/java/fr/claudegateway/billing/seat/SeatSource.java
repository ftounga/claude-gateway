package fr.claudegateway.billing.seat;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import fr.claudegateway.billing.EntitlementSpace;

/**
 * Ce que la facturation a besoin de savoir des <b>postes</b> pour compter des mois-clients
 * (F-65 / SF-65-01, par espace depuis F-107 / SF-107-05) : lesquels sont facturables, dans quel espace,
 * depuis quand, comment ils s'appellent.
 *
 * <p>L'interface existe pour que le module de facturation ne dépende pas du module runner : c'est ce
 * dernier qui vient s'y brancher ({@code RunnerHostSeatSource}), et lui seul sait qu'« être
 * facturable » se lit sur {@code runner_hosts.mission_status} et « être dans un espace » sur
 * {@code host_spaces}.</p>
 */
public interface SeatSource {

    /**
     * Postes <b>facturables dans la Forge</b> d'un utilisateur : ceux dont la mission n'est pas clôturée et
     * qui sont activés dans la Forge (un poste sans ligne d'espace l'est).
     *
     * @param userId propriétaire (contexte de sécurité), jamais un paramètre client
     * @return les postes facturables, éventuellement vide, jamais {@code null}
     */
    List<BillableSeat> billableSeats(UUID userId);

    /**
     * Postes facturables <b>dans un espace</b> (F-107 / SF-107-05). {@code createdAt} y date l'entrée dans la
     * facturation de cet espace : création du poste pour la Forge, activation pour la Vigie.
     *
     * @param userId propriétaire (contexte de sécurité)
     * @param space  espace compté
     * @return les postes facturables dans cet espace
     */
    default List<BillableSeat> billableSeats(UUID userId, EntitlementSpace space) {
        return space == EntitlementSpace.FORGE ? billableSeats(userId) : List.of();
    }

    /**
     * Espaces où un poste possédé est activé (F-107 / SF-107-05) : la clôture de mission, geste unique, engage
     * le mois dans chacun.
     *
     * @param userId propriétaire (contexte de sécurité)
     * @param hostId poste
     * @return ses espaces ; la Forge seule par défaut
     */
    default Set<EntitlementSpace> spacesOf(UUID userId, UUID hostId) {
        return EnumSet.of(EntitlementSpace.FORGE);
    }

    /**
     * Date d'entrée d'un poste dans la facturation d'un espace (F-107 / SF-107-05) : activation dans la Vigie,
     * {@code null} pour la Forge (la création du poste fait foi).
     *
     * @param userId propriétaire (contexte de sécurité)
     * @param hostId poste
     * @param space  espace
     * @return l'instant, ou {@code null}
     */
    default OffsetDateTime enteredSpaceAt(UUID userId, UUID hostId, EntitlementSpace space) {
        return null;
    }

    /**
     * Nom lisible d'un poste possédé, pour l'afficher dans le détail des mois-clients.
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
     * @param createdAt entrée du poste dans la facturation de l'espace quand aucune ligne de mois-client ne
     *                  la date
     */
    record BillableSeat(UUID hostId, String name, OffsetDateTime createdAt) {
    }
}
