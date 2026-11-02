/**
 * **Corriger de la voix** (F-239 / SF-239-06, D7) — pur. « Non, j'ai dit CAGIP » après « Où en est Cagibi ? » ⇒
 * « Où en est CAGIP ? » ; une correction longue (≥ {@link FULL_REPLACEMENT_WORDS} mots) remplace toute la demande.
 */

/** Une correction de ce nombre de mots ou plus remplace toute la demande. */
export const FULL_REPLACEMENT_WORDS = 4;
/** Une correction n'a de sens que peu après ce qu'elle corrige. */
export const CORRECTION_WINDOW_MS = 30_000;

const PATTERN = /^(?:jarvis[ ,]+)?(?:non|mais non|pardon)?[ ,]*(?:j'ai dit|j’ai dit|je voulais dire|je disais|j'ai bien dit|j’ai bien dit|c'est|c’est)\s*:?\s+(.+)$/i;

/** Le texte que le PO dit avoir voulu dire, ou `null` si ce n'est pas une correction. */
export function parseVoiceCorrection(said: string | null | undefined): string | null {
  const t = (said ?? '').trim().replace(/[.!?…]+$/, '').trim();
  const m = PATTERN.exec(t);
  if (!m) {
    return null;
  }
  // « c'est … » seul n'est une correction que s'il commence par « non » (« non, c'est CAGIP »).
  if (/^(?:jarvis[ ,]+)?(?:c'est|c’est)/i.test(t)) {
    return null;
  }
  const x = m[1].trim().replace(/^[«"]+|[»"]+$/g, '').trim();
  return x ? x : null;
}

function plain(word: string): string {
  return word.toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^a-z0-9]+/g, '');
}

function distance(a: string, b: string): number {
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) {
      cur[j] = Math.min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    prev = cur;
  }
  return prev[b.length];
}

/**
 * Applique la correction à la demande précédente : longue ⇒ la remplace ; courte ⇒ remplace le passage le plus
 * proche (un mot, ou autant de mots que la correction), par distance d'édition rapportée à la longueur.
 */
export function applyCorrection(previous: string, correction: string): string {
  const fix = correction.trim();
  const fixWords = fix.split(/\s+/);
  if (fixWords.length >= FULL_REPLACEMENT_WORDS || !previous.trim()) {
    return fix;
  }
  const tokens = previous.trim().split(/\s+/);
  const target = plain(fix);
  let best = { start: -1, end: -1, score: Number.POSITIVE_INFINITY };
  for (let size = 1; size <= Math.min(3, tokens.length); size++) {
    for (let start = 0; start + size <= tokens.length; start++) {
      const candidate = plain(tokens.slice(start, start + size).join(''));
      if (!candidate) {
        continue;
      }
      const score = distance(candidate, target) / Math.max(candidate.length, target.length);
      if (score < best.score) {
        best = { start, end: start + size, score };
      }
    }
  }
  if (best.start < 0 || best.score > 0.75) {
    return fix;
  }
  const last = tokens[best.end - 1];
  const trailing = /[.,;:!?…»"]+$/.exec(last)?.[0] ?? '';
  return [...tokens.slice(0, best.start), fix + trailing, ...tokens.slice(best.end)].join(' ');
}
