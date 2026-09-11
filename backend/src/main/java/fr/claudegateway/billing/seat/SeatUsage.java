package fr.claudegateway.billing.seat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * État des <b>mois-postes</b> d'un utilisateur sur la période courante (F-65 / SF-65-01) :
 * combien de postes sont comptés, lequel est couvert par l'abonnement, combien de jetons les
 * suppléments apportent, et si le supplément est réellement facturé.
 *
 * <p>Objet métier, indépendant du transport (le DTO REST s'en déduit).</p>
 *
 * @param includedSeats  postes couverts par l'abonnement lui-même (configuration, défaut 1)
 * @param countedSeats   postes comptés pour la période, clôturés en cours de mois compris
 * @param extraSeats     postes au-delà de ceux que l'abonnement couvre
 * @param grantedTokens  jetons apportés par les suppléments, proratisation comprise
 * @param billed         le supplément est réellement facturé (un price est branché) ; {@code false}
 *                       tant que le PO n'a rien configuré — et l'écran doit le dire
 * @param displayPrice   montant d'affichage du supplément, vide tant qu'il n'est pas configuré
 * @param periodStart    premier jour de la période (mois calendaire UTC)
 * @param periodEnd      premier jour de la période suivante (borne exclue)
 * @param seats          détail poste par poste, du plus ancien facturable au plus récent
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
        List<Seat> seats) {

    /**
     * Un poste compté pour la période.
     *
     * @param hostId        identifiant du poste
     * @param name          nom lisible du poste, ou {@code null} s'il n'est plus lisible
     * @param billableFrom  jour à partir duquel il est compté sur cette période
     * @param coveredByPlan vrai si c'est un des postes que l'abonnement couvre (le ou les plus
     *                      anciens facturables) : il n'apporte aucun supplément
     * @param extraSeatRank rang du supplément (1, 2, 3…), ou {@code 0} s'il est couvert par le plan
     * @param grantedTokens jetons que ce poste apporte à la période, proratisation comprise
     * @param closed        vrai si sa mission est <b>clôturée</b> aujourd'hui alors qu'il a été
     *                      facturable plus tôt dans le mois : il reste compté jusqu'au bout du mois
     *                      engagé, et ne le sera plus le mois suivant
     */
    public record Seat(
            UUID hostId,
            String name,
            LocalDate billableFrom,
            boolean coveredByPlan,
            int extraSeatRank,
            long grantedTokens,
            boolean closed) {
    }
}
