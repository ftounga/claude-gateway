package fr.claudegateway.billing.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.billing.seat.SeatUsage;

/**
 * Réponse de {@code GET /api/billing/seats} (F-65 / SF-65-01) : quels postes sont comptés pour la
 * période, lequel l'abonnement couvre, ce que les suppléments apportent en jetons, et si le
 * supplément est réellement facturé.
 *
 * <p>Aucun identifiant de price, aucun secret : le price ID reste interne, comme partout ailleurs
 * dans le module (OQ-07). Le montant transporté est un montant d'<b>affichage</b>, et il est vide
 * tant que le PO n'en a pas configuré.</p>
 *
 * @param includedSeats postes couverts par l'abonnement lui-même
 * @param countedSeats  postes comptés pour la période, clôturés en cours de mois compris
 * @param extraSeats    postes au-delà de ceux que l'abonnement couvre
 * @param grantedTokens jetons apportés par les suppléments sur la période, proratisation comprise
 * @param billed        le supplément est réellement facturé ; {@code false} tant que rien n'est
 *                      configuré — l'écran doit alors le dire au lieu de laisser croire à une facture
 * @param displayPrice  montant d'affichage du supplément, vide s'il n'est pas configuré
 * @param periodStart   premier jour de la période
 * @param periodEnd     premier jour de la période suivante (borne exclue)
 * @param seats         détail poste par poste, du plus ancien facturable au plus récent
 */
public record SeatsResponse(
        int includedSeats,
        int countedSeats,
        int extraSeats,
        long grantedTokens,
        boolean billed,
        String displayPrice,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<SeatResponse> seats) {

    /** Projette l'état métier en réponse d'API. */
    public static SeatsResponse from(SeatUsage usage) {
        return new SeatsResponse(
                usage.includedSeats(),
                usage.countedSeats(),
                usage.extraSeats(),
                usage.grantedTokens(),
                usage.billed(),
                usage.displayPrice(),
                usage.periodStart(),
                usage.periodEnd(),
                usage.seats().stream().map(SeatResponse::from).toList());
    }

    /**
     * Un poste compté pour la période.
     *
     * @param hostId        identifiant du poste
     * @param name          nom lisible du poste, ou {@code null} s'il n'est plus lisible
     * @param billableFrom  jour à partir duquel il est compté sur cette période
     * @param coveredByPlan vrai si l'abonnement le couvre : il n'apporte aucun supplément
     * @param extraSeatRank rang du supplément (1, 2, 3…), ou {@code 0} s'il est couvert par le plan
     * @param grantedTokens jetons que ce poste apporte à la période, proratisation comprise
     * @param closed        vrai s'il est clôturé aujourd'hui mais reste compté pour le mois engagé
     */
    public record SeatResponse(
            UUID hostId,
            String name,
            LocalDate billableFrom,
            boolean coveredByPlan,
            int extraSeatRank,
            long grantedTokens,
            boolean closed) {

        /** Projette un poste compté en réponse d'API. */
        public static SeatResponse from(SeatUsage.Seat seat) {
            return new SeatResponse(
                    seat.hostId(),
                    seat.name(),
                    seat.billableFrom(),
                    seat.coveredByPlan(),
                    seat.extraSeatRank(),
                    seat.grantedTokens(),
                    seat.closed());
        }
    }
}
