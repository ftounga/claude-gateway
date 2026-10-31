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

/** Le nom du fichier, pour l'en-tête et le téléchargement. */
export function depositFileName(path: string): string {
  return (path ?? '').split(/[\\/]/).pop() || 'piece';
}
