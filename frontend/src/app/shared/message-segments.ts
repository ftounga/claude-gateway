import { CopyBlock, CopyBlockType, MessageSegment } from './copy-block.model';

/**
 * Fence Markdown ouvert par ``` (≥ 3 backticks), un token de langage optionnel, du contenu, puis
 * une clôture par le même nombre de backticks. Le groupe `content` peut être vide (traité comme
 * prose ensuite). Multiligne ; la casse du langage est gérée en aval.
 */
const FENCE_REGEX = /(^|\n)(`{3,})[ \t]*([^\n`]*)\n([\s\S]*?)\n\2[ \t]*(?=\n|$)/g;

/** Tokens de langage → type de bloc. Tout le reste (ou absence de token) ⇒ `code`. */
const MAIL_TOKENS = new Set(['mail', 'email', 'eml']);
const DOC_TOKENS = new Set(['md', 'markdown', 'doc', 'document', 'text', 'txt', 'html']);

const TYPE_LABELS: Record<CopyBlockType, string> = {
  code: 'Code',
  doc: 'Document',
  mail: 'E-mail',
};

/** Déduit le type de bloc à partir du token de langage du fence. */
export function copyBlockTypeFromLanguage(language: string | null): CopyBlockType {
  const token = (language ?? '').trim().toLowerCase();
  if (MAIL_TOKENS.has(token)) {
    return 'mail';
  }
  if (DOC_TOKENS.has(token)) {
    return 'doc';
  }
  return 'code';
}

/** Construit un libellé lisible : « Code (typescript) », « Document », « E-mail »… */
function buildTitle(type: CopyBlockType, language: string | null): string {
  const base = TYPE_LABELS[type];
  const token = (language ?? '').trim();
  return type === 'code' && token.length > 0 ? `${base} (${token})` : base;
}

/** Ajoute un segment de prose si le texte n'est pas entièrement blanc. */
function pushProse(segments: MessageSegment[], text: string): void {
  if (text.trim().length > 0) {
    segments.push({ kind: 'prose', text });
  }
}

/**
 * Découpe le contenu d'un message assistant en segments ordonnés (prose / bloc copiable).
 *
 * <p>Déterministe et pur : aucune dépendance réseau ni fournisseur IA. Le texte hors fence devient
 * de la prose (rendue en Markdown en aval) ; chaque fenced block clos et non vide devient un bloc
 * copiable. Un fence vide ou non clos (streaming en cours) reste dans la prose. L'ordre de sortie
 * suit l'ordre d'origine du message.</p>
 */
export function splitMessageSegments(content: string | null | undefined): MessageSegment[] {
  const source = content ?? '';
  if (source.length === 0) {
    return [];
  }
  const segments: MessageSegment[] = [];
  let proseStart = 0;
  FENCE_REGEX.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = FENCE_REGEX.exec(source)) !== null) {
    const blockContent = match[4] ?? '';
    // Fence vide → on le laisse dans la prose (rendu tel quel), pas de bloc copiable.
    if (blockContent.trim().length === 0) {
      continue;
    }
    const fenceStart = match.index + match[1].length;
    pushProse(segments, source.slice(proseStart, fenceStart));

    const rawLanguage = (match[3] ?? '').trim();
    const language = rawLanguage.length > 0 ? rawLanguage : null;
    const type = copyBlockTypeFromLanguage(language);
    const block: CopyBlock = { type, language, title: buildTitle(type, language), content: blockContent };
    segments.push({ kind: 'block', block });

    proseStart = match.index + match[0].length;
  }
  pushProse(segments, source.slice(proseStart));
  return segments;
}
