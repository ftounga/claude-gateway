/**
 * **« Dis-m'en plus » descend d'un étage** (F-240 / SF-240-04) — fonctions pures, testées seules.
 *
 * La réponse d'Opus est **déjà calculée** et à l'écran : descendre d'un étage la **lit**, sans aucun appel (D7).
 * Étage 1 → 2 : dire l'essentiel, ligne par ligne. Étage 2 → 3 : déplier le détail et en dire la première section
 * (≤ {@link MAX_SECTION_WORDS} mots, ni tableau ni code : « le tableau est à l'écran »). Chaque « encore » dit la
 * section suivante. À la fin : « C'est tout pour cette réponse. »
 */

import { clean, firstSentences, normalize } from './jarvis-oral';
import { jarvisTiers } from './jarvis-tiers';
import { spokenWordCount } from './jarvis-turn-clock';

/** La marque du cerveau rapide : « l'utilisateur veut la suite de la dernière réponse » (jamais dite). */
export const MORE_MARK = '⤵';

/** Au plus autant de mots dits par section du détail (D7). */
export const MAX_SECTION_WORDS = 60;

/** Au plus autant de mots dits pour l'essentiel (3 à 5 lignes courtes). */
export const MAX_ESSENTIAL_WORDS = 80;

/** Un « oui » à l'invitation ciblée vaut « dis-m'en plus » s'il vient dans ce délai (D8). */
export const INVITATION_WINDOW_MS = 30_000;

/** Ce qui est dit quand le cerveau rapide demande la suite (« ⤵ ») et qu'aucune réponse d'Opus n'est à lire. */
export const NOTHING_MORE_PHRASE = 'Je n’ai rien de plus à développer pour l’instant, Monsieur.';

/** Ce qui est dit à la fin de la réponse. */
export const END_PHRASE = 'C’est tout pour cette réponse.';

/** Un pas de la descente : ce qui est dit, et l'étage atteint (`null` à la fin). */
export interface MoreStep {
  kind: 'essential' | 'detail' | 'end';
  speech: string;
  depth: 2 | 3 | null;
}

/** La lecture, étage par étage, d'une réponse déjà calculée. */
export class MoreReader {
  private stage: 1 | 2 | 3 = 1;
  private section = 0;
  private readonly essential: string;
  private readonly sections: string[];

  constructor(reply: string) {
    const tiers = jarvisTiers(reply);
    this.essential = essentialSpeech(tiers.essential);
    this.sections = detailSections(tiers.detail);
  }

  /** L'étage atteint (1 tant que rien n'a été descendu). */
  get depth(): 1 | 2 | 3 {
    return this.stage;
  }

  /** Le pas suivant. */
  next(): MoreStep {
    if (this.stage === 1) {
      this.stage = 2;
      if (this.essential) {
        return { kind: 'essential', speech: this.essential, depth: 2 };
      }
    }
    if (this.section < this.sections.length) {
      this.stage = 3;
      return { kind: 'detail', speech: this.sections[this.section++], depth: 3 };
    }
    return { kind: 'end', speech: END_PHRASE, depth: null };
  }
}

/** L'essentiel à dire : ses lignes nettoyées (puces retirées), chacune ponctuée, au plus 80 mots. */
export function essentialSpeech(essential: string | null): string {
  if (!essential) {
    return '';
  }
  const lines = prose(essential).lines;
  return capWords(lines.map(punctuate).join(' '), MAX_ESSENTIAL_WORDS);
}

/**
 * Les sections du détail, à dire une par une : découpées par titre (« ## … »), sinon par paragraphe ; chacune
 * ≤ {@link MAX_SECTION_WORDS} mots ; un tableau ou du code n'est pas lu (« … est à l'écran »).
 */
export function detailSections(detail: string | null): string[] {
  if (!detail) {
    return [];
  }
  const raw = detail.split('\n');
  const hasHeadings = raw.some((l) => /^#{1,6}\s/.test(l.trim()));
  const chunks: string[][] = [];
  let current: string[] = [];
  let inFence = false;
  for (const line of raw) {
    const t = line.trim();
    if (t.startsWith('```')) {
      inFence = !inFence;
    }
    const breaks = !inFence && (hasHeadings ? /^#{1,6}\s/.test(t) : t === '');
    if (breaks && current.some((l) => l.trim())) {
      chunks.push(current);
      current = [];
    }
    current.push(line);
  }
  if (current.some((l) => l.trim())) {
    chunks.push(current);
  }
  return chunks.map((chunk) => sectionSpeech(chunk.join('\n'))).filter((s) => s.length > 0);
}

function sectionSpeech(section: string): string {
  const { lines, table, code } = prose(section);
  const text = capWords(lines.map(punctuate).join(' '), MAX_SECTION_WORDS);
  const notes = [table ? 'Le tableau est à l’écran.' : '', code ? 'Le code est à l’écran.' : ''].filter((n) => n);
  return [text, ...notes].filter((p) => p).join(' ');
}

/** Les lignes lisibles d'un texte (hors tableaux et code), et s'il y avait un tableau ou du code. */
function prose(text: string): { lines: string[]; table: boolean; code: boolean } {
  const lines: string[] = [];
  let inFence = false;
  let table = false;
  let code = false;
  for (const raw of text.split('\n')) {
    const t = raw.trim();
    if (t.startsWith('```')) {
      inFence = !inFence;
      code = true;
      continue;
    }
    if (inFence) {
      continue;
    }
    if (t.startsWith('|')) {
      table = true;
      continue;
    }
    if (raw.startsWith('    ')) {
      code = true;
      continue;
    }
    const c = clean(t);
    if (c) {
      lines.push(c);
    }
  }
  return { lines, table, code };
}

function punctuate(line: string): string {
  return /[.!?…:;]["»”)]*$/.test(line) ? line : `${line.replace(/[,;:]+$/, '')}.`;
}

/** Au plus `max` mots : des phrases entières si possible, sinon une coupe nette suivie de « … ». */
function capWords(text: string, max: number): string {
  if (spokenWordCount(text) <= max) {
    return text;
  }
  let kept = '';
  for (let n = 1; ; n++) {
    const candidate = firstSentences(text, n);
    if (spokenWordCount(candidate) > max || candidate === kept) {
      break;
    }
    kept = candidate;
  }
  if (kept) {
    return kept;
  }
  const words = text.split(/\s+/);
  return `${words.slice(0, max).join(' ')}…`;
}

function command(text: string): string {
  return normalize(text).replace(/^(jarvis|monsieur)\s+/, '').replace(/\s+(jarvis|monsieur)$/, '').trim();
}

/**
 * « Dis-m'en plus », « développe », « détaille », « plus de détails », « encore », « la suite », « et ensuite »
 * (liste fermée, phrase entière, sur l'appareil). « Continue » en est **exclu** : c'est un acquiescement quand il coupe
 * Jarvis (`classifyInterruption`).
 */
export function isMoreCommand(text: string | null | undefined): boolean {
  return MORE_COMMANDS.has(command(text ?? ''));
}

const MORE_COMMANDS = new Set([
  'dis m en plus', 'dites m en plus', 'dis m en un peu plus', 'dites m en un peu plus', 'dis moi en plus',
  'dites moi en plus', 'developpe', 'developpez', 'developpe un peu', 'developpez un peu', 'tu peux developper',
  'vous pouvez developper', 'detaille', 'detaillez', 'detaille un peu', 'plus de details', 'plus de detail',
  'davantage de details', 'encore', 'encore un peu', 'la suite', 'et la suite', 'et ensuite', 'ensuite',
  'en dire plus', 'tu peux m en dire plus', 'vous pouvez m en dire plus', 'quoi d autre', 'et apres',
]);

/** « Oui », « vas-y », « dis-le »… : vaut « dis-m'en plus » juste après une invitation ciblée (D8). */
export function isMoreAssent(text: string | null | undefined): boolean {
  return MORE_ASSENTS.has(command(text ?? ''));
}

const MORE_ASSENTS = new Set([
  'oui', 'oui oui', 'oui vas y', 'vas y', 'allez y', 'oui allez y', 'dis le', 'dites le', 'oui dis le',
  'oui dites le', 'volontiers', 'oui volontiers', 'je veux bien', 'oui je veux bien', 'oui s il te plait',
  'oui s il vous plait', 'oui merci', 'avec plaisir', 'je t ecoute', 'je vous ecoute',
]);

/** Le texte du cerveau rapide est la ligne-marque `⤵` (jamais dite). */
export function isMoreMark(sentence: string | null | undefined): boolean {
  return (sentence ?? '').trim().startsWith(MORE_MARK);
}
