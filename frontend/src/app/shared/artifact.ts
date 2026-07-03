import { Artifact, ArtifactType } from './artifact.model';

/** Message minimal requis pour l'extraction (compatible {@link ChatMessage}). */
export interface ArtifactSourceMessage {
  id: string;
  role: 'USER' | 'ASSISTANT';
  content: string;
}

/**
 * Fence Markdown ouvert par ``` (≥ 3 backticks), un token de langage optionnel, du contenu, puis
 * une clôture par le même nombre de backticks. Le groupe `content` peut être vide (filtré ensuite).
 * Multiligne, insensible à la casse gérée en aval.
 */
const FENCE_REGEX = /(^|\n)(`{3,})[ \t]*([^\n`]*)\n([\s\S]*?)\n\2[ \t]*(?=\n|$)/g;

/** Tokens de langage → type d'artefact. Tout le reste (ou absence de token) ⇒ `code`. */
const MAIL_TOKENS = new Set(['mail', 'email', 'eml']);
const DOC_TOKENS = new Set(['md', 'markdown', 'doc', 'document', 'text', 'txt', 'html']);

/** Déduit le type d'artefact à partir du token de langage du fence. */
export function artifactTypeFromLanguage(language: string | null): ArtifactType {
  const token = (language ?? '').trim().toLowerCase();
  if (MAIL_TOKENS.has(token)) {
    return 'mail';
  }
  if (DOC_TOKENS.has(token)) {
    return 'doc';
  }
  return 'code';
}

const TYPE_LABELS: Record<ArtifactType, string> = {
  code: 'Code',
  doc: 'Document',
  mail: 'E-mail',
};

/** Construit un libellé lisible : « Code (typescript) », « Document », « E-mail »… */
function buildTitle(type: ArtifactType, language: string | null): string {
  const base = TYPE_LABELS[type];
  const token = (language ?? '').trim();
  return type === 'code' && token.length > 0 ? `${base} (${token})` : base;
}

/**
 * Extrait les artefacts (blocs fenced Markdown) de tous les messages **assistant** fournis.
 *
 * <p>Déterministe et pur : aucune dépendance réseau ni fournisseur IA. Les messages utilisateur
 * sont ignorés ; les blocs vides (contenu blanc) ou non clos sont écartés. L'ordre de sortie suit
 * l'ordre des messages puis l'ordre des blocs dans chaque message.</p>
 */
export function extractArtifacts(messages: readonly ArtifactSourceMessage[]): Artifact[] {
  const artifacts: Artifact[] = [];
  for (const message of messages) {
    if (message.role !== 'ASSISTANT' || !message.content) {
      continue;
    }
    let index = 0;
    // Nouvelle instance implicite via exec en boucle : reset lastIndex par sécurité.
    FENCE_REGEX.lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = FENCE_REGEX.exec(message.content)) !== null) {
      const rawLanguage = match[3]?.trim() ?? '';
      const content = match[4] ?? '';
      if (content.trim().length === 0) {
        continue;
      }
      const language = rawLanguage.length > 0 ? rawLanguage : null;
      const type = artifactTypeFromLanguage(language);
      artifacts.push({
        id: `${message.id}#${index}`,
        messageId: message.id,
        index,
        type,
        language,
        title: buildTitle(type, language),
        content,
      });
      index += 1;
    }
  }
  return artifacts;
}

/** Vrai si le message assistant contient au moins un artefact. */
export function messageHasArtifacts(message: ArtifactSourceMessage): boolean {
  return extractArtifacts([message]).length > 0;
}
