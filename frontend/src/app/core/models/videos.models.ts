/** **Les vidéos** (F-213) : ce que la gateway rend pour une vidéo du compte. */

/** Un chapitre (`t` en secondes). */
export interface VideoChapter {
  t: number;
  titre: string;
}

/**
 * Une vidéo (ou une piste audio) telle que `GET /videos/{id}` la rend : les adresses de lecture portent un ticket
 * frais (jamais le JWT), valables quelques heures.
 */
export interface VideoSummary {
  id: string;
  kind: 'VIDEO' | 'AUDIO';
  title: string;
  description: string | null;
  seriesId: string | null;
  seriesTitle: string | null;
  episode: number | null;
  currentVersion: number;
  version: number;
  durationS: number | null;
  mime: string;
  sizeBytes: number;
  label: string | null;
  chapters: VideoChapter[];
  mediaUrl: string;
  posterUrl: string | null;
  downloadUrl: string;
  createdAt: string;
  updatedAt: string;
}

/** Une vidéo telle qu'une carte de la galerie la montre (F-213 / SF-213-03). */
export interface VideoGalleryItem {
  id: string;
  title: string;
  description: string | null;
  seriesId: string | null;
  episode: number | null;
  durationS: number | null;
  currentVersion: number;
  chapterCount: number;
  posterUrl: string | null;
  createdAt: string;
}

/** Une série : titre, nombre d'épisodes, durée totale connue, épisodes dans l'ordre. */
export interface VideoGallerySeries {
  id: string;
  title: string;
  description: string | null;
  episodeCount: number;
  totalDurationS: number;
  episodes: VideoGalleryItem[];
  createdAt: string;
}

/** La galerie d'un sujet ou d'un poste : les séries, puis les vidéos seules. */
export interface VideoGallery {
  series: VideoGallerySeries[];
  videos: VideoGalleryItem[];
}

/** Le lieu d'une galerie : un sujet, ou tout un poste. */
export type VideoGalleryPlace = { workspaceId: string } | { hostId: string };
