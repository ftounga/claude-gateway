package fr.claudegateway.billing.seat;

/**
 * Règle de <b>proratisation</b> d'un poste devenu facturable en cours de mois (F-65 / SF-65-01).
 *
 * <p>Elle vaut <b>pour le quota</b> : l'argent, lui, est proraté par le fournisseur de paiement.
 * Les deux doivent dire la même chose — d'où une clé de configuration et non une constante.</p>
 */
public enum SeatProration {

    /**
     * Prorata temporis à la journée : un poste facturable à partir du jour <i>j</i> apporte la
     * fraction de jetons correspondant aux jours restants du mois, <i>j</i> compris.
     *
     * <p>C'est le défaut, et il s'aligne sur le comportement par défaut de Stripe quand la quantité
     * d'un abonnement augmente en cours de période. Le retenir n'est pas d'abord une question de
     * justice mais de <b>sûreté</b> : une part <b>pleine</b> de jetons pour un poste ouvert le 28
     * serait une faille — on ouvrirait un poste la veille de la fin du mois, on encaisserait la
     * part entière, et on recommencerait.</p>
     */
    DAILY,

    /**
     * Aucune proratisation : un poste facturable un seul jour du mois apporte la part entière.
     *
     * <p>N'a de sens que si le PO configure aussi son fournisseur de paiement pour ne pas proratiser
     * ({@code proration_behavior=none}) : les deux côtés doivent bouger ensemble, sans quoi le
     * client paierait une fraction de mois et recevrait un mois entier de jetons — ou l'inverse.</p>
     */
    NONE
}
