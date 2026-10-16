import { WorkspaceSummary } from '../core/models/atelier.models';
import { HOSTED_REF } from '../postes/forge-fleet';

/**
 * Le client courant que le terminal ouvert impose (F-199 / SF-199-01), en fonction pure.
 *
 * @returns la référence du poste (`heberge` pour un terminal sans poste) ; `null` quand elle n'est
 *   pas encore connue — l'ancien client doit alors être effacé, jamais conservé ; `undefined` quand
 *   aucun terminal n'est ouvert — le client courant n'est pas touché.
 */
export function currentClientRefOf(
  activeWorkspaceId: string | null,
  summary: WorkspaceSummary | null,
  hostId: string | null,
): string | null | undefined {
  if (!activeWorkspaceId) {
    return undefined;
  }
  if (hostId) {
    return hostId;
  }
  // `hostId: null` servi explicitement (backend F-199) et aucun nom de poste : le terminal est Hébergé.
  // Champ absent (backend antérieur) : on ne sait pas encore — le détail tranchera.
  if (summary && summary.hostId === null && !summary.hostName) {
    return HOSTED_REF;
  }
  return null;
}
