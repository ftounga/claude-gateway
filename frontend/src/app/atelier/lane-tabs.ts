import { OpenChantier, chantierAwaitsUser } from '../core/models/journey.models';

/** « Chantier 2 · Logs » — l'onglet ou l'en-tête d'un volet du split (F-226 / SF-226-02) ; « Fil du sujet » sans chantier. */
export function laneLabelOf(number: number | null, chantiers: readonly OpenChantier[]): string {
  if (number === null) {
    return 'Fil du sujet';
  }
  const title = chantiers.find((c) => c.number === number)?.title;
  return title ? `Chantier ${number} · ${title}` : `Chantier ${number}`;
}

/** L'état écrit d'un volet : « tourne » (tour vivant ou course), « à vous » (un geste attendu), ou rien. */
export function laneStateOf(number: number | null, chantiers: readonly OpenChantier[], active: readonly number[],
                            autonomous = false): string | null {
  if (number === null) {
    return null;
  }
  if (autonomous || active.includes(number)) {
    return 'tourne';
  }
  const c = chantiers.find((x) => x.number === number);
  return c && chantierAwaitsUser(c) ? 'à vous' : null;
}
