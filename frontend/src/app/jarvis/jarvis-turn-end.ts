/**
 * **Laisser finir la phrase** (F-239 / SF-239-10, D6) — pur. Le PO marque des pauses en réfléchissant (« Allons chez le
 * client et donnez- … ») : un silence seul coupe ces phrases (banc `--vad` : 0,7 s ⇒ 50/52 phrases à pause coupées,
 * 1,2 s ⇒ 28/52). Une phrase **en suspens** n'est donc jamais close au premier silence : l'écoute attend
 * {@link SUSPENDED_EXTRA_MS} de plus ; une parole qui reprend relance l'attente.
 */

/** Une phrase en suspens attend autant de plus avant la fin du tour. */
export const SUSPENDED_EXTRA_MS = 1_500;
/** En flux, le silence mesuré par l'écran ne clôt qu'après le silence du fournisseur, plus cette marge. */
export const STREAM_FALLBACK_MS = 800;

/** Les derniers mots qui laissent une phrase ouverte (normalisés, sans accents). */
const OPEN_WORDS = new Set([
  'et', 'ou', 'mais', 'donc', 'or', 'ni', 'car', 'puis', 'alors', 'que', 'qui', 'quoi', 'dont', 'si', 'comme', 'quand',
  'parce', 'pour', 'avec', 'sans', 'sur', 'sous', 'dans', 'chez', 'par', 'vers', 'entre', 'de', 'du', 'des', 'a', 'au',
  'aux', 'en', 'le', 'la', 'les', 'l', 'un', 'une', 'd', 'mon', 'ma', 'mes', 'ton', 'ta', 'tes', 'son', 'sa', 'ses',
  'notre', 'nos', 'votre', 'vos', 'leur', 'leurs', 'ce', 'cet', 'cette', 'ces', 'est', 'sont', 'euh', 'heu', 'hum',
  'bah', 'ben', 'enfin', 'genre', 'disons', 'notamment', 'surtout', 'aussi', 'meme', 'tres', 'plus', 'moins',
]);

/**
 * Vrai si la phrase est restée ouverte : elle finit par un trait d'union, une virgule, deux-points ou des points de
 * suspension, ou par un mot qui appelle une suite (conjonction, article, préposition, possessif, hésitation).
 * Une phrase close par un point, un point d'interrogation ou d'exclamation n'est jamais en suspens.
 */
export function isSuspended(text: string | null | undefined): boolean {
  const t = (text ?? '').trim();
  if (!t) {
    return false;
  }
  if (/[-‐–—,;:]$/.test(t) || /(\.\.\.|…)$/.test(t)) {
    return true;
  }
  if (/[.!?]$/.test(t)) {
    return false;
  }
  const words = t.toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/['’]/g, ' ')
    .replace(/[^a-z0-9 ]+/g, ' ').trim().split(/\s+/);
  return OPEN_WORDS.has(words[words.length - 1] ?? '');
}
