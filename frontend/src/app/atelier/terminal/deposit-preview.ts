/**
 * **L'aperçu d'une pièce jointe** (F-228) — fonctions pures : quelle vue pour quel fichier, et l'URL de la route.
 * Le type vient de l'extension, comme côté passerelle ; un HTML n'est jamais rendu (téléchargement).
 */

/** La vue d'aperçu d'une pièce. */
export type PreviewKind = 'image' | 'svg' | 'pdf' | 'video' | 'audio' | 'csv' | 'xlsx' | 'download';

const IMAGE = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp']);
const VIDEO = new Set(['mp4', 'webm', 'mov']);
const AUDIO = new Set(['m4a', 'mp3', 'wav', 'ogg']);

/** L'extension en minuscules, sans le point (« » sans extension). */
export function extensionOf(path: string): string {
  const name = (path ?? '').split(/[\\/]/).pop() ?? '';
  const dot = name.lastIndexOf('.');
  return dot < 0 ? '' : name.slice(dot + 1).toLowerCase();
}

/** La vue d'une pièce, par son extension. */
export function previewKindOf(path: string): PreviewKind {
  const ext = extensionOf(path);
  if (IMAGE.has(ext)) {
    return 'image';
  }
  if (ext === 'svg') {
    return 'svg';
  }
  if (ext === 'pdf') {
    return 'pdf';
  }
  if (VIDEO.has(ext)) {
    return 'video';
  }
  if (AUDIO.has(ext)) {
    return 'audio';
  }
  if (ext === 'csv') {
    return 'csv';
  }
  if (ext === 'xlsx') {
    return 'xlsx';
  }
  return 'download';
}

/** La route du contenu d'une pièce (F-228 / SF-228-01). */
export function depositContentUrl(workspaceId: string, depositId: string): string {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}/deposits/${encodeURIComponent(depositId)}/content`;
}

/** Au plus autant de lignes montrées d'un tableur (F-228 / SF-228-03). */
export const MAX_TABLE_ROWS = 500;

/** Un onglet de tableur, prêt à afficher : des chaînes, jamais de HTML. */
export interface PreviewSheet {
  name: string;
  rows: string[][];
  /** Des lignes ont été coupées au-delà de {@link MAX_TABLE_ROWS}. */
  truncated: boolean;
}

/** Le séparateur d'un CSV, deviné sur la première ligne hors guillemets : `;`, tabulation ou `,`. */
export function csvDelimiter(text: string): string {
  const counts: Record<string, number> = { ',': 0, ';': 0, '\t': 0 };
  let quoted = false;
  for (const ch of text) {
    if (ch === '"') {
      quoted = !quoted;
    } else if (!quoted && (ch === '\n' || ch === '\r')) {
      break;
    } else if (!quoted && ch in counts) {
      counts[ch]++;
    }
  }
  return (Object.entries(counts).sort((a, b) => b[1] - a[1])[0][1] > 0)
    ? Object.entries(counts).sort((a, b) => b[1] - a[1])[0][0] : ',';
}

/**
 * **Un CSV, lu en tableau** (F-228 / SF-228-03) — analyseur maison (RFC 4180 : guillemets, guillemets doublés,
 * retours à la ligne entre guillemets), séparateur deviné, borné à {@link MAX_TABLE_ROWS} lignes.
 */
export function parseCsv(text: string, maxRows = MAX_TABLE_ROWS): PreviewSheet {
  const source = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;
  const delimiter = csvDelimiter(source);
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  let truncated = false;
  for (let i = 0; i < source.length; i++) {
    const ch = source[i];
    if (quoted) {
      if (ch === '"' && source[i + 1] === '"') {
        cell += '"';
        i++;
      } else if (ch === '"') {
        quoted = false;
      } else {
        cell += ch;
      }
      continue;
    }
    if (ch === '"') {
      quoted = true;
    } else if (ch === delimiter) {
      row.push(cell);
      cell = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && source[i + 1] === '\n') {
        i++;
      }
      row.push(cell);
      cell = '';
      rows.push(row);
      row = [];
      if (rows.length >= maxRows) {
        truncated = i + 1 < source.length;
        break;
      }
    } else {
      cell += ch;
    }
  }
  if (!truncated && (cell !== '' || row.length > 0)) {
    row.push(cell);
    rows.push(row);
  }
  return { name: 'CSV', rows, truncated };
}

/** Une cellule de tableur en texte : date lisible, nombre tel quel, vide pour `null`. */
export function cellText(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  if (value instanceof Date) {
    return isNaN(value.getTime()) ? '' : value.toLocaleDateString('fr-FR');
  }
  return String(value);
}

/** Le nom du fichier, pour l'en-tête et le téléchargement. */
export function depositFileName(path: string): string {
  return (path ?? '').split(/[\\/]/).pop() || 'piece';
}
