/**
 * **Les passerelles entre la Forge et la Vigie** (F-106 / SF-106-04), en fonctions pures.
 *
 * <p>Un client, deux espaces : changer d'espace ne doit pas faire perdre le client qu'on regardait.
 * La barre du haut lit donc le client ouvert dans l'adresse courante et le porte vers l'autre
 * espace. Si le client n'y est pas activé, l'écran d'arrivée ouvre son client par défaut — sans
 * erreur, c'est déjà sa règle.</p>
 */

/** Segments de la Forge qui ne désignent pas un client (`/forge/voir`…), ni le poste « Hébergé ». */
const NOT_A_CLIENT = new Set(['supervision', 'mosaique', 'voir', 'heberge']);

/** Le client ouvert dans une adresse `/forge/<id>` ou `/vigie/<id>[/…]`, ou `null`. */
export function openClientRef(url: string | null | undefined): string | null {
  const path = (url ?? '').split('?')[0].split('#')[0];
  const segments = path.split('/').filter((segment) => segment.length > 0);
  if (segments.length < 2 || (segments[0] !== 'forge' && segments[0] !== 'vigie')) {
    return null;
  }
  const ref = decodeURIComponent(segments[1]);
  return NOT_A_CLIENT.has(ref) ? null : ref;
}

/**
 * Les adresses des entrées Forge et Vigie, portant le client ouvert quand il y en a un.
 *
 * <p>F-199 / SF-199-01 : à défaut de client dans l'adresse (`/atelier/<id>`, `/chat`…), le **client
 * courant** — celui du dernier terminal ouvert — est porté. Le client de l'adresse prime toujours.
 * « Hébergé » n'existe que dans la Forge : il n'est jamais porté vers la Vigie.</p>
 */
export function spaceLinks(
  url: string | null | undefined,
  currentClient: string | null = null,
): { forge: string[]; vigie: string[] } {
  const current = (currentClient ?? '').trim();
  const ref = openClientRef(url) ?? (current.length ? current : null);
  if (ref === null) {
    return { forge: ['/forge'], vigie: ['/vigie'] };
  }
  return {
    forge: ['/forge', ref],
    vigie: NOT_A_CLIENT.has(ref) ? ['/vigie'] : ['/vigie', ref],
  };
}
