/** Modèles des codes d'accès à durée limitée (F-62, contrat figé SF-62-01). */

/**
 * L'**accès offert** de l'utilisateur courant.
 *
 * `active` est renvoyé par le **serveur**, jamais recalculé ici : une horloge de navigateur
 * décalée ne doit décider ni d'ouvrir ni de fermer un accès.
 */
export interface AccessGrantView {
  /** Vrai si un accès offert est en cours. */
  active: boolean;
  /** Plan dont le droit est ouvert (`GOLD`), ou null. */
  grantedPlanCode: string | null;
  /** Terme du droit (ISO 8601), ou null. */
  grantedUntil: string | null;
  /**
   * Plan que porte réellement l'abonnement et qui reprend seul la main au terme — il n'a jamais été
   * quitté. Null si le compte n'a aucune offre payante.
   */
  previousPlanCode: string | null;
  /** Libellé donné par l'admin à l'émission, ou null. */
  label: string | null;
}

/** Requête de consommation d'un code. */
export interface RedeemAccessCodeRequest {
  code: string;
}
