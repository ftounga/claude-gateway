/**
 * Modèle des **postes comptés** (F-65 / SF-65-01, `GET /api/billing/seats`).
 *
 * **Aucun montant, aucun quota, aucune règle tarifaire ne vit ici.** Tout ce que l'écran affiche
 * vient du serveur, qui le tient de sa configuration : le frontend montre, il ne calcule pas.
 */

/** Un poste compté pour la période courante. */
export interface SeatView {
  /** Identifiant du poste. */
  hostId: string;
  /** Nom lisible du poste, ou `null` s'il n'est plus lisible. */
  name: string | null;
  /** Jour à partir duquel il est compté sur cette période (ISO `YYYY-MM-DD`). */
  billableFrom: string;
  /** Vrai si l'abonnement le couvre : il n'apporte aucun supplément. */
  coveredByPlan: boolean;
  /** Rang du supplément (1, 2, 3…), ou `0` s'il est couvert par l'abonnement. */
  extraSeatRank: number;
  /** Jetons que ce poste apporte à la période, proratisation comprise. */
  grantedTokens: number;
  /**
   * Vrai si sa mission est **clôturée** aujourd'hui alors qu'il a été facturable plus tôt dans le
   * mois : il reste compté jusqu'au bout du mois engagé, et ne le sera plus le mois suivant.
   */
  closed: boolean;
}

/** État des postes comptés pour la période courante. */
export interface SeatsView {
  /** Postes couverts par l'abonnement lui-même. */
  includedSeats: number;
  /** Postes comptés pour la période, clôturés en cours de mois compris. */
  countedSeats: number;
  /** Postes au-delà de ceux que l'abonnement couvre. */
  extraSeats: number;
  /** Jetons apportés par les suppléments sur la période. */
  grantedTokens: number;
  /**
   * Le supplément est-il réellement facturé ? `false` tant que rien n'est configuré — l'écran doit
   * alors le dire, plutôt que de laisser croire à une facture qui n'existe pas.
   */
  billed: boolean;
  /** Montant d'affichage du supplément, vide s'il n'est pas configuré. */
  displayPrice: string;
  /** Premier jour de la période (ISO `YYYY-MM-DD`). */
  periodStart: string;
  /** Premier jour de la période suivante (borne exclue, ISO `YYYY-MM-DD`). */
  periodEnd: string;
  /** Détail poste par poste, du plus ancien facturable au plus récent. */
  seats: SeatView[];
}
