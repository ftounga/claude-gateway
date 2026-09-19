/**
 * Le journal de diagnostic d'un poste (F-132 / SF-132-03) — ce que rend
 * `GET /api/runner-hosts/{hostId}/diag` (SF-132-02).
 *
 * Des **formes et des états**, jamais un contenu : le runner expurge à la source (SF-132-01).
 */

/** Niveau d'un événement de diagnostic. */
export type RunnerDiagLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';

/** Un événement de diagnostic du runner tel que rendu par la gateway. */
export interface RunnerDiagEntry {
  readonly id: string;
  readonly level: RunnerDiagLevel;
  readonly category: string;
  readonly code: string;
  readonly message: string | null;
  /** Petite carte de champs scalaires expurgés (objet JSON), ou `null`. */
  readonly fields: Record<string, unknown> | null;
  /** Horodatage d'observation côté runner, ou `null`. */
  readonly observedAt: string | null;
  /** Horodatage serveur de réception. */
  readonly createdAt: string;
}

/** Options de lecture du journal : niveau minimum et taille de page. */
export interface RunnerDiagQuery {
  /** Niveau **minimum** ; absent = tous les niveaux. */
  readonly level?: RunnerDiagLevel;
  /** Nombre max de lignes (le serveur borne à 500). */
  readonly limit?: number;
}
