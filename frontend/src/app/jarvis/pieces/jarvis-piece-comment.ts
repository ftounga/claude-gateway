import { normalize } from '../jarvis-oral';
import { PieceNav, selectedItem } from './jarvis-piece-nav';

/**
 * F-237 / SF-237-12 — **le commentaire vocal horodaté** d'une vidéo (cadrage §14 idée 3) et **l'épinglage au
 * cockpit** (idée 4). Pur.
 *
 * « Note ici : le logo est trop petit » pendant qu'une vidéo est ouverte ⇒ Jarvis **reformule et demande
 * confirmation** (« Je note à 0:42 : … Vous confirmez ? ») ; seul un « oui » net écrit le retour, par la route
 * existante `POST /api/videos/{id}/comments` (table `video_comments`, F-215). Le retour rejoint la liste que l'agent
 * lit déjà (`video_retours`) : aucun outil ajouté au tour Opus (préfixe intact).
 */

/** Un retour dicté, en attente de confirmation. */
export interface VoiceComment {
  videoId: string;
  version: number | null;
  tStartMs: number;
  text: string;
}

/** Longueur au plus d'un retour dicté. */
export const MAX_COMMENT_CHARS = 500;

const COMMENT = /^(?:jarvis[\s,]+)?(?:note(?:\s+ici)?|commentaire|retour|remarque)\s*(?:[:,-]|\s)\s*(.+)$/i;

/** L'identifiant de vidéo et la version d'une pièce `video:<uuid>@v2`. */
export function videoOf(pieceId: string): { videoId: string; version: number | null } | null {
  const m = /^video:([0-9a-f-]{36})(?:@v(\d+))?$/.exec(pieceId);
  return m ? { videoId: m[1], version: m[2] ? Number(m[2]) : null } : null;
}

/**
 * Le retour d'une phrase (« note ici : … ») si une vidéo ou un audio publié est ouvert, sinon `null` (la phrase suit
 * son chemin normal). `mediaTime` = l'instant de lecture courant (secondes).
 */
export function parseVoiceComment(said: string | null | undefined, nav: PieceNav, mediaTime: number | null):
  VoiceComment | null {
  const item = selectedItem(nav);
  if (!item || (item.kind !== 'VIDEO' && item.kind !== 'AUDIO')) {
    return null;
  }
  const video = videoOf(item.id);
  if (!video) {
    return null;
  }
  const m = COMMENT.exec((said ?? '').trim());
  const text = m?.[1]?.trim().replace(/^[«"]+|[»"]+$/g, '').trim();
  if (!text || normalize(text).length < 2) {
    return null;
  }
  const seconds = mediaTime ?? nav.time ?? 0;
  return {
    videoId: video.videoId, version: video.version, tStartMs: Math.max(0, Math.round(seconds * 1000)),
    text: text.slice(0, MAX_COMMENT_CHARS),
  };
}

/** « 0:42 », « 1:05:03 ». */
export function clockOf(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}

/** La reformulation à confirmer (règle « une action qui engage = reformulée + oui »). */
export function commentQuestion(comment: VoiceComment): string {
  return `Je note à ${clockOf(comment.tStartMs)} : « ${comment.text} ». Vous confirmez ?`;
}

/** « épingle au cockpit », « mets-la sur le grand écran »… (liste fermée, phrase entière). */
export function isPinCommand(said: string | null | undefined): boolean {
  const n = normalize(said ?? '').replace(/^jarvis\s+/, '');
  return /^(epingle|epingle la|epingle le|epingle au cockpit|epingle la au cockpit|epingle le au cockpit|mets la sur le grand ecran|mets le sur le grand ecran|sur le grand ecran|envoie au cockpit|au cockpit)$/
    .test(n);
}

/** « désépingle », « retire du cockpit ». */
export function isUnpinCommand(said: string | null | undefined): boolean {
  const n = normalize(said ?? '').replace(/^jarvis\s+/, '');
  return /^(desepingle|desepingle la|desepingle le|retire du cockpit|enleve du cockpit|libere le cockpit)$/.test(n);
}
