/**
 * F-238 / SF-238-09 — **zéro chevauchement d'étiquettes** (retour orchestrateur du 2026-11-15). Fichier <b>pur</b>.
 *
 * <p>Glouton par priorité : une étiquette n'est montrée que si son cadre (élargi d'une marge) ne touche aucune
 * étiquette déjà retenue. Priorité : ce dont Jarvis parle, puis les services (le contenu), puis les zones de la plus
 * petite à la plus grande (la plus précise d'abord). Déterministe : à priorité égale, l'ordre d'entrée.</p>
 */

export interface LabelRect {
  x: number;
  y: number;
  w: number;
  h: number;
  /** Plus petit = plus prioritaire. */
  priority: number;
}

/** Marge entre deux étiquettes (px). */
export const LABEL_GAP = 2;

function overlaps(a: LabelRect, b: LabelRect, gap: number): boolean {
  return a.x < b.x + b.w + gap && b.x < a.x + a.w + gap && a.y < b.y + b.h + gap && b.y < a.y + a.h + gap;
}

/** Les étiquettes à montrer (vrai) ; une étiquette vide (0 × 0) n'est jamais retenue. */
export function declutter(rects: readonly LabelRect[], gap = LABEL_GAP): boolean[] {
  const order = rects.map((_, i) => i).sort((a, b) => rects[a].priority - rects[b].priority || a - b);
  const kept: LabelRect[] = [];
  const shown = rects.map(() => false);
  for (const i of order) {
    const r = rects[i];
    if (r.w <= 0 || r.h <= 0 || kept.some((k) => overlaps(k, r, gap))) {
      continue;
    }
    kept.push(r);
    shown[i] = true;
  }
  return shown;
}
