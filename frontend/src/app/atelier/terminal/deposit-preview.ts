/**
 * **L'aperçu d'une pièce jointe** (F-228) — fonctions pures : quelle vue pour quel fichier, et l'URL de la route.
 * Le type vient de l'extension, comme côté passerelle ; un HTML n'est jamais rendu (téléchargement).
 */

/** La vue d'aperçu d'une pièce. */
export type PreviewKind = 'image' | 'svg' | 'pdf' | 'video' | 'audio' | 'csv' | 'xlsx' | 'text' | 'docx' | 'download';

const IMAGE = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp']);
/** Le texte montré tel quel en `<pre>` (F-228 / SF-228-04). */
const TEXT = new Set(['txt', 'md', 'log', 'json']);
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
  if (TEXT.has(ext)) {
    return 'text';
  }
  if (ext === 'docx') {
    return 'docx';
  }
  return 'download';
}

/** Le plafond d'un aperçu, aligné sur la passerelle (SF-228-01) : au-delà, [Télécharger] seulement. */
export const MAX_PREVIEW_BYTES = 20 * 1024 * 1024;

/** La route du téléchargement d'une pièce, en flux, jusqu'à 100 Mo (F-228 / SF-228-04). */
export function depositDownloadUrl(workspaceId: string, depositId: string): string {
  return `/api/workspaces/${encodeURIComponent(workspaceId)}/deposits/${encodeURIComponent(depositId)}/download`;
}

/** Au plus autant d'octets (ou de caractères pour un docx) montrés d'un texte (F-228 / SF-228-04). */
export const MAX_TEXT_BYTES = 200 * 1024;

/** Un texte prêt à afficher en `<pre>` : jamais de HTML, coupé au-delà de {@link MAX_TEXT_BYTES}. */
export interface PreviewText {
  text: string;
  truncated: boolean;
}

/**
 * Les {@link MAX_TEXT_BYTES} premiers octets d'un fichier texte, décodés en UTF-8 sans couper un caractère : le
 * décodage en flux (`stream: true`) garde pour lui un caractère inachevé en fin de tranche au lieu d'un « � ».
 */
export function decodeTextPreview(bytes: Uint8Array, max = MAX_TEXT_BYTES): PreviewText {
  const truncated = bytes.length > max;
  const text = new TextDecoder('utf-8').decode(truncated ? bytes.subarray(0, max) : bytes, { stream: truncated });
  return { text, truncated };
}

/** Un texte déjà décodé (docx), coupé à {@link MAX_TEXT_BYTES} caractères. */
export function truncateText(text: string, max = MAX_TEXT_BYTES): PreviewText {
  return text.length > max ? { text: text.slice(0, max), truncated: true } : { text, truncated: false };
}

/**
 * Le texte d'un CSV (F-228 / SF-228-04) : UTF-8 s'il en est (BOM compris) ; sinon **Windows-1252** — l'export CSV
 * d'Excel en français, dont les accents sortaient en « � ».
 */
export function decodeCsv(bytes: Uint8Array): string {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return new TextDecoder('windows-1252').decode(bytes);
  }
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

/**
 * Une cellule de tableur en texte : date lisible, nombre tel quel, vide pour `null`. Une date de classeur n'a pas de
 * fuseau : `read-excel-file` la rend à minuit **UTC** ; la lire en heure locale montrait la veille à l'ouest de
 * Greenwich (F-228 / SF-228-04). Lue en UTC : `jj/mm/aaaa`, l'heure ajoutée seulement si elle n'est pas minuit.
 */
export function cellText(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  if (value instanceof Date) {
    if (isNaN(value.getTime())) {
      return '';
    }
    const two = (n: number): string => String(n).padStart(2, '0');
    const day = `${two(value.getUTCDate())}/${two(value.getUTCMonth() + 1)}/${value.getUTCFullYear()}`;
    const h = value.getUTCHours();
    const m = value.getUTCMinutes();
    const s = value.getUTCSeconds();
    if (h === 0 && m === 0 && s === 0) {
      return day;
    }
    return `${day} ${two(h)}:${two(m)}${s ? `:${two(s)}` : ''}`;
  }
  return String(value);
}

/** Le nom du fichier, pour l'en-tête et le téléchargement. */
export function depositFileName(path: string): string {
  return (path ?? '').split(/[\\/]/).pop() || 'piece';
}
