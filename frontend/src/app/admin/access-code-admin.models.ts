/** Modèles d'administration des codes d'accès (F-62, contrat figé SF-62-01). */

/**
 * État d'un code, **calculé par le serveur** à partir de ses dates. L'écran ne le redérive jamais :
 * le stocker ou le recalculer ici ferait deux vérités, dont une fausse dès que les horloges
 * divergent.
 */
export type AccessCodeState = 'ISSUED' | 'ACTIVE' | 'ENDED' | 'EXPIRED';

/**
 * Un code d'accès vu depuis l'administration. **Ne porte jamais le code en clair** : celui-ci
 * n'existe qu'une fois, dans la réponse de création.
 */
export interface AccessCodeAdminView {
  id: string;
  /** Libellé donné à l'émission — la seule façon de reconnaître un code. */
  label: string;
  /** Destinataire d'un code nominatif, ou null (code au porteur). */
  assignedEmail: string | null;
  /** Plan dont le droit est offert (`GOLD`). */
  grantedPlanCode: string;
  /** Durée du droit, figée à l'émission. */
  durationHours: number;
  /** Au-delà, un code non consommé ne vaut plus rien. */
  validUntil: string;
  state: AccessCodeState;
  /** Compte qui l'a consommé — la trace « pour qui ». */
  redeemedByEmail: string | null;
  /** Instant de la consommation — la trace « quand ». */
  redeemedAt: string | null;
  /** Terme du droit. */
  grantedUntil: string | null;
  /** Plan auquel ce compte revient au terme (il ne l'a jamais quitté). */
  previousPlanCode: string | null;
  createdAt: string;
}

/** Demande d'émission. Ni la durée ni la validité ne s'y trouvent : ce sont des réglages produit. */
export interface IssueAccessCodeRequest {
  label: string;
  assignedEmail?: string;
}

/** Réponse d'émission : le **seul** endroit où le code en clair apparaît. */
export interface IssuedAccessCode {
  code: string;
  view: AccessCodeAdminView;
}
