import { JarvisPieceClient } from './jarvis-piece-client';
import { GalleryMode } from './jarvis-piece-show';
import { JarvisPieceStore } from './jarvis-piece-store';
import { Piece } from './jarvis-piece.models';

/**
 * F-237 / SF-237-14 — **les pièces du banc de captures** (`/jarvis-banc?banc=pieces-…`, développement seulement) :
 * une galerie fictive, des images dessinées sur place (PNG, aucun réseau), pour la planche de référence de F-237.
 */

const SUBJECT = 's-forge';

export const BANC_PIECES: Piece[] = [
  { id: 'need:1', kind: 'TEXT', category: 'ENTREE', title: 'Besoin initial du chantier 3',
    caption: 'Corrige l’erreur 502 du portail Edenred : les utilisateurs ne peuvent plus se connecter depuis ce matin.',
    captionSource: 'MESSAGE', at: '2026-11-02T08:02:00Z', subjectId: SUBJECT, chantier: 3, chantierLink: 'MESSAGE' },
  { id: 'dep:a1', kind: 'IMAGE', category: 'ENTREE', title: 'capture-502.png', caption: 'Voici la capture de l’erreur 502.',
    captionSource: 'MESSAGE', at: '2026-11-02T08:03:00Z', subjectId: SUBJECT, chantier: 3, chantierLink: 'MESSAGE',
    thumb: '/banc/erreur', open: '/banc/erreur' },
  { id: 'dep:a2', kind: 'AUDIO', category: 'ENTREE', title: 'note-vocale.m4a', at: '2026-11-02T08:05:00Z',
    subjectId: SUBJECT, chantier: 3, chantierLink: 'WINDOW', meta: { durationS: 48 } },
  { id: 'proof:b4', kind: 'IMAGE', category: 'PREUVE', title: 'Santé du portail',
    caption: 'Étape 4. 2 commandes, toutes réussies', captionSource: 'STEP', at: '2026-11-03T08:40:00Z',
    subjectId: SUBJECT, chantier: 3, chantierLink: 'MESSAGE', thumb: '/banc/preuve', open: '/banc/preuve',
    meta: { step: 'Étape 4' } },
  { id: 'send:s1', kind: 'TEXT', category: 'PREUVE', title: 'Portail rétabli', caption: 'Envoyé à Zahi — « Bonjour Zahi, le portail répond de nouveau. »',
    at: '2026-11-03T09:10:00Z', subjectId: SUBJECT, chantier: 3, chantierLink: 'MESSAGE' },
  { id: 'video:c7@v2', kind: 'VIDEO', category: 'PRODUCTION', title: 'Démo du déploiement', caption: 'Le déploiement sur EKS',
    at: '2026-11-03T08:55:00Z', subjectId: SUBJECT, chantier: 3, chantierLink: 'MESSAGE', thumb: '/banc/video',
    meta: { durationS: 114, chapters: [{ t: 0, title: 'Introduction' }, { t: 40, title: 'Déploiement' }] } },
  { id: 'diag:d9', kind: 'ARCHITECTURE', category: 'PRODUCTION', title: 'Architecture du portail',
    caption: 'VPC de prod, EKS, RDS', captionSource: 'SPEC', at: '2026-11-02T14:10:00Z', subjectId: SUBJECT,
    chantier: 3, chantierLink: 'MESSAGE', thumb: '/banc/archi' },
  { id: 'page:e2@v3', kind: 'PAGE', category: 'PRODUCTION', title: 'Rapport 502',
    caption: 'Ce que montre la page : la cause et le correctif', at: '2026-11-03T09:01:00Z', subjectId: SUBJECT,
    chantier: 3, chantierLink: 'MESSAGE', thumb: '/banc/page' },
];

const PALETTES: Record<string, [string, string, string]> = {
  erreur: ['#2A0F14', '#E5484D', 'HTTP 502 Bad Gateway'],
  preuve: ['#0E2A1E', '#4CF2A0', 'GET /health → 200 OK'],
  video: ['#1A1430', '#E07B39', '▶ Démo du déploiement'],
  archi: ['#0B1A2A', '#5FB4FF', 'VPC prod · EKS · RDS'],
  page: ['#1E1A12', '#F0954F', 'Rapport 502'],
};

/** Une image dessinée sur place (PNG en `data:`), jamais lue sur le réseau. */
export function bancImage(path: string): string | null {
  const key = path.replace(/^\/banc\//, '');
  const palette = PALETTES[key];
  if (!palette || typeof document === 'undefined') {
    return null;
  }
  const canvas = document.createElement('canvas');
  canvas.width = 640;
  canvas.height = 400;
  const g = canvas.getContext('2d');
  if (!g) {
    return null;
  }
  const gradient = g.createLinearGradient(0, 0, 640, 400);
  gradient.addColorStop(0, palette[0]);
  gradient.addColorStop(1, '#0B1020');
  g.fillStyle = gradient;
  g.fillRect(0, 0, 640, 400);
  g.strokeStyle = palette[1];
  g.lineWidth = 3;
  g.strokeRect(24, 24, 592, 352);
  g.fillStyle = palette[1];
  g.font = '600 34px Inter, sans-serif';
  g.fillText(palette[2], 56, 210);
  return canvas.toDataURL('image/png');
}

/** Le client du banc : images dessinées, aucun appel. */
export const BANC_PIECE_CLIENT: Partial<JarvisPieceClient> = {
  list: async () => ({ pieces: BANC_PIECES, total: BANC_PIECES.length, truncated: false }),
  objectUrl: async (path) => (path ? bancImage(path) : null),
  text: async () => null,
  json: async () => null,
};

/** Pose la scène des pièces du banc sur le magasin. */
export function stageBancPieces(store: JarvisPieceStore, scene: string): void {
  const scope = { subjectId: SUBJECT, chantier: 3, label: 'Pièces du chantier 3' };
  const pick = (ids: string[]) => ids.map((id) => BANC_PIECES.find((p) => p.id === id)!).filter(Boolean);
  const mode: GalleryMode = scene === 'pieces-avant-apres' ? 'compare' : scene === 'pieces-planche' ? 'contact' : 'fan';
  store.mode.set(mode);
  if (scene === 'pieces-avant-apres') {
    store.show({ ...scope, label: 'Avant / après — Edenred' }, pick(['dep:a1', 'proof:b4']));
    return;
  }
  if (scene === 'pieces-planche') {
    store.show({ subjectId: null, label: 'Planche contact depuis le 2026-11-03' },
      BANC_PIECES.filter((p) => p.category === 'PRODUCTION'));
    return;
  }
  store.show({ ...scope, category: null }, BANC_PIECES, scene === 'pieces-proactif' ? 'PROACTIVE' : 'ASKED');
  if (scene === 'pieces-image') {
    store.dispatch({ type: 'select', ref: 4 });
  } else if (scene === 'pieces-texte') {
    store.dispatch({ type: 'select', ref: 1 });
  }
}
