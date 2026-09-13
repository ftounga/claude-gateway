package fr.claudegateway.billing.seat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.billing.EntitlementSpace;

/**
 * État des <b>mois-clients</b> d'un utilisateur sur la période courante, <b>pour un espace</b> (F-65 /
 * SF-65-01, par espace depuis F-107 / SF-107-05) : combien de clients sont comptés, lequel est couvert par
 * l'abonnement, combien de jetons les suppléments apportent, et si le supplément est réellement facturé.
 *
 * <p>Objet métier, indépendant du transport (le DTO REST s'en déduit).</p>
 *
 * @param includedSeats  clients couverts par l'abonnement lui-même dans cet espace
 * @param countedSeats   clients comptés pour la période, clôturés ou retirés en cours de mois compris
 * @param extraSeats     clients au-delà de ceux que l'abonnement couvre
 * @param grantedTokens  jetons apportés au quota par les suppléments, proratisation comprise — nuls tant que
 *                       le supplément n'est pas facturé, en BYOK, et toujours dans la Vigie
 * @param billed         le supplément est réellement facturé (un price est branché)
 * @param displayPrice   montant d'affichage du premier supplément, vide s'il n'est pas configuré
 * @param periodStart    premier jour de la période (mois calendaire UTC)
 * @param periodEnd      premier jour de la période suivante (borne exclue)
 * @param seats          détail client par client, du plus ancien facturable au plus récent
 * @param space          espace compté (F-107 / SF-107-05)
 * @param tokensApply    les suppléments de cet espace apportent des jetons à ce compte : faux dans la Vigie et
 *                       en BYOK (F-41 : aucun jeton plateforme), quels que soient les montants
 */
public record SeatUsage(
        int includedSeats,
        int countedSeats,
        int extraSeats,
        long grantedTokens,
        boolean billed,
        String displayPrice,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<Seat> seats,
        EntitlementSpace space,
        boolean tokensApply) {

    /**
     * Un client compté pour la période.
     *
     * @param hostId        identifiant du poste
     * @param name          nom lisible du poste, ou {@code null} s'il n'est plus lisible
     * @param billableFrom  jour à partir duquel il est compté sur cette période
     * @param coveredByPlan vrai si c'est un des clients que l'abonnement couvre
     * @param extraSeatRank rang du supplément (1, 2, 3…), ou {@code 0} s'il est couvert par le plan
     * @param grantedTokens jetons que ce client apporte à la période, proratisation comprise
     * @param closed        vrai si sa mission est clôturée (ou s'il a quitté l'espace) alors qu'il a été
     *                      facturable plus tôt dans le mois : il reste compté jusqu'au bout du mois engagé
     * @param displayPrice  montant d'affichage de son supplément (palier de son rang), vide s'il est couvert
     *                      par le plan ou si aucun montant n'est configuré
     */
    public record Seat(
            UUID hostId,
            String name,
            LocalDate billableFrom,
            boolean coveredByPlan,
            int extraSeatRank,
            long grantedTokens,
            boolean closed,
            String displayPrice) {
    }
}
