/**
 * Nom d'un fichier envoyé au dépôt (F-201 / SF-201-02) — fonction pure.
 *
 * <p>Le navigateur nomme toute capture collée {@code image.png} : chaque collage écrasait le précédent.
 * Le serveur garantit l'unicité (SF-201-01) ; le client envoie déjà un nom parlant.</p>
 */

/** Noms que le navigateur donne à une capture collée : `image.png`, `image (2).png`, `blob`… */
const GENERIC_NAME = /^(image|blob|pasted image|screenshot)( ?\(\d+\))?(\.[a-z0-9]{1,10})?$/i;

/**
 * Nom envoyé au dépôt : une capture générique devient `capture-AAAAMMJJ-HHMMSS.<ext>` (le serveur
 * reste la source de vérité et garantit l'unicité, SF-201-01) ; tout autre nom est conservé.
 */
export function depositFileName(name: string | undefined, now: Date = new Date()): string {
  const raw = (name ?? '').trim();
  if (raw.length === 0) {
    return 'fichier';
  }
  if (!GENERIC_NAME.test(raw)) {
    return raw;
  }
  const dot = raw.lastIndexOf('.');
  const ext = dot > 0 ? raw.slice(dot).toLowerCase() : '.png';
  const pad = (n: number) => String(n).padStart(2, '0');
  const stamp =
    `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}` +
    `-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  return `capture-${stamp}${ext}`;
}
