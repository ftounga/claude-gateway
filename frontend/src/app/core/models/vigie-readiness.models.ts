/**
 * La check-list de mise en service de la Vigie (F-122 / SF-122-02).
 *
 * Reflète `GET /api/runner-hosts/{hostId}/vigie/readiness` : quatre vérifications vert/rouge/en
 * attente, vérifiées **avant** de démarrer.
 */

/** L'état d'une vérification : vert, rouge, ou pas encore connu. */
export type VigieCheckStatus = 'OK' | 'KO' | 'PENDING';

/** Les quatre vérifications de la mise en service. */
export type VigieReadinessCheck =
  | 'RUNNER_CONNECTED'
  | 'CHROME_REACHABLE'
  | 'TEAMS_CONNECTED'
  | 'TEAMS_READ_TEST';

/** Une ligne de la check-list. */
export interface VigieReadinessItem {
  check: VigieReadinessCheck;
  status: VigieCheckStatus;
  detail: string;
}

/** La check-list complète et la décision « on peut démarrer ». */
export interface VigieReadiness {
  checks: VigieReadinessItem[];
  /** Vrai seulement si les quatre vérifications sont `OK`. */
  canStart: boolean;
  /** Vrai si Teams demande une identification (bouton « Se connecter à Teams »). */
  teamsSignInRequired: boolean;
}
