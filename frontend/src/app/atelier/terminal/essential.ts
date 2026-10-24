/**
 * Découpage « L'essentiel » / « Le détail » d'une réponse d'agent (F-126 / SF-126-01).
 *
 * L'agent balise sa réponse directe et courte entre `<<essentiel>>` et `<</essentiel>>` (consigne
 * système `ESSENTIAL_ANSWER_DOCTRINE`). Le frontend met cet essentiel en avant, le reste en détail.
 *
 * Le marqueur est du CONTENU à afficher, pas une métadonnée : il ne collisionne pas avec le retrait
 * du marqueur `fin-de-tour` (F-125, `stripTurnMetadata`), qui ne vise que le commentaire HTML
 * `<!-- fin-de-tour: … -->`. Ici on découpe le brut AVANT le rendu Markdown ; chaque moitié repasse
 * ensuite par `renderMarkdown` (donc DOMPurify + strip fin-de-tour). Fonction pure, testable sans
 * TestBed.
 */

/** Ouverture, tolérante à la casse et aux espaces internes : `<<essentiel>>`, `<< Essentiel >>`… */
const OPEN_MARKER = /<<\s*essentiel\s*>>/i;
/** Fermeture, idem : `<</essentiel>>`, `<< / essentiel >>`… */
const CLOSE_MARKER = /<<\s*\/\s*essentiel\s*>>/i;

/** Résultat du découpage. `essential` est `null` quand aucun essentiel exploitable n'est balisé. */
export interface EssentialSplit {
  /** La réponse essentielle mise en avant, ou `null` (repli gracieux : rendu normal). */
  essential: string | null;
  /** Le reste, rendu en style normal. Vaut le message entier quand `essential` est `null`. */
  detail: string;
}

/**
 * Découpe le contenu brut d'un message d'agent en essentiel + détail.
 *
 * - Pas de marqueur d'ouverture → `{ essential: null, detail: <tout> }` (aucune régression).
 * - Ouverture + fermeture → essentiel = l'entre-deux ; détail = ce qui précède l'ouverture (rare)
 *   concaténé à ce qui suit la fermeture.
 * - Ouverture SANS fermeture (troncature / streaming en cours) → tout ce qui suit l'ouverture est
 *   traité comme l'essentiel ; le marqueur brut n'est JAMAIS rendu littéralement.
 * - Essentiel vide (après trim) → `{ essential: null, … }` : pas de bloc vide.
 */
export function splitEssential(content: string | null | undefined): EssentialSplit {
  // F-192 / SF-192-02 : le bloc « Et maintenant ? » parle au produit — jamais rendu, même pendant le
  // flux (la passerelle le retire de la réponse persistée, mais le texte vivant le porte encore).
  // F-210 / SF-210-03 : les blocs `<<question id="N">>` d'une réponse découpée ne sont jamais montrés bruts.
  const text = neutralizeQuestionMarkers(stripNextSteps(content ?? ''));
  const open = text.match(OPEN_MARKER);
  if (!open || open.index === undefined) {
    return { essential: null, detail: text };
  }
  const beforeOpen = text.slice(0, open.index);
  const afterOpen = text.slice(open.index + open[0].length);

  const close = afterOpen.match(CLOSE_MARKER);
  if (!close || close.index === undefined) {
    // Fermeture absente : l'essentiel est en cours (streaming) ou tronqué. On prend tout le reste.
    const essential = afterOpen.trim();
    return essential
      ? { essential, detail: beforeOpen.trim() }
      : { essential: null, detail: beforeOpen.trim() };
  }

  const essential = afterOpen.slice(0, close.index).trim();
  const afterClose = afterOpen.slice(close.index + close[0].length);
  const detail = [beforeOpen.trim(), afterClose.trim()].filter((part) => part.length > 0).join('\n\n');
  return essential ? { essential, detail } : { essential: null, detail };
}

/** Ouverture du bloc de suites (F-192), tolérante : `<<suites>>`, `<< Suites >>`… */
const NEXT_STEPS_OPEN = /<<\s*suites\s*>>/i;
/** Fermeture, idem : `<</suites>>`. */
const NEXT_STEPS_CLOSE = /<<\s*\/\s*suites\s*>>/i;

/**
 * Retire les blocs `<<suites>> … <</suites>>` (F-192 / SF-192-02) d'un texte d'agent. Une ouverture
 * sans fermeture (flux en cours) retire tout ce qui la suit. Sans bloc, le texte revient tel quel.
 */
export function stripNextSteps(content: string): string {
  let rest = content;
  let kept = '';
  let removed = false;
  for (;;) {
    const open = rest.match(NEXT_STEPS_OPEN);
    if (!open || open.index === undefined) {
      break;
    }
    removed = true;
    kept += rest.slice(0, open.index);
    const after = rest.slice(open.index + open[0].length);
    const close = after.match(NEXT_STEPS_CLOSE);
    if (!close || close.index === undefined) {
      rest = '';
      break;
    }
    rest = after.slice(close.index + close[0].length);
  }
  return removed ? (kept + rest).replace(/\n{3,}/g, '\n\n').trim() : content;
}

/** Ouverture d'un bloc de réponse par question (F-210), tolérante : `<<question id="1">>`, `<< Question 2 >>`… */
const QUESTION_OPEN = /<<\s*question\s+(?:id\s*=\s*)?["']?(\d+)["']?\s*>>/gi;
/** Fermeture : `<</question>>`. */
const QUESTION_CLOSE = /<<\s*\/\s*question\s*>>/gi;
/** Un marqueur de question encore incomplet en fin de flux (`<<quest`, `<</questi`…). */
const QUESTION_PARTIAL = /<<\s*\/?\s*q[a-z]*[^>]*>?$/i;
const ESSENTIAL_ALL = /<<\s*\/?\s*essentiel\s*>>/gi;

/**
 * Neutralise les marqueurs de question d'une réponse découpée (F-210 / SF-210-03) : chaque ouverture devient
 * « **N.** », la fermeture disparaît, et les marqueurs d'essentiel internes sont retirés (une réponse à
 * plusieurs questions n'a pas UN essentiel). Même rendu que le texte persisté par la gateway (SF-210-02).
 * Sans marqueur de question, le texte revient tel quel.
 */
export function neutralizeQuestionMarkers(content: string): string {
  const hasOpen = new RegExp(QUESTION_OPEN.source, 'i').test(content);
  const partial = QUESTION_PARTIAL.test(content);
  if (!hasOpen && !partial) {
    return content;
  }
  let text = content.replace(QUESTION_PARTIAL, '');
  if (hasOpen) {
    text = text
      .replace(QUESTION_OPEN, (_m, id: string) => `\n\n**${id}.**\n\n`)
      .replace(QUESTION_CLOSE, '')
      .replace(ESSENTIAL_ALL, '');
  }
  return text.replace(/\n{3,}/g, '\n\n').trim();
}
