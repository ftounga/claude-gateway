/**
 * **Les médias collés, marqués dans le texte** (F-201 / SF-201-02) — fonctions pures.
 *
 * <p>Comme Claude Code : une capture collée pose `[Image #1]` <b>à l'endroit du curseur</b>, et l'on
 * continue à taper autour. Le marqueur fait foi : l'effacer retire la pièce, retirer la puce retire le
 * marqueur. À l'envoi, chaque marqueur encore présent devient une référence explicite au fichier
 * déposé, à l'endroit exact du texte — l'agent sait de quelle image on parle, et où.</p>
 */

export type MediaKind = 'image' | 'video' | 'file';

/** Compteurs d'un brouillon : la numérotation est monotone par type, jamais réutilisée avant l'envoi. */
export type MediaCounters = Record<MediaKind, number>;

export function emptyCounters(): MediaCounters {
  return { image: 0, video: 0, file: 0 };
}

const IMAGE_EXT = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'heic', 'heif', 'avif', 'tif', 'tiff']);
const VIDEO_EXT = new Set(['mp4', 'mov', 'webm', 'mkv', 'avi', 'm4v', 'ogv']);

/** Le type d'un média, d'après l'extension de son chemin déposé. */
export function mediaKindOf(path: string | undefined): MediaKind {
  const name = (path ?? '').split('/').pop() ?? '';
  const dot = name.lastIndexOf('.');
  const ext = dot > 0 ? name.slice(dot + 1).toLowerCase() : '';
  if (IMAGE_EXT.has(ext)) {
    return 'image';
  }
  if (VIDEO_EXT.has(ext)) {
    return 'video';
  }
  return 'file';
}

const LABELS: Record<MediaKind, string> = { image: 'Image', video: 'Vidéo', file: 'Fichier' };

/** Le marqueur posé dans la saisie : `[Image #1]`, `[Vidéo #1]`, `[Fichier #1]`. */
export function markerOf(kind: MediaKind, index: number): string {
  return `[${LABELS[kind]} #${index}]`;
}

/** Attribue le prochain marqueur d'un type et avance le compteur (retourne de nouveaux compteurs). */
export function nextMarker(counters: MediaCounters, kind: MediaKind): { marker: string; counters: MediaCounters } {
  const index = counters[kind] + 1;
  return { marker: markerOf(kind, index), counters: { ...counters, [kind]: index } };
}

/**
 * Insère `text` au curseur (en remplaçant la sélection `[start, end)`), en l'entourant d'espaces
 * seulement là où il touche un mot. Rend le brouillon et la position juste après l'insertion.
 */
export function insertAtCaret(
  draft: string,
  start: number | null | undefined,
  text: string,
  end?: number | null,
): { draft: string; caret: number } {
  const value = draft ?? '';
  const from = clamp(start ?? value.length, value.length);
  const to = Math.max(from, clamp(end ?? from, value.length));
  const before = value.slice(0, from);
  const after = value.slice(to);
  const lead = before.length > 0 && !/\s$/.test(before) ? ' ' : '';
  const trail = after.length > 0 && !/^\s/.test(after) ? ' ' : '';
  const inserted = `${lead}${text}${trail}`;
  return { draft: before + inserted + after, caret: before.length + lead.length + text.length };
}

function clamp(position: number, length: number): number {
  return Math.min(Math.max(0, position), length);
}

/** Retire un marqueur du brouillon (geste « retirer la puce »), sans laisser d'espaces doublés. */
export function removeMarker(draft: string, marker: string): string {
  return (draft ?? '').split(marker).join('').replace(/[ \t]{2,}/g, ' ').trim();
}

/** Ce que l'agent lit à la place du marqueur : `[Image #1 = .atelier/entrees/capture-….png]`. */
export function resolvedMarker(marker: string, path: string): string {
  return `${marker.slice(0, -1)} = ${path}]`;
}

/** Une pièce marquée : son marqueur et le chemin où elle a été déposée. */
export interface MarkedMedia {
  marker?: string;
  path?: string;
}

/**
 * À l'envoi : chaque marqueur **encore présent** devient sa référence explicite, à sa place. Un
 * marqueur effacé n'est pas réinjecté — rien ne part que l'utilisateur n'ait sous les yeux.
 */
export function translateMarkers(draft: string, media: MarkedMedia[]): string {
  let out = draft ?? '';
  for (const item of media) {
    if (!item.marker || !item.path || !out.includes(item.marker)) {
      continue;
    }
    out = out.split(item.marker).join(resolvedMarker(item.marker, item.path));
  }
  return out;
}

/** Les pièces dont le marqueur a disparu du brouillon (l'utilisateur l'a effacé). */
export function vanishedMarkers<T extends MarkedMedia>(draft: string, media: T[]): T[] {
  const value = draft ?? '';
  return media.filter((item) => !!item.marker && !value.includes(item.marker));
}
