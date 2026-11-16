import { normalize } from '../jarvis-oral';

/**
 * F-238 / SF-238-10 — **le sous-état `arch` de la navigation** (cadrage §7, §12) : ce que montre une pièce
 * `ARCHITECTURE` ouverte (focus, zones repliées, éclaté, vue, flux allumé, orbite, repli 2D choisi). Fichier
 * <b>pur</b> : le toucher, la grammaire vocale locale et l'outil `afficher` du cerveau rapide envoient les <b>mêmes</b>
 * demandes ; la visionneuse 3D les résout contre le graphe (libellé, id, type, famille), le réducteur les applique.
 */

export type ArchViewName = 'ensemble' | 'dessus' | 'face';

/** Un élément nommé du schéma : son id dans le graphe et son libellé (dit et écrit dans `## Écran`). */
export interface ArchRef {
  id: string;
  label: string;
}

export interface ArchNav {
  focus: ArchRef | null;
  /** Zones repliées (capsules), triées par id : replier puis déplier rend l'état identique. */
  folded: ArchRef[];
  exploded: boolean;
  view: ArchViewName;
  /** Le flux allumé par un ordre (« montre le flux sortant ») ; la narration allume les siens à part. */
  flow: { edges: string[]; label: string } | null;
  /** Décalage d'orbite (radians) posé au toucher ou par « tourne ». */
  orbit: { az: number; el: number };
  /** Le PO a demandé le 2D (« en 2D ») : la visionneuse SVG cadrée prend la place. */
  flat: boolean;
}

/** Une demande, avant résolution contre le graphe (`target` : texte dit, libellé ou id ; vide = tout / rien). */
export type ArchRequest =
  | { kind: 'focus'; target: string }
  | { kind: 'flow'; target: string }
  | { kind: 'fold'; target: string }
  | { kind: 'unfold'; target: string }
  | { kind: 'explode'; on: boolean }
  | { kind: 'view'; view: ArchViewName | '2d' | '3d' }
  | { kind: 'turn' }
  | { kind: 'orbit'; dAz: number; dEl: number }
  /** SF-238-11 (retour PO) — recentrer : vue d'ensemble, sans focus, orbite et zoom remis à zéro. */
  | { kind: 'recenter' };

/** Une demande résolue : ce que le réducteur applique. */
export type ArchOp =
  | { kind: 'focus'; ref: ArchRef | null }
  | { kind: 'flow'; edges: string[]; label: string } | { kind: 'flowOff' }
  | { kind: 'fold'; refs: ArchRef[] }
  | { kind: 'unfold'; refs: ArchRef[] | 'all' }
  | { kind: 'explode'; on: boolean }
  | { kind: 'view'; view: ArchViewName }
  | { kind: 'flat'; on: boolean }
  | { kind: 'orbit'; dAz: number; dEl: number }
  | { kind: 'recenter' };

/** « tourne » : un huitième de tour. */
export const TURN_STEP = Math.PI / 4;
/** Orbite bornée (cadrage §12) : élévation absolue de 10° à 90° ; décalage d'élévation borné en conséquence. */
export const ORBIT_EL_MIN = (-22 * Math.PI) / 180;
export const ORBIT_EL_MAX = (58 * Math.PI) / 180;

export function defaultArchNav(): ArchNav {
  return { focus: null, folded: [], exploded: false, view: 'ensemble', flow: null, orbit: { az: 0, el: 0 },
    flat: false };
}

/** Les demandes qui n'ont pas besoin du graphe (éclater, vue, tourner, tout déplier, effacer le focus). */
export function trivialArchOp(request: ArchRequest): ArchOp | null {
  switch (request.kind) {
    case 'explode':
      return { kind: 'explode', on: request.on };
    case 'view':
      return request.view === '2d' || request.view === '3d' ? { kind: 'flat', on: request.view === '2d' }
        : { kind: 'view', view: request.view };
    case 'turn':
      return { kind: 'orbit', dAz: TURN_STEP, dEl: 0 };
    case 'orbit':
      return { kind: 'orbit', dAz: request.dAz, dEl: request.dEl };
    case 'recenter':
      return { kind: 'recenter' };
    case 'unfold':
      return request.target.trim() === '' ? { kind: 'unfold', refs: 'all' } : null;
    case 'focus':
      return request.target.trim() === '' ? { kind: 'focus', ref: null } : null;
    default:
      return null;
  }
}

function sortRefs(refs: ArchRef[]): ArchRef[] {
  return [...refs].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

function wrapAngle(a: number): number {
  const full = Math.PI * 2;
  return ((((a + Math.PI) % full) + full) % full) - Math.PI;
}

/** Le réducteur du sous-état — pur : même état et même opération ⇒ même résultat. */
export function reduceArch(state: ArchNav, op: ArchOp): ArchNav {
  switch (op.kind) {
    case 'focus':
      return { ...state, focus: op.ref };
    case 'flow':
      return { ...state, flow: { edges: [...op.edges], label: op.label } };
    case 'flowOff':
      return { ...state, flow: null };
    case 'fold': {
      const known = new Set(state.folded.map((r) => r.id));
      const added = op.refs.filter((r) => !known.has(r.id));
      return added.length ? { ...state, folded: sortRefs([...state.folded, ...added]) } : state;
    }
    case 'unfold': {
      if (op.refs === 'all') {
        return state.folded.length ? { ...state, folded: [] } : state;
      }
      const drop = new Set(op.refs.map((r) => r.id));
      const folded = state.folded.filter((r) => !drop.has(r.id));
      return folded.length !== state.folded.length ? { ...state, folded } : state;
    }
    case 'explode':
      return state.exploded === op.on ? state : { ...state, exploded: op.on };
    case 'view':
      // Une vue nommée remet l'orbite à zéro (la vue de dessus est le 2D cadré exactement).
      return { ...state, view: op.view, orbit: { az: 0, el: 0 } };
    case 'flat':
      return state.flat === op.on ? state : { ...state, flat: op.on };
    case 'recenter':
      return { ...state, focus: null, view: 'ensemble', orbit: { az: 0, el: 0 } };
    case 'orbit': {
      const el = Math.min(ORBIT_EL_MAX, Math.max(ORBIT_EL_MIN, state.orbit.el + op.dEl));
      return { ...state, orbit: { az: wrapAngle(state.orbit.az + op.dAz), el } };
    }
  }
}

function clip(text: string, max: number): string {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length <= max ? t : `${t.slice(0, max - 1).trimEnd()}…`;
}

/**
 * La ligne d'état du bloc `## Écran` (cadrage §7) — dans le <b>message</b>, jamais dans la consigne système :
 * « Architecture « 3-tiers » ; focus : RDS primaire ; repliés : Zone B ; éclaté : non ; vue : ensemble ; flux : … ».
 */
export function archScreenLine(title: string, arch: ArchNav): string {
  const folded = arch.folded.length ? arch.folded.slice(0, 6).map((r) => clip(r.label, 30)).join(', ')
    + (arch.folded.length > 6 ? ` +${arch.folded.length - 6}` : '') : 'aucune';
  return `Architecture « ${clip(title, 60)} » ; focus : ${arch.focus ? clip(arch.focus.label, 40) : 'aucun'}`
    + ` ; repliées : ${folded} ; éclaté : ${arch.exploded ? 'oui' : 'non'} ; vue : ${arch.flat ? '2D' : arch.view}`
    + ` ; flux : ${arch.flow ? clip(arch.flow.label, 60) : 'aucun'}`;
}

// ------------------------------------------------------------------------------------------- grammaire locale

const EXPLODE = /^(eclate|eclate le schema|eclate les plateaux|ecarte les plateaux)$/;
const GATHER = /^(rassemble|rassemble le schema|rassemble les plateaux)$/;
const TOP = /^(vue de dessus|de dessus|vu de dessus)$/;
const FRONT = /^(vue de face|de face)$/;
const ENSEMBLE = /^(vue d ensemble|vue generale)$/;
const FLAT = /^(en 2d|en deux d|passe en 2d|passe en deux d|vue 2d)$/;
const DEEP = /^(en 3d|en trois d|passe en 3d|passe en trois d|vue 3d)$/;
const FOLD_ALL = /^(replie tout|replie tous les groupes|replie toutes les zones)$/;
const UNFOLD_ALL = /^(deplie tout|deplie tous les groupes|deplie toutes les zones)$/;
const TURN = /^(tourne|tourne le schema|fais le tourner)$/;
const RECENTER = /^(recentre|recentre le schema|recadre|recadre le schema|centre le schema|remets le schema au centre)$/;

/**
 * Les ordres exacts d'architecture (cadrage §7, §12), reconnus sur l'appareil sans IA quand une pièce `ARCHITECTURE`
 * est ouverte : phrase entière, liste fermée. Tout ce qui nomme un service ou une zone part au cerveau rapide.
 */
export function archVoiceRequest(said: string | null | undefined): ArchRequest | null {
  const words = normalize(said ?? '').replace(/^jarvis\s+/, '').trim();
  if (!words) {
    return null;
  }
  if (EXPLODE.test(words)) {
    return { kind: 'explode', on: true };
  }
  if (GATHER.test(words)) {
    return { kind: 'explode', on: false };
  }
  if (TOP.test(words)) {
    return { kind: 'view', view: 'dessus' };
  }
  if (FRONT.test(words)) {
    return { kind: 'view', view: 'face' };
  }
  if (ENSEMBLE.test(words)) {
    return { kind: 'view', view: 'ensemble' };
  }
  if (FLAT.test(words)) {
    return { kind: 'view', view: '2d' };
  }
  if (DEEP.test(words)) {
    return { kind: 'view', view: '3d' };
  }
  if (FOLD_ALL.test(words)) {
    return { kind: 'fold', target: '' };
  }
  if (UNFOLD_ALL.test(words)) {
    return { kind: 'unfold', target: '' };
  }
  if (TURN.test(words)) {
    return { kind: 'turn' };
  }
  if (RECENTER.test(words)) {
    return { kind: 'recenter' };
  }
  return null;
}
