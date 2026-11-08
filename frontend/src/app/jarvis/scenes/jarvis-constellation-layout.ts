import { JarvisUniverse, UniverseClient, UniverseSubject, clientKey } from './jarvis-universe.models';

/**
 * **La disposition de la constellation** (F-234 / SF-234-03, D6, D7) — **radiale et déterministe** : calculée une fois
 * par univers, sans simulation de forces ; le même univers donne toujours les mêmes positions (le PO apprend sa
 * carte). Coordonnées dans un repère `-100…100`, l'orbe au centre.
 *
 * - Les **clients** sur la couronne extérieure (le client courant en haut).
 * - Les **sujets du client courant** en anneau autour de l'orbe.
 * - Desktop : les sujets des autres clients en grappe discrète autour de leur client ; téléphone (< 768 px) : les
 *   autres clients ne sont que des points nommés en lisière (D7).
 * - Chantiers et personnes n'apparaissent que cités (le composant les place à partir de leur sujet / client).
 */

/** Le rayon de la couronne des clients. */
export const CLIENT_RING = 88;
/** Le rayon de l'anneau des sujets du client courant. */
export const SUBJECT_RING = 62;
/** Le rayon où s'inscrit un chantier cité (entre son sujet et la couronne). */
export const CHANTIER_RING = 76;
/** Le rayon d'une grappe de sujets autour d'un autre client (desktop). */
const CLUSTER = 9;
/** Au-delà, les noms des sujets ne sont écrits que s'ils sont allumés. */
export const MAX_NAMED_SUBJECTS = 10;

export interface PlacedClient {
  key: string;
  name: string;
  x: number;
  y: number;
  angle: number;
  current: boolean;
  connected: boolean;
}

export interface PlacedSubject {
  id: string;
  name: string;
  clientKey: string;
  x: number;
  y: number;
  angle: number;
  /** Sujet du client courant (anneau) ou d'un autre client (grappe, desktop seulement). */
  ring: 'current' | 'cluster';
  hostTerminal: boolean;
  live: boolean;
  /** Le nom est écrit au repos. */
  named: boolean;
}

export interface ConstellationLayout {
  currentClientKey: string | null;
  clients: PlacedClient[];
  subjects: PlacedSubject[];
}

const EMPTY: ConstellationLayout = { currentClientKey: null, clients: [], subjects: [] };

/** Le client qui porte `subjectId`, sinon le premier. */
export function currentClient(universe: JarvisUniverse | null, subjectId: string | null): UniverseClient | null {
  if (!universe || universe.clients.length === 0) {
    return null;
  }
  return universe.clients.find((c) => c.subjects.some((s) => s.id === subjectId)) ?? universe.clients[0];
}

/** Place l'univers. `narrow` = téléphone (< 768 px). */
export function layoutUniverse(universe: JarvisUniverse | null, subjectId: string | null, narrow: boolean):
    ConstellationLayout {
  const current = currentClient(universe, subjectId);
  if (!universe || !current) {
    return EMPTY;
  }
  const currentKey = clientKey(current);
  // Le client courant en haut, les autres dans l'ordre de l'univers, également répartis.
  const ordered = [current, ...universe.clients.filter((c) => c !== current)];
  const clients: PlacedClient[] = ordered.map((c, i) => {
    const angle = -90 + (360 * i) / ordered.length;
    return {
      key: clientKey(c), name: c.name, angle, current: c === current, connected: c.connected,
      ...polar(CLIENT_RING, angle),
    };
  });
  const subjects: PlacedSubject[] = [];
  const ring = current.subjects;
  ring.forEach((s, i) => {
    const angle = -90 + (360 * (i + 0.5)) / ring.length;
    subjects.push(place(s, currentKey, angle, polar(SUBJECT_RING, angle), 'current', ring.length <= MAX_NAMED_SUBJECTS));
  });
  if (!narrow) {
    for (const placed of clients.filter((c) => !c.current)) {
      const client = ordered.find((c) => clientKey(c) === placed.key)!;
      client.subjects.forEach((s, i) => {
        const angle = (360 * i) / Math.max(1, client.subjects.length);
        const offset = polar(CLUSTER, angle);
        subjects.push(place(s, placed.key, placed.angle, { x: placed.x + offset.x, y: placed.y + offset.y },
          'cluster', false));
      });
    }
  }
  return { currentClientKey: currentKey, clients, subjects };
}

/** Coordonnées d'un point à `radius` sous `angleDeg` (0° = droite, −90° = haut). */
export function polar(radius: number, angleDeg: number): { x: number; y: number } {
  const a = (angleDeg * Math.PI) / 180;
  return { x: round(radius * Math.cos(a)), y: round(radius * Math.sin(a)) };
}

/** L'ancrage d'un nom selon sa position : à droite du centre, il part vers la droite. */
export function anchorFor(x: number): 'start' | 'middle' | 'end' {
  return x > 12 ? 'start' : x < -12 ? 'end' : 'middle';
}

function place(s: UniverseSubject, ck: string, angle: number, at: { x: number; y: number }, ring: 'current' | 'cluster',
    named: boolean): PlacedSubject {
  return {
    id: s.id, name: s.name, clientKey: ck, x: at.x, y: at.y, angle, ring, hostTerminal: s.hostTerminal, live: s.live,
    named,
  };
}

function round(v: number): number {
  return Math.round(v * 100) / 100;
}

/** F-253 / SF-253-07 — la demi-largeur où une étiquette reste lisible au téléphone (la carte y prend 94 % de l'écran). */
export const LABEL_LIMIT = 98;
/** Largeur moyenne d'un caractère, en fraction de la taille de police (police de texte du design system). */
const CHAR_WIDTH = 0.6;

export interface LabelPlace {
  x: number;
  y: number;
  anchor: 'start' | 'middle' | 'end';
}

/**
 * F-253 / SF-253-07 (audit §4) — garde une étiquette dans l'écran : au téléphone, un nom qui déborderait du repère
 * (« data-platform » à droite) passe sous son point, centré, et se décale vers l'intérieur s'il le faut. Le placement
 * habituel est rendu tel quel quand il tient.
 */
export function fitLabel(point: { x: number; y: number }, place: LabelPlace, label: string, fontSize: number,
    limit = LABEL_LIMIT): LabelPlace {
  const width = label.length * fontSize * CHAR_WIDTH;
  const left = place.anchor === 'start' ? place.x : place.anchor === 'end' ? place.x - width : place.x - width / 2;
  if (left >= -limit && left + width <= limit) {
    return place;
  }
  const half = Math.min(width / 2, limit);
  const x = Math.max(-limit + half, Math.min(limit - half, point.x));
  const y = place.anchor === 'middle' ? place.y : point.y + (point.y < 0 ? -fontSize : fontSize + 1.5);
  return { x: round(x), y: round(y), anchor: 'middle' };
}
