/**
 * **Le texte collé, replié** (F-146 / SF-146-01) — fonctions pures.
 *
 * <p>Le champ du terminal tient sur une ligne : y coller un journal ou une configuration le rend
 * illisible. On y met une <b>référence</b>, et le texte complet revient à l'envoi.</p>
 */

/** Un texte collé, mis de côté. */
export interface PastedText {
  /** Numéro d'ordre, celui qui s'affiche : 1, 2, 3… */
  index: number;
  text: string;
  lines: number;
}

/** Au-delà, on replie. En deçà, on ne replie pas : ce qui tient dans le champ y reste. */
export const FOLD_LINES = 5;
export const FOLD_CHARS = 400;

/** Faut-il replier ce collage ? */
export function shouldFold(text: string): boolean {
  if (!text) {
    return false;
  }
  return countLines(text) > FOLD_LINES || text.length > FOLD_CHARS;
}

/** Le nombre de lignes d'un texte — ce que la puce annonce. */
export function countLines(text: string): number {
  return text ? text.split('\n').length : 0;
}

/**
 * La référence posée dans le champ.
 *
 * <p>Volontairement <b>lisible et effaçable</b> : c'est ce que l'utilisateur voit, et s'il la
 * supprime, le texte ne part pas. Le champ fait foi.</p>
 */
export function referenceOf(index: number): string {
  return `[texte collé #${index}]`;
}

/**
 * Remplace, dans le brouillon, chaque référence **encore présente** par son texte.
 *
 * <p><b>La garantie de non-surprise</b> : un collage dont la référence a été effacée n'est pas
 * réinjecté. Rien ne part que l'utilisateur n'ait sous les yeux.</p>
 */
export function expand(draft: string, pastes: PastedText[]): string {
  let expanded = draft ?? '';
  for (const paste of pastes) {
    const reference = referenceOf(paste.index);
    if (!expanded.includes(reference)) {
      continue;
    }
    expanded = expanded.split(reference).join(paste.text);
  }
  return expanded;
}

/** Retire une référence du brouillon — le geste « retirer » de la puce. */
export function removeReference(draft: string, index: number): string {
  return (draft ?? '')
    .split(referenceOf(index))
    .join('')
    .replace(/[ \t]{2,}/g, ' ')
    .trim();
}
