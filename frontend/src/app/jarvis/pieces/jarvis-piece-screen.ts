import { NavItem, PieceNav } from './jarvis-piece-nav';
import { PieceKind } from './jarvis-piece.models';

/**
 * F-237 / SF-237-04 — le bloc **`## Écran`** (cadrage §5.2) : ce que l'écran montre, joint au **dernier message**
 * envoyé au cerveau rapide (jamais à la consigne : préfixe de cache stable). C'est ce qui permet à l'IA de comprendre
 * « la deuxième ». Plafond 1 500 caractères, titres coupés à 60.
 */

export const SCREEN_MAX_CHARS = 1500;
export const SCREEN_TITLE_CHARS = 60;

const KIND_LABELS: Record<PieceKind, string> = {
  IMAGE: 'image', VIDEO: 'vidéo', AUDIO: 'audio', PDF: 'PDF', PAGE: 'page', SLIDES: 'diaporama',
  ARCHITECTURE: 'architecture', DIAGRAM: 'schéma', TEXT: 'texte', FILE: 'fichier',
};

const WHEN = new Intl.DateTimeFormat('fr-FR', {
  weekday: 'long', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Paris',
});

function clip(text: string, max: number): string {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length <= max ? t : `${t.slice(0, max - 1).trimEnd()}…`;
}

function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function when(at: string | null | undefined): string | null {
  if (!at) {
    return null;
  }
  const date = new Date(at);
  return Number.isNaN(date.getTime()) ? null : WHEN.format(date);
}

function line(item: NavItem, nav: PieceNav): string {
  const parts = [`${item.index}. ${KIND_LABELS[item.kind] ?? 'fichier'} — « ${clip(item.title, SCREEN_TITLE_CHARS)} »`];
  const date = when(item.at);
  if (date) {
    parts.push(date);
  }
  if (item.pages) {
    parts.push(`${item.pages} pages`);
  }
  if (nav.selected === item.index) {
    if (nav.time != null) {
      const total = item.durationS != null ? ` / ${clock(item.durationS)}` : '';
      parts.push(`${nav.playing ? 'en lecture' : 'en pause'} ${clock(nav.time)}${total}`);
      if (nav.chapter != null && item.chapters?.[nav.chapter - 1]) {
        parts.push(`chapitre ${nav.chapter} « ${clip(item.chapters[nav.chapter - 1].title, 40)} »`);
      }
    }
    if (nav.page != null) {
      parts.push(`page ${nav.page}`);
    }
    if (nav.zoom > 1) {
      parts.push(`zoom ×${nav.zoom}${nav.target ? ` sur « ${clip(nav.target, 40)} »` : ''}`);
    }
  }
  return parts.join(' — ');
}

/** Le bloc `## Écran` — « Écran : univers » sans galerie ; jamais plus de 1 500 caractères. */
export function screenBlock(nav: PieceNav, presentation = false): string {
  if (presentation) {
    return '## Écran\nÉcran : présentation (aucune pièce affichée)';
  }
  if (nav.level === 'UNIVERS' || !nav.scope) {
    return '## Écran\nÉcran : univers';
  }
  if (nav.level === 'CHANTIER') {
    return `## Écran\nÉcran : chantier — ${clip(nav.scope.label, 80)}`;
  }
  const count = nav.items.length + nav.more;
  const head = `## Écran\nGalerie : ${clip(nav.scope.label, 80)} (${count} pièce${count > 1 ? 's' : ''})`
    + ` ; sélection : ${nav.selected ?? 'aucune'}`;
  let out = head;
  for (const item of nav.items) {
    const next = `${out}\n${line(item, nav)}`;
    if (next.length > SCREEN_MAX_CHARS - 2) {
      return `${out}\n…`;
    }
    out = next;
  }
  if (nav.more > 0) {
    const tail = `${out}\n+${nav.more} autres`;
    return tail.length <= SCREEN_MAX_CHARS ? tail : out;
  }
  return out;
}
