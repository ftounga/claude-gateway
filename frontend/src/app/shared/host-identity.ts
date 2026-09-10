/**
 * **Identité visuelle d'un poste** (F-49 / SF-49-03).
 *
 * <p>La vue d'ensemble livrée par SF-49-02 *informe* — nom, état, racine, système, interpréteur,
 * projets — mais ne *montre* rien : rien ne rattache visuellement un terminal à son client, et le
 * nom du poste est simplement écrit. Ce module donne à chaque poste une couleur et des initiales,
 * portées partout où un projet apparaît.</p>
 *
 * <p><b>Fonction pure, rien de rangé nulle part</b> (arbitrage n° 2 de la mini-spec) : la couleur
 * est une <b>conséquence du nom</b>, calculée à chaque affichage. Ni colonne en base, ni
 * `localStorage`, ni champ d'API. C'est ce qui la rend identique d'une session à l'autre, d'un écran
 * à l'autre et d'un poste de consultation à l'autre — une couleur rangée en base ne vaudrait que
 * pour un seul navigateur, et divergerait dès la première restauration. Contrepartie assumée :
 * renommer un poste change sa couleur.</p>
 *
 * <p><b>La couleur ne porte jamais seule l'information</b> : ce module ne rend qu'un ton et des
 * initiales ; c'est `HostBadgeComponent` qui garantit que le nom reste écrit à côté.</p>
 */

/**
 * Un ton d'identité : trois couleurs qui vont ensemble, et dont les contrastes sont **prouvés** par
 * `host-identity.spec.ts` (WCAG 2.1, seuil AA texte normal 4.5:1).
 */
export interface HostTone {
  /** Aplat fort — fond de la pastille d'initiales, **texte blanc dessus** (≥ 4.5:1). */
  readonly solid: string;
  /** Encre — texte de cette identité, **sur blanc et sur `tint`** (≥ 4.5:1 dans les deux cas). */
  readonly ink: string;
  /** Teinte pâle — fond discret d'une puce portant `ink`. */
  readonly tint: string;
}

/**
 * **Palette d'identité des postes** — fermée, ordonnée, validée (`DESIGN_SYSTEM.md` §9).
 *
 * <p>Le hachage choisit un <b>index</b> ici, il ne calcule pas une teinte (arbitrage n° 1 de la
 * mini-spec). La contrainte d'accessibilité — « le contraste reste conforme <b>quelle que soit</b>
 * la couleur tirée » — n'est démontrable que sur un ensemble <b>fini</b> : dix tons se prouvent en
 * les parcourant tous, 360 teintes HSL ne se prouvent pas, et la luminance perçue y varie trop d'une
 * teinte à l'autre à luminosité constante.</p>
 *
 * <p>Tons volontairement <b>désaturés</b> : la charte est sobre, et ces couleurs identifient — elles
 * n'appellent pas à l'action. L'or de marque (`--cg-accent`) reste réservé aux gestes.</p>
 */
export const HOST_IDENTITY_PALETTE: readonly HostTone[] = [
  { solid: '#4370A3', ink: '#386599', tint: '#E7EFF9' }, // bleu ardoise
  { solid: '#7051B8', ink: '#5C3DA4', tint: '#ECE7F9' }, // violet
  { solid: '#A348B1', ink: '#933BA0', tint: '#F6E7F9' }, // mauve
  { solid: '#B1487D', ink: '#A43D70', tint: '#F9E7F0' }, // framboise
  { solid: '#B14F48', ink: '#A4433D', tint: '#F9E8E7' }, // brique
  { solid: '#94633D', ink: '#865632', tint: '#F9EFE7' }, // terre
  { solid: '#7F6D34', ink: '#705F29', tint: '#F9F4E7' }, // olive
  { solid: '#597731', ink: '#4E6C28', tint: '#F1F9E7' }, // vert mousse
  { solid: '#327B57', ink: '#286C4A', tint: '#E7F9F0' }, // vert sapin
  { solid: '#34777F', ink: '#2B6C73', tint: '#E7F7F9' }, // sarcelle
];

/** Ton de repli — un poste sans nom exploitable reste identifiable, et le reste toujours pareil. */
const FALLBACK_TONE = HOST_IDENTITY_PALETTE[0];

/** Initiales de repli. Jamais une chaîne vide : une pastille sans lettre n'est plus qu'une couleur. */
const FALLBACK_INITIALS = '?';

/** Ce qui sépare deux mots dans un nom de poste — espaces et ponctuation de chemin. */
const WORD_SEPARATORS = /[\s\-_./\\]+/;

/**
 * Nom réduit à ce qui doit décider de la couleur : casse et espaces de bord ne comptent pas, pour
 * que « Client Alpha », « client alpha » et «   Client   Alpha  » soient le même poste à l'œil.
 */
function normalize(name: string | null | undefined): string {
  return (name ?? '').normalize('NFC').trim().replace(/\s+/g, ' ').toLowerCase();
}

/**
 * FNV-1a 32 bits — petit, sans dépendance, et surtout **stable dans le temps** : c'est la seule
 * propriété qui compte ici, puisque rien n'est stocké et que la couleur doit être recalculable à
 * l'identique demain, ailleurs. Ce n'est pas un hachage cryptographique et n'a pas à l'être.
 */
function fnv1a(value: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < value.length; i += 1) {
    hash ^= value.charCodeAt(i);
    // Multiplication par 16777619 en arithmétique 32 bits non signée.
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash >>> 0;
}

/** Index du ton d'un poste dans {@link HOST_IDENTITY_PALETTE}. Toujours dans les bornes. */
export function hostToneIndex(name: string | null | undefined): number {
  const normalized = normalize(name);
  if (normalized.length === 0) {
    return 0;
  }
  return fnv1a(normalized) % HOST_IDENTITY_PALETTE.length;
}

/** Ton d'un poste — fonction pure du nom, et de rien d'autre. */
export function hostTone(name: string | null | undefined): HostTone {
  const normalized = normalize(name);
  return normalized.length === 0 ? FALLBACK_TONE : HOST_IDENTITY_PALETTE[hostToneIndex(name)];
}

/**
 * Initiales d'un poste : une lettre par mot pour les deux premiers, ou les deux premières lettres
 * d'un mot unique. Calculées sur le nom **non minusculisé** puis mises en majuscules — un poste
 * nommé en minuscules porte quand même des capitales.
 */
export function hostInitials(name: string | null | undefined): string {
  const trimmed = (name ?? '').normalize('NFC').trim();
  if (trimmed.length === 0) {
    return FALLBACK_INITIALS;
  }
  const words = trimmed
    .split(WORD_SEPARATORS)
    .map((word) => Array.from(word).filter((char) => /[\p{L}\p{N}]/u.test(char)).join(''))
    .filter((word) => word.length > 0);
  if (words.length === 0) {
    return FALLBACK_INITIALS;
  }
  if (words.length === 1) {
    return Array.from(words[0]).slice(0, 2).join('').toUpperCase();
  }
  return (Array.from(words[0])[0] + Array.from(words[1])[0]).toUpperCase();
}

/** Identité visuelle complète d'un poste : ce qu'un écran a besoin de savoir, en un appel. */
export interface HostIdentity {
  readonly name: string;
  readonly initials: string;
  readonly tone: HostTone;
}

/** Identité visuelle d'un poste. Pure : mêmes entrées, mêmes sorties, partout, toujours. */
export function hostIdentity(name: string | null | undefined): HostIdentity {
  return {
    name: (name ?? '').trim(),
    initials: hostInitials(name),
    tone: hostTone(name),
  };
}
