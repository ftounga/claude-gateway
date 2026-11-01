/**
 * **Ce que Jarvis dit, et ce qu'il comprend sans le cerveau** (F-231) — fonctions pures, testées seules.
 *
 * - `oralPhrase` : la phrase à dire d'une réponse (D3) — la ligne « 🎙 » si l'agent l'a posée, sinon les deux
 *   premières phrases de prose. Le détail reste à l'écran.
 * - `subjectSwitch` : « passe sur CAGIP » change de sujet **sur l'appareil**, sans tour ni coût (D6).
 */

/** La marque de la phrase orale en tête de réponse (D3). */
export const ORAL_MARK = '🎙';

/** Au-delà, la phrase orale est coupée : Jarvis ne lit pas un compte rendu. */
export const MAX_ORAL_CHARS = 400;

/** La phrase à dire d'une réponse, ou une chaîne vide si la réponse n'a rien de lisible à voix haute. */
export function oralPhrase(reply: string | null | undefined): string {
  if (!reply) {
    return '';
  }
  const prose: string[] = [];
  let inFence = false;
  for (const raw of reply.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('```')) {
      inFence = !inFence;
      continue;
    }
    if (inFence || !line) {
      continue;
    }
    if (line.startsWith(ORAL_MARK)) {
      return cutOral(clean(line.slice(ORAL_MARK.length)));
    }
    // Les tableaux et les lignes de code ne se lisent pas à voix haute.
    if (line.startsWith('|') || line.startsWith('    ')) {
      continue;
    }
    prose.push(clean(line));
  }
  const text = prose.filter((l) => l.length > 0).join(' ');
  return cutOral(firstSentences(text, 2));
}

/** Le texte sans balisage Markdown ni marqueurs de l'essentiel. */
export function clean(line: string): string {
  return line
    .replace(/<<\s*\/?\s*[a-zA-Zéè]+\s*>>/g, '')
    .replace(/!\[[^\]]*]\([^)]*\)/g, '')
    .replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/^[#>*\-+\s]+/, '')
    .replace(/[*_`~]+/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Les `count` premières phrases de `text`. */
export function firstSentences(text: string, count: number): string {
  const sentences = text.match(/[^.!?…]+[.!?…]+["»”)]*\s*|[^.!?…]+$/g) ?? [];
  return sentences.slice(0, count).join('').trim();
}

function cutOral(text: string): string {
  if (text.length <= MAX_ORAL_CHARS) {
    return text;
  }
  const head = text.slice(0, MAX_ORAL_CHARS);
  const space = head.lastIndexOf(' ');
  return `${(space > 0 ? head.slice(0, space) : head).trimEnd()}…`;
}

/** Sans accents ni casse, espaces repliés : de quoi comparer ce qui a été dit à un nom de sujet. */
export function normalize(text: string): string {
  return text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

// La préposition est exigée : « va voir les journaux » est une demande, pas un changement de sujet.
const SWITCH = /^(?:jarvis\W+)?(?:passe|bascule|va|change)\s+(?:sur|à|au|vers|dans)\s+(?:le\s+)?(?:sujet\s+)?(.+?)[\s.!?]*$/i;

/**
 * « Passe sur CAGIP » → le sujet nommé, s'il n'y en a qu'un qui correspond ; `null` sinon (la phrase part alors au
 * cerveau comme une demande ordinaire).
 */
export function subjectSwitch<T extends { id: string; name: string }>(said: string, subjects: readonly T[]): T | null {
  const match = SWITCH.exec(said.trim());
  if (!match) {
    return null;
  }
  const wanted = normalize(match[1]);
  if (!wanted) {
    return null;
  }
  const exact = subjects.filter((s) => normalize(s.name) === wanted);
  if (exact.length === 1) {
    return exact[0];
  }
  const partial = subjects.filter((s) => normalize(s.name).includes(wanted));
  return partial.length === 1 ? partial[0] : null;
}
