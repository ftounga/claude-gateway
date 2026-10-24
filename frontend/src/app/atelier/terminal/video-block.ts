import {
  AtelierTerminalAudio,
  AtelierTerminalBlock,
  AtelierTerminalVideo,
} from '../../core/models/atelier.models';

/**
 * **Les blocs « vidéo » et « audio »** (F-213 / SF-213-02), en fonctions pures.
 */

/** Les vitesses proposées sous le lecteur (cadrage §5 : 0,75 à 2×). */
export const VIDEO_SPEEDS: readonly number[] = [0.75, 1, 1.25, 1.5, 2];

/** `1:05`, `12:30`, `1:02:03` — l'instant d'un chapitre ou une durée. */
export function formatClock(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds) || seconds < 0) {
    return '';
  }
  const total = Math.floor(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

/** `0,75×`, `1×`, `1,5×`. */
export function formatSpeed(speed: number): string {
  return `${String(speed).replace('.', ',')}×`;
}

/** Le titre du bloc : « Vidéo — Le cluster EKS », ou « Épisode 2 — Les nœuds » dans une série. */
export function videoHeadline(video: AtelierTerminalVideo): string {
  return video.episode != null && video.seriesTitle
    ? `Épisode ${video.episode} — ${video.title}`
    : `Vidéo — ${video.title}`;
}

/** L'index du chapitre en cours à l'instant `t` (le dernier dont le début est passé), ou -1. */
export function currentChapterIndex(chapters: { t: number }[], t: number): number {
  let index = -1;
  for (let i = 0; i < chapters.length; i++) {
    if (chapters[i].t <= t + 0.25) {
      index = i;
    }
  }
  return index;
}

/**
 * L'adresse de la vidéo quand elle n'a pas de couverture : le fragment `#t=` fait afficher au navigateur l'image
 * à 10 % de la durée (cadrage §3, « extraite à 10 % ») — sans traitement côté serveur.
 */
export function mediaSourceWithPreview(mediaUrl: string, durationS: number | null, hasPoster: boolean): string {
  if (hasPoster || !durationS || durationS <= 0) {
    return mediaUrl;
  }
  return `${mediaUrl}#t=${Math.max(0.1, Math.round(durationS * 0.1 * 10) / 10)}`;
}

/** L'adresse de la galerie Vidéos d'un sujet, ouverte sur une vidéo (F-213 / SF-213-03). */
export function videoGalleryPath(workspaceId: string, videoId?: string | null): string {
  const base = `/atelier/${encodeURIComponent(workspaceId)}/videos`;
  return videoId ? `${base}?video=${encodeURIComponent(videoId)}` : base;
}

/** Le bloc de transcription d'une vidéo reçue au fil de l'eau (rangé comme une carte). */
export function videoBlock(toolUseId: string, video: AtelierTerminalVideo): AtelierTerminalBlock {
  return {
    tool: 'video_publier',
    toolUseId,
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    video,
  };
}

/** Le bloc de transcription de sons reçus au fil de l'eau (rangé comme une carte). */
export function audioBlock(toolUseId: string, audio: AtelierTerminalAudio): AtelierTerminalBlock {
  return {
    tool: 'audio_publier',
    toolUseId,
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    audio,
  };
}
