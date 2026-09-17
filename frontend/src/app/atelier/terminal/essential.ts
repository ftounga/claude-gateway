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
  const text = content ?? '';
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
