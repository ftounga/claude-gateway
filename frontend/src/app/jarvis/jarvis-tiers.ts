/**
 * **La réponse en trois étages** (F-240 / SF-240-03) — fonction pure, testée seule.
 *
 * 1. ce que Jarvis **dit** (la ligne 🎙, `jarvis-oral.ts`) ;
 * 2. ce qu'il **montre** : l'essentiel seul — le bloc `<<essentiel>>` de la réponse (découpé par `splitEssential`,
 *    réutilisé tel quel), sinon, en repli, les 5 premières lignes de prose (hors tableaux et code) ;
 * 3. le **détail**, replié, **sans** l'essentiel.
 *
 * Les lignes qui ne se montrent jamais (« 🎙 » déjà dite, « ↪ » invitation, « 🖥 montrer: ») sont retirées avant le
 * découpage : sans cela, la ligne dite, écrite avant le bloc essentiel, tomberait dans le détail.
 */

import { splitEssential } from '../atelier/terminal/essential';
import { INVITE_MARK, ORAL_MARK, SHOW_LINE_MARK } from './jarvis-oral';

/** Au plus autant de lignes de prose font l'essentiel de repli (D4 : 3 à 5 lignes). */
export const FALLBACK_ESSENTIAL_LINES = 5;

/** Les étages 2 et 3 d'une réponse : l'essentiel (ou `null`) et le détail (ou `null` s'il ne reste rien). */
export interface JarvisTiers {
  essential: string | null;
  detail: string | null;
}

/** Le texte d'une réponse sans ses lignes dites ou pilotes (« 🎙 », « ↪ », « 🖥 »), hors blocs de code. */
export function stripVoiceLines(reply: string | null | undefined): string {
  let inFence = false;
  return (reply ?? '').split('\n').filter((raw) => {
    const line = raw.trim();
    if (line.startsWith('```')) {
      inFence = !inFence;
      return true;
    }
    return inFence || !(line.startsWith(ORAL_MARK) || line.startsWith(INVITE_MARK) || line.startsWith(SHOW_LINE_MARK));
  }).join('\n').trim();
}

/** Les étages 2 et 3 d'une réponse d'Opus. */
export function jarvisTiers(reply: string | null | undefined): JarvisTiers {
  const text = stripVoiceLines(reply);
  if (!text) {
    return { essential: null, detail: null };
  }
  const split = splitEssential(text);
  if (split.essential !== null) {
    return { essential: split.essential, detail: split.detail.trim() || null };
  }
  return fallback(split.detail);
}

/**
 * Le repli sans bloc essentiel : les {@link FALLBACK_ESSENTIAL_LINES} premières lignes de prose, jusqu'au premier
 * tableau ou bloc de code ; le reste fait le détail. Une réponse qui commence par un tableau ou du code n'a pas
 * d'essentiel : tout reste au détail.
 */
function fallback(text: string): JarvisTiers {
  const lines = text.split('\n');
  const kept: string[] = [];
  let prose = 0;
  let index = 0;
  for (; index < lines.length && prose < FALLBACK_ESSENTIAL_LINES; index++) {
    const line = lines[index].trim();
    if (line.startsWith('```') || line.startsWith('|') || lines[index].startsWith('    ')) {
      break;
    }
    kept.push(lines[index]);
    if (line) {
      prose++;
    }
  }
  const essential = kept.join('\n').trim();
  const detail = lines.slice(index).join('\n').trim();
  return essential ? { essential, detail: detail || null } : { essential: null, detail: detail || null };
}
