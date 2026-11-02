import { BrainEnd, BrainsState, brainsFor } from './jarvis-brains';
import { JarvisUniverse } from '../scenes/jarvis-universe.models';
import { HeartMood, JarvisVoiceState, heartMood } from './jarvis-heart';
import { EMPTY_LAYOUT, Layout3d, Node3d, layoutUniverse3d } from './jarvis-layout-3d';
import { Graph3d, MAX_LIT_NODES, linkGraph } from './jarvis-link-graph';
import { SceneView } from '../scenes/jarvis-scene-reader';
import { directScene } from './scenes3d';
import { boundPanels } from './jarvis-panels';
import { EMPTY_SESSION, SessionSnapshot } from './scenes3d/jarvis-session-memory';
import { ScenePlan3d } from './scenes3d/jarvis-scene3d.model';
import { ShowFocus } from '../scenes/jarvis-show';
import { ShowPlan3d } from './scenes3d/jarvis-show-3d';

/**
 * F-236 — **le directeur de scène** (cadrage §3). Fichier <b>pur</b> : il lit les signaux existants de l'écran et
 * du bus F-234 (jamais modifiés) et produit un <b>plan déclaratif</b> ; le moteur 3D ne fait que dessiner et
 * interpoler ce plan. Chaque SF y ajoute sa part (SF-236-02 : le cœur).
 */

/** Ce que l'écran sait (lu, jamais écrit). */
export interface StageSignals {
  /** H6 — l'état de l'écran. */
  state: JarvisVoiceState;
  /** H2 — `ReaderState.frozen` : la voix a été coupée. */
  frozen: boolean;
  /** H2 — une autorisation S2 attend sa réponse (`scene.id === 'S2' && pinned`). */
  waiting: boolean;
  /** N3 — Jarvis est en veille. */
  asleep: boolean;
  /** N2 — réveils joués (onde de choc à chaque incrément). */
  wakes: number;
  /** H8 — SF-236-03 : délégations vivantes, cerveau rapide, tour en cours, fin du dernier travail. */
  delegations: number;
  fastBrainOn: boolean;
  turnActive: boolean;
  brainEnd: BrainEnd;
}

/** Ce que l'écran `/jarvis` passe à la scène 3D (la veille et les réveils sont tenus par la scène). */
export interface ScreenSignals {
  state: JarvisVoiceState;
  frozen: boolean;
  waiting: boolean;
  /** H9 — la conversation mains libres est active. */
  conversing: boolean;
  /** H10 — appels du mot « Jarvis » (ou toucher de réveil) depuis l'ouverture. */
  wakeCalls: number;
  /** H8 / H5 — SF-236-03. */
  delegations: number;
  fastBrainOn: boolean;
  turnActive: boolean;
  brainEnd: BrainEnd;
  /** H1 / H5 — SF-236-04 : l'univers, le sujet courant (D19 : `null` ⇒ « Jarvis global »). */
  universe: JarvisUniverse | null;
  subjectId: string | null;
  /** H2 — clés des nœuds cités (par priorité) et nommés dans la phrase dite. */
  lit: readonly string[];
  said: readonly string[];
  /** H3 — « Montrer » : clés des éléments montrés, `null` sinon. */
  shown: readonly string[] | null;
  /** H2 — SF-236-08 : la scène F-234 en cours (une à la fois) et le compteur d'appels d'outil du tour. */
  scene: SceneView | null;
  pulses: number;
  /** SF-236-09 — les clés de tickets nommées dans la phrase dite par Jarvis (la carte s'illumine). */
  spokenTickets: readonly string[];
  /** H2 — SF-236-10 : étapes faites pendant le tour, par chantier (`ReaderState.stepsDone`). */
  stepsDone: Readonly<Record<string, number>>;
  /** H3 — SF-236-11 : « Montrer » résolu sur l'univers (`resolveShow`), `null` sinon. */
  showFocus: ShowFocus | null;
}

export const IDLE_SCREEN: ScreenSignals = { state: 'repos', frozen: false, waiting: false, conversing: false,
  wakeCalls: 0, delegations: 0, fastBrainOn: false, turnActive: false, brainEnd: 'normal', universe: null,
  subjectId: null, lit: [], said: [], shown: null, scene: null, pulses: 0, spokenTickets: [],
  stepsDone: {}, showFocus: null };

/** Deux relevés de l'écran identiques (évite de recalculer le plan pour rien). */
export function sameScreen(a: ScreenSignals, b: ScreenSignals): boolean {
  return a.state === b.state && a.frozen === b.frozen && a.waiting === b.waiting && a.conversing === b.conversing
    && a.wakeCalls === b.wakeCalls && a.delegations === b.delegations && a.fastBrainOn === b.fastBrainOn
    && a.turnActive === b.turnActive && a.brainEnd === b.brainEnd && a.universe === b.universe
    && a.subjectId === b.subjectId && sameKeys(a.lit, b.lit) && sameKeys(a.said, b.said)
    && (a.shown === b.shown || (!!a.shown && !!b.shown && sameKeys(a.shown, b.shown)))
    && a.scene === b.scene && a.pulses === b.pulses && sameKeys(a.spokenTickets, b.spokenTickets)
    && a.stepsDone === b.stepsDone && a.showFocus === b.showFocus;
}

function sameKeys(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((k, i) => k === b[i]);
}

/** SF-236-04 — l'univers à dessiner : disposition, graphe des liaisons, gel de la dérive. */
export interface UniverseView {
  layout: Layout3d;
  graph: Graph3d;
  /** La dérive lente s'arrête pendant l'écoute et sur un focus (§8.1). */
  freeze: boolean;
  /** SF-236-05 — le nœud dont on parle (le premier cité, posé), cadré par la caméra. */
  focus: Node3d | null;
  /** Le sujet courant (un changement de sujet ⇒ dézoom). */
  subjectId: string | null;
}

export const EMPTY_VIEW: UniverseView = { layout: EMPTY_LAYOUT, graph: { links: [], nodes: new Map() }, freeze: false,
  focus: null, subjectId: null };

/** H1-H5 ⇒ l'univers à dessiner. `narrow` = téléphone. */
export function universeView(s: ScreenSignals, asleep: boolean, narrow: boolean): UniverseView {
  if (!s.universe) {
    return EMPTY_VIEW;
  }
  // SF-236-08 — l'entité de la scène en cours est allumée elle aussi (S-GEN : la cible reconnue, D2 : ≤ 3).
  const cited = [...s.said, ...s.lit.filter((k) => !s.said.includes(k))];
  // SF-236-10 — le chantier d'une scène S9 / S11 prime sur l'entité : il est posé, allumé, son chapelet déployé.
  const entity = s.scene?.data?.chantierKey ?? s.scene?.entity?.key;
  const lit = entity && !cited.includes(entity) && cited.length < MAX_LIT_NODES ? [...cited, entity] : cited;
  const layout = layoutUniverse3d(s.universe, { subjectId: s.subjectId, narrow,
    shown: new Set([...lit, ...(s.shown ?? [])]), stepsDone: s.stepsDone });
  const graph = linkGraph(layout, { subjectId: s.subjectId, lit, shown: s.shown, asleep });
  const focus = s.shown === null ? layout.nodes.find((n) => n.key === lit.find((k) => graph.nodes.get(k) === 'lit'))
    ?? null : null;
  return { layout, graph, freeze: s.state === 'ecoute' || lit.length > 0 || s.shown !== null, focus,
    subjectId: s.subjectId };
}

/** Le plan que le moteur dessine. */
export interface StagePlan {
  mood: HeartMood;
  wakes: number;
  /** SF-236-03 — les deux cerveaux. */
  brains: BrainsState;
  /** Banc seulement : les cerveaux partent déployés (séquence du retour). */
  brainsFromDeployed?: boolean;
  /** Banc seulement : la caméra rejoue un zoom (T-Z1) ou un dézoom (T-Z2) depuis t = 0. */
  bancCamera?: 'zoom' | 'dezoom';
  /** SF-236-04 — l'univers et ses liaisons. */
  universe: UniverseView;
  /** SF-236-08 — la scène 3D en cours (panneaux holographiques et effets), `null` sans scène. */
  scene: ScenePlan3d | null;
  /** Banc seulement : les panneaux de la scène sortent depuis t = 0 (séquence T-P2). */
  bancPanelsOut?: boolean;
  /** SF-236-11 — « Montrer » en 3D (S20, T-M), `null` sinon. */
  show?: ShowPlan3d | null;
}

/** SF-236-08 — la scène F-234 en cours ⇒ son plan 3D, ancré sur l'univers posé. */
export function stageScene(s: ScreenSignals, view: UniverseView, narrow: boolean,
    session: SessionSnapshot = EMPTY_SESSION): ScenePlan3d | null {
  const plan = directScene(s.scene, { universe: s.universe, subjectId: s.subjectId, pulses: s.pulses, narrow,
    nodes: new Set(view.layout.nodes.map((n) => n.key)), session, spokenTickets: s.spokenTickets,
    stepsDone: s.stepsDone });
  // T6 — pool borné (PC 8, téléphone 2) ; « devant tout » (S2) d'abord, jamais écarté.
  return plan ? { ...plan, panels: boundPanels(plan.panels, narrow) } : null;
}

export const IDLE_SIGNALS: StageSignals = { state: 'repos', frozen: false, waiting: false, asleep: false, wakes: 0,
  delegations: 0, fastBrainOn: false, turnActive: false, brainEnd: 'normal' };

export function directStage(signals: StageSignals, universe: UniverseView = EMPTY_VIEW,
    scene: ScenePlan3d | null = null): StagePlan {
  return {
    universe,
    scene,
    mood: heartMood(signals),
    wakes: signals.wakes,
    brains: brainsFor({ delegations: signals.delegations, fastBrainOn: signals.fastBrainOn,
      turnActive: signals.turnActive, end: signals.brainEnd }),
  };
}

/** Deux plans identiques ⇒ le moteur n'a rien à faire. */
export function samePlan(a: StagePlan, b: StagePlan): boolean {
  return a.mood === b.mood && a.wakes === b.wakes && a.brains.target === b.brains.target
    && a.brains.variant === b.brains.variant && a.brains.pearls === b.brains.pearls && a.universe === b.universe
    && sameScenePlan(a.scene, b.scene) && (a.show ?? null) === (b.show ?? null);
}

/** Deux plans de scène identiques (petits objets déclaratifs : comparaison de leur forme écrite). */
export function sameScenePlan(a: ScenePlan3d | null, b: ScenePlan3d | null): boolean {
  return a === b || (!!a && !!b && JSON.stringify(a) === JSON.stringify(b));
}
