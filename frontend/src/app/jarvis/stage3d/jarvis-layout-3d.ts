import { normalizeName } from '../scenes/jarvis-name-matcher';
import { currentClient } from '../scenes/jarvis-constellation-layout';
import { JarvisUniverse, NodeKind, clientKey } from '../scenes/jarvis-universe.models';

/**
 * F-236 / SF-236-04 — **la disposition 3D de l'univers** (cadrage §8.1). Fichier <b>pur</b>, <b>déterministe</b> :
 * mêmes entrées ⇒ mêmes positions (hachage stable des clés) — le PO apprend sa carte. Le cœur est à l'origine.
 *
 * <ul>
 *   <li>clients sur une orbite (PC : anneau incliné vu de haut ; téléphone : ellipse verticale qui occupe le
 *   portrait), répartis régulièrement dans l'ordre de leur clé, avec un léger décalage tiré du hachage ;</li>
 *   <li>sujets en anneau autour de leur client (téléphone : ceux du client courant, plus les sujets cités) ;</li>
 *   <li>chantiers autour de leur sujet (sujet courant ou cité) ; étapes en <b>chapelet</b> (≤ 12 perles) autour du
 *   chantier focalisé ; personnes (citées, attendues) près de leur client.</li>
 * </ul>
 */

export type Node3dKind = NodeKind | 'step';

export interface Node3d {
  key: string;
  kind: Node3dKind;
  label: string;
  clientKey: string;
  subjectId?: string;
  /** Le chantier d'une étape. */
  chantierKey?: string;
  x: number;
  y: number;
  z: number;
  /** Client : poste connecté. */
  connected?: boolean;
  /** Sujet : terminal vivant. */
  live?: boolean;
  /** Étape faite (perle pleine). */
  done?: boolean;
  /** Personne : attendue par un chantier affiché (L-attente). */
  awaited?: boolean;
  /** Chantier : personnes attendues. */
  waitingOn?: string[];
}

/** Un nœud touché (étiquette) : sujet ⇒ « passe sur X » ; tout autre nœud ⇒ regarder, sans naviguer (D9). */
export interface UniverseTouch {
  key: string;
  kind: Node3dKind;
  subjectId?: string;
}

export interface Layout3d {
  currentClientKey: string | null;
  nodes: Node3d[];
  /** Demi-étendues (pour cadrer la vue d'ensemble). */
  extent: { x: number; y: number; z: number };
}

export const EMPTY_LAYOUT: Layout3d = { currentClientKey: null, nodes: [], extent: { x: 0, y: 0, z: 0 } };

/** Rayon de l'orbite des clients. */
export const CLIENT_ORBIT = { wide: 6.4, narrow: 5 } as const;
export const SUBJECT_ORBIT = 1.5;
export const CHANTIER_ORBIT = 0.8;
export const STEP_ORBIT = 0.42;
export const MAX_STEPS = 12;

/** Hachage FNV-1a 32 bits ramené dans [0, 1). Stable d'une session à l'autre. */
export function stableHash(text: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0) / 4294967296;
}

export interface LayoutOptions {
  subjectId: string | null;
  /** Téléphone (portrait étroit). */
  narrow: boolean;
  /** Clés citées / montrées : elles sont posées même hors du client courant. */
  shown: ReadonlySet<string>;
  /** SF-236-10 — étapes faites pendant le tour, par chantier (la perle se remplit avant la relecture de l'univers). */
  stepsDone?: Readonly<Record<string, number>>;
}

export function layoutUniverse3d(universe: JarvisUniverse | null, options: LayoutOptions): Layout3d {
  const current = currentClient(universe, options.subjectId);
  if (!universe || !current) {
    return EMPTY_LAYOUT;
  }
  const currentKey = clientKey(current);
  const nodes: Node3d[] = [];
  const clients = [...universe.clients].sort((a, b) => clientKey(a).localeCompare(clientKey(b)));
  const radius = options.narrow ? CLIENT_ORBIT.narrow : CLIENT_ORBIT.wide;
  const n = clients.length;
  clients.forEach((client, index) => {
    const ck = clientKey(client);
    const jitter = (stableHash(`${ck}~a`) - 0.5) * (Math.PI / Math.max(n, 2)) * 0.5;
    const a = (index / n) * Math.PI * 2 + jitter + Math.PI / 2;
    const at = orbitPoint(a, radius, options.narrow, stableHash(`${ck}~i`));
    nodes.push({ key: ck, kind: 'client', label: client.name, clientKey: ck, connected: client.connected, ...at });

    const showSubjects = !options.narrow || ck === currentKey;
    const subjects = client.subjects.filter((s) => showSubjects || options.shown.has(s.id));
    const tilt = (stableHash(`${ck}~t`) - 0.5) * 1.2;
    subjects.forEach((subject, i) => {
      const sa = (i / Math.max(subjects.length, 1)) * Math.PI * 2 + stableHash(`${subject.id}~a`) * 0.3;
      const around = ringAround(at, SUBJECT_ORBIT * (ck === currentKey ? 1 : 0.7), sa, tilt);
      nodes.push({ key: subject.id, kind: 'subject', label: subject.name, clientKey: ck, subjectId: subject.id,
        live: subject.live, ...around });

      const isCurrentSubject = subject.id === options.subjectId;
      const chantiers = subject.chantiers.filter((c) => isCurrentSubject
        || options.shown.has(`${subject.id}#${c.number}`) || options.shown.has(subject.id));
      chantiers.forEach((chantier, j) => {
        const key = `${subject.id}#${chantier.number}`;
        const ca = (j / Math.max(chantiers.length, 1)) * Math.PI * 2 + 0.4;
        const cpos = ringAround(around, CHANTIER_ORBIT, ca, tilt + 0.6);
        nodes.push({ key, kind: 'chantier', label: `Chantier ${chantier.number} — ${chantier.title}`, clientKey: ck,
          subjectId: subject.id, waitingOn: chantier.waitingOn, ...cpos });
        const focused = (isCurrentSubject && chantier.focus) || options.shown.has(key);
        if (focused && chantier.total > 0) {
          const total = Math.min(chantier.total, MAX_STEPS);
          for (let k = 0; k < total; k++) {
            const pa = -Math.PI * 0.75 + (k / Math.max(total - 1, 1)) * Math.PI * 1.5;
            const spos = ringAround(cpos, STEP_ORBIT, pa, 0);
            nodes.push({ key: `${key}/${k + 1}`, kind: 'step', label: chantier.step && chantier.step.rank === k + 1
              ? chantier.step.title : '', clientKey: ck, subjectId: subject.id, chantierKey: key,
              done: k < chantier.done + (options.stepsDone?.[key] ?? 0), ...spos });
          }
        }
        for (const name of chantier.waitingOn) {
          const pkey = `${ck}:${normalizeName(name)}`;
          if (!nodes.some((p) => p.key === pkey) && (isCurrentSubject || options.shown.has(key))) {
            nodes.push(personNode(client.people.find((p) => normalizeName(p.name) === normalizeName(name))?.name
              ?? name, ck, at, nodes.filter((p) => p.kind === 'person' && p.clientKey === ck).length, true));
          }
        }
      });
    });
    for (const person of client.people) {
      const pkey = `${ck}:${normalizeName(person.name)}`;
      if (options.shown.has(pkey) && !nodes.some((p) => p.key === pkey)) {
        nodes.push(personNode(person.name, ck, at,
          nodes.filter((p) => p.kind === 'person' && p.clientKey === ck).length, false));
      }
    }
  });
  const extent = { x: 0, y: 0, z: 0 };
  for (const node of nodes) {
    extent.x = Math.max(extent.x, Math.abs(node.x));
    extent.y = Math.max(extent.y, Math.abs(node.y));
    extent.z = Math.max(extent.z, Math.abs(node.z));
  }
  return { currentClientKey: currentKey, nodes, extent };
}

/** Un point de l'orbite des clients. PC : anneau légèrement incliné ; téléphone : ellipse dressée face à l'écran. */
function orbitPoint(a: number, radius: number, narrow: boolean, h: number): { x: number; y: number; z: number } {
  if (narrow) {
    return { x: round(Math.cos(a) * radius * 0.62), y: round(Math.sin(a) * radius), z: round((h - 0.5) * 1.6) };
  }
  const incl = (h - 0.5) * 0.5;
  return {
    x: round(Math.cos(a) * radius),
    y: round(Math.sin(a) * radius * Math.sin(incl) + (h - 0.5) * 1.2),
    z: round(Math.sin(a) * radius * Math.cos(incl)),
  };
}

/** Un point sur un petit anneau incliné autour de `c`. */
function ringAround(c: { x: number; y: number; z: number }, r: number, a: number, tilt: number):
    { x: number; y: number; z: number } {
  return {
    x: round(c.x + Math.cos(a) * r),
    y: round(c.y + Math.sin(a) * r * Math.cos(tilt)),
    z: round(c.z + Math.sin(a) * r * Math.sin(tilt)),
  };
}

function personNode(name: string, ck: string, client: { x: number; y: number; z: number }, index: number,
    awaited: boolean): Node3d {
  const a = -Math.PI / 2 + (index - 1) * 0.5;
  const at = ringAround(client, 1.1, a, 0.3);
  return { key: `${ck}:${normalizeName(name)}`, kind: 'person', label: name, clientKey: ck, awaited, ...at };
}

function round(v: number): number {
  return Math.round(v * 1000) / 1000;
}
