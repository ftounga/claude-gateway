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
