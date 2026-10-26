/** **Les vidéos** (F-213) : ce que la gateway rend pour une vidéo du compte. */

/** Un chapitre (`t` en secondes). */
export interface VideoChapter {
  t: number;
  titre: string;
}

/**
 * Une ligne de la **fiche des sources** d'une vidéo (F-215 / SF-215-04) : une affirmation technique et d'où elle
 * vient, ou « à confirmer » faute de source.
 */
export interface VideoSource {
  affirmation: string;
  source: string | null;
  aConfirmer: boolean;
}

/**
 * Une vidéo (ou une piste audio) telle que `GET /videos/{id}` la rend : les adresses de lecture portent un ticket
 * frais (jamais le JWT), valables quelques heures.
 */
export interface VideoSummary {
  id: string;
  /** Le sujet de la vidéo (F-214 : « Envoyer à l'agent » depuis la galerie d'un poste). */
  workspaceId?: string | null;
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
  /** La fiche des sources de la version servie (F-215 / SF-215-04) ; absente ou vide sinon. */
  sources?: VideoSource[];
  /** La voix fixée pour la série (F-215 / SF-215-04), ou `null`. */
  seriesVoice?: string | null;
  /** Coût réel mesuré, en dollars (F-216), ou `null` : publiée sans devis. */
  costUsd?: number | null;
  /** Estimation du devis (F-216), ou `null`. */
  estimateLowUsd?: number | null;
  estimateHighUsd?: number | null;
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
  /** Coût réel mesuré et estimation (F-216), `null` si non mesuré. */
  costUsd?: number | null;
  estimateLowUsd?: number | null;
  estimateHighUsd?: number | null;
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
  /** Coût et estimation sommés sur les épisodes (F-216), `null` si aucun n'est mesuré. */
  costUsd?: number | null;
  estimateLowUsd?: number | null;
  estimateHighUsd?: number | null;
}

/** Ce que le compte a dépensé en vidéos ce mois-ci (F-216 / SF-216-03, `GET /videos/costs`). */
export interface VideoMonthCost {
  /** « 2026-10 ». */
  month: string;
  spentUsd: number;
  /** Devis créés ce mois-ci. */
  quotes: number;
}

/** La galerie d'un sujet ou d'un poste : les séries, puis les vidéos seules. */
export interface VideoGallery {
  series: VideoGallerySeries[];
  videos: VideoGalleryItem[];
}

/** Le lieu d'une galerie : un sujet, ou tout un poste. */
export type VideoGalleryPlace = { workspaceId: string } | { hostId: string };

/** Une version conservée d'une vidéo (`GET /videos/{id}/versions`). */
export interface VideoVersionInfo {
  version: number;
  sizeBytes: number;
  mime: string;
  poster: boolean;
  createdAt: string;
}

/** Le statut d'un retour à la seconde (F-214). */
export type VideoCommentStatus = 'OUVERT' | 'ENVOYE' | 'TRAITE' | 'REJETE';

/** Un retour à la seconde, attaché à un instant (ou une plage) d'une version (F-214 / SF-214-01). */
export interface VideoComment {
  id: string;
  videoId: string;
  version: number;
  tStartMs: number;
  tEndMs: number | null;
  frame: number;
  endFrame: number | null;
  text: string;
  status: VideoCommentStatus;
  resolution: string | null;
  resolvedVersion: number | null;
  sentAt: string | null;
  createdAt: string;
}

/** Ce que rend `POST /videos/{id}/comments/send` : le message à placer dans la saisie, les retours envoyés. */
export interface VideoCommentsSent {
  message: string;
  comments: VideoComment[];
}
