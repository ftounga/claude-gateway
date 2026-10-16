import { CopyBlock, CopyBlockType, MessageSegment } from './copy-block.model';

/**
 * Fence Markdown ouvert par ``` (≥ 3 backticks), un token de langage optionnel, du contenu, puis
 * une clôture par le même nombre de backticks. Le groupe `content` peut être vide (traité comme
 * prose ensuite). Multiligne ; la casse du langage est gérée en aval.
 */
const FENCE_REGEX = /(^|\n)(`{3,})[ \t]*([^\n`]*)\n([\s\S]*?)\n\2[ \t]*(?=\n|$)/g;

/** Tokens de langage → type de bloc. Tout le reste (ou absence de token) ⇒ `code`. */
const MAIL_TOKENS = new Set(['mail', 'email', 'eml']);
const MESSAGE_TOKENS = new Set(['message', 'teams', 'slack']);
const DOC_TOKENS = new Set(['md', 'markdown', 'doc', 'document', 'text', 'txt', 'html']);

const TYPE_LABELS: Record<CopyBlockType, string> = {
  code: 'Code',
  doc: 'Document',
  mail: 'E-mail',
  message: 'Message',
};

/** Déduit le type de bloc à partir du token de langage du fence. */
export function copyBlockTypeFromLanguage(language: string | null): CopyBlockType {
  const token = (language ?? '').trim().toLowerCase();
  if (MAIL_TOKENS.has(token)) {
    return 'mail';
  }
  if (MESSAGE_TOKENS.has(token)) {
    return 'message';
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
  return splitSegments(content, () => true);
}

/** Types de bloc « message à envoyer » (F-203) : courriel et messagerie. */
export function isMessageBlockType(type: CopyBlockType): boolean {
  return type === 'mail' || type === 'message';
}

/**
 * Découpage restreint aux blocs « message à envoyer » (F-203 / SF-203-02), pour le terminal de
 * l'Atelier : seuls les blocs ```mail / ```message (et alias) deviennent des blocs copiables ; tout
 * autre fence (code, document) reste dans la prose, rendue en Markdown comme avant.
 */
export function splitMessageBlocks(content: string | null | undefined): MessageSegment[] {
  return splitSegments(content, isMessageBlockType);
}

/** Vrai si au moins un segment est un bloc copiable. */
export function hasBlockSegment(segments: readonly MessageSegment[]): boolean {
  return segments.some((segment) => segment.kind === 'block');
}

/** Ligne d'objet en tête d'un message : « Objet : … » ou « Subject: … », gras toléré. */
const SUBJECT_LINE = /^\s*(?:\*\*|__)?\s*(?:objet|subject)\s*(?:\*\*|__)?\s*:\s*(?:\*\*|__)?(.*?)(?:\*\*|__)?\s*$/i;

/**
 * Sépare l'objet éventuel (première ligne non vide « Objet : … ») du corps d'un message (F-203).
 * Sans objet, le corps est le contenu inchangé. Les lignes vides qui suivent l'objet sont retirées.
 */
export function splitSubject(content: string): { subject: string | null; body: string } {
  const lines = content.split('\n');
  const first = lines.findIndex((line) => line.trim().length > 0);
  if (first < 0) {
    return { subject: null, body: content };
  }
  const match = SUBJECT_LINE.exec(lines[first]);
  const subject = match ? match[1].trim() : '';
  if (!match || subject.length === 0) {
    return { subject: null, body: content };
  }
  let start = first + 1;
  while (start < lines.length && lines[start].trim().length === 0) {
    start++;
  }
  return { subject, body: lines.slice(start).join('\n') };
}

function splitSegments(
  content: string | null | undefined,
  accept: (type: CopyBlockType) => boolean,
): MessageSegment[] {
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
    const rawLanguage = (match[3] ?? '').trim();
    const language = rawLanguage.length > 0 ? rawLanguage : null;
    const type = copyBlockTypeFromLanguage(language);
    // Bloc non retenu par le découpage (ex. code dans le terminal) → il reste dans la prose.
    if (!accept(type)) {
      continue;
    }
    const fenceStart = match.index + match[1].length;
    pushProse(segments, source.slice(proseStart, fenceStart));

    const block: CopyBlock = { type, language, title: buildTitle(type, language), content: blockContent };
    segments.push({ kind: 'block', block });

    proseStart = match.index + match[0].length;
  }
  pushProse(segments, source.slice(proseStart));
  return segments;
}
