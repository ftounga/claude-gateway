/**
 * **Les @-mentions de fichiers** (F-121 / SF-121-24) — fonctions pures, à l'image de Claude Code.
 *
 * <p>Dans le composer, taper <code>@</code> ouvre une autocomplétion sur l'arborescence du projet.
 * La sélection pose une <b>référence lisible</b> <code>@chemin</code> dans le champ ; à l'envoi, le
 * contenu du fichier mentionné est <b>apposé</b> au message, dans un bloc étiqueté par chemin. Le
 * modèle « lit » comme d'habitude (Provider-First) : ce n'est qu'une injection ciblée de contexte
 * dans le message utilisateur, jamais dans le préfixe système (cache de prompt F-134 préservé).</p>
 *
 * <p>Symétrique aux « textes collés repliés » (SF-146-01) : <b>le champ fait foi</b>. Une référence
 * effacée n'est pas réinjectée. Différence : le contenu vient de la machine (lecture bornée, async),
 * pas du presse-papier. Module sans dépendance Angular → testable unitairement.</p>
 */

/** Un jeton `@…` en cours de saisie, repéré à gauche du curseur. */
export interface ActiveMention {
  /** Texte tapé après le `@`, jusqu'au curseur (peut être vide juste après `@`). */
  query: string;
  /** Index du `@` dans le brouillon. */
  start: number;
  /** Index du curseur (fin du jeton). */
  end: number;
}

/** Plafond de suggestions affichées — au-delà, la liste cesse d'aider. */
export const MAX_SUGGESTIONS = 8;

/** Un caractère fait-il partie d'un chemin mentionnable (ni espace, ni `@`) ? */
function isMentionChar(ch: string): boolean {
  return ch !== '' && !/\s/.test(ch) && ch !== '@';
}

/**
 * Repère le jeton `@…` immédiatement à gauche du curseur, s'il y en a un.
 *
 * <p>La règle : remonter depuis le curseur tant qu'on lit des caractères de chemin ; le jeton n'est
 * actif que si le caractère juste avant est un `@`, et que ce `@` est en début de champ ou précédé
 * d'un blanc (pour ne pas déclencher sur une adresse e-mail <code>a@b</code>).</p>
 *
 * @return le jeton actif, ou `null` si le curseur n'est pas dans un `@`-jeton.
 */
export function activeMention(draft: string, caret: number): ActiveMention | null {
  const text = draft ?? '';
  const pos = Math.max(0, Math.min(caret, text.length));
  let i = pos;
  while (i > 0 && isMentionChar(text[i - 1])) {
    i--;
  }
  // i pointe sur le premier caractère du jeton candidat ; le caractère juste avant doit être `@`.
  if (i === 0 || text[i - 1] !== '@') {
    return null;
  }
  const atIndex = i - 1;
  // Le `@` doit ouvrir un mot : début de champ ou précédé d'un blanc. Sinon (ex. e-mail) : pas de
  // mention.
  if (atIndex > 0 && !/\s/.test(text[atIndex - 1])) {
    return null;
  }
  return { query: text.slice(i, pos), start: atIndex, end: pos };
}

/**
 * Filtre les chemins du projet par la requête (sous-chaîne, insensible à la casse), plafonné.
 *
 * <p>Une requête vide (juste après `@`) rend les premiers chemins connus : on aide dès le `@`.</p>
 */
export function suggestPaths(
  paths: readonly string[],
  query: string,
  limit: number = MAX_SUGGESTIONS,
): string[] {
  const q = (query ?? '').toLowerCase();
  const out: string[] = [];
  for (const path of paths ?? []) {
    if (!path) {
      continue;
    }
    if (q.length === 0 || path.toLowerCase().includes(q)) {
      out.push(path);
      if (out.length >= limit) {
        break;
      }
    }
  }
  return out;
}

/** Ce qui est posé dans le champ à la sélection. Volontairement lisible et effaçable. */
export function referenceOf(path: string): string {
  return `@${path}`;
}

/** Résultat d'une insertion : le nouveau brouillon et la position du curseur après la référence. */
export interface MentionInsertion {
  draft: string;
  caret: number;
}

/**
 * Remplace le jeton `@…` actif par la référence `@chemin ` (avec un espace de suite), et rend la
 * position du curseur juste après.
 */
export function applyMention(
  draft: string,
  mention: ActiveMention,
  path: string,
): MentionInsertion {
  const text = draft ?? '';
  const reference = `${referenceOf(path)} `;
  const next = text.slice(0, mention.start) + reference + text.slice(mention.end);
  return { draft: next, caret: mention.start + reference.length };
}

/**
 * Les chemins **connus** mentionnés dans le brouillon, distincts, dans l'ordre d'apparition.
 *
 * <p><b>La garantie de non-surprise</b> : un `@` qui ne correspond à aucun fichier connu, ou dont la
 * référence a été effacée, n'entraîne aucune lecture ni aucune injection.</p>
 */
export function mentionedPaths(draft: string, knownPaths: readonly string[]): string[] {
  const text = draft ?? '';
  const known = new Set(knownPaths ?? []);
  const seen = new Set<string>();
  const out: string[] = [];
  const re = /(^|\s)@([^\s@]+)/g;
  let match: RegExpExecArray | null;
  while ((match = re.exec(text)) !== null) {
    const path = match[2];
    if (known.has(path) && !seen.has(path)) {
      seen.add(path);
      out.push(path);
    }
  }
  return out;
}

/** Le contenu lu d'un fichier mentionné — ou un échec de lecture. */
export interface MentionedFile {
  path: string;
  content: string | null;
}

/**
 * Construit le bloc de contexte apposé au message : un cartouche par fichier mentionné, étiqueté par
 * chemin. Un fichier illisible reçoit un marqueur explicite plutôt que de bloquer l'envoi.
 *
 * <p>Rend une chaîne <b>vide</b> s'il n'y a rien à apposer, pour ne pas gonfler le message.</p>
 */
export function buildContextBlock(files: readonly MentionedFile[]): string {
  const usable = (files ?? []).filter((f) => f && f.path);
  if (usable.length === 0) {
    return '';
  }
  const parts = usable.map((f) => {
    if (f.content === null) {
      return `@${f.path} :\n(contenu non lu — fichier hors ligne ou inaccessible)`;
    }
    return `@${f.path} :\n\`\`\`\n${f.content}\n\`\`\``;
  });
  return `\n\n--- Fichiers mentionnés (@) ---\n\n${parts.join('\n\n')}`;
}
