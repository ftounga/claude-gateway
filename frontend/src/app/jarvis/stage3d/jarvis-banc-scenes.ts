import { testUniverse } from '../scenes/jarvis-universe.fixture';
import { BrainsState, FOLDED } from './jarvis-brains';
import { HeartMood } from './jarvis-heart';
import { EMPTY_VIEW, IDLE_SCREEN, ScreenSignals, StagePlan, UniverseView, stageScene, universeView,
} from './jarvis-stage-director';
import { BANC_AMBIENTS, BANC_SCENE_VIEWS, BANC_SESSION_FILES, sceneOf } from './scenes3d/jarvis-scene3d.fixture';
import { SessionMemory } from './scenes3d/jarvis-session-memory';
import { ShowFilter, resolveShow } from '../scenes/jarvis-show';
import { directShow } from './scenes3d/jarvis-show-3d';
import { directAmbient } from './scenes3d/jarvis-ambiance-3d';
import { baseName } from '../scenes/jarvis-scene-reader';
import { ScenePlan3d } from './scenes3d/jarvis-scene3d.model';

/**
 * F-236 — **les scènes du banc** (développement seulement : importé par la seule page du banc, chargée
 * paresseusement et jamais appariée en production). Chaque scène pose un plan sur l'univers de test F-234.
 */

/** SF-236-02 — l'humeur du cœur posée par chaque scène du banc (transition rejouée depuis t = 0). */
const BANC_MOODS: Readonly<Record<string, HeartMood>> = {
  coeur: 'repos', ecoute: 'ecoute', reflexion: 'reflexion', parole: 'parole', oui: 'oui', coupure: 'coupure',
  veille: 'veille', reveil: 'repos', 'cerveau-aller': 'reflexion', cerveau: 'reflexion', 'cerveau-retour': 'repos',
  'cerveau-annule': 'repos', univers: 'repos', 'univers-cite': 'parole', 'univers-montrer': 'repos',
  'univers-telephone': 'repos',
};

/** SF-236-03 — les cerveaux posés par chaque scène du banc. */
function bancBrains(scene: string): { brains: BrainsState; fromDeployed: boolean } {
  switch (scene) {
    case 'cerveau-aller':
    case 'cerveau':
      return { brains: { target: 'deployed', variant: 'normal', pearls: 2 }, fromDeployed: false };
    case 'cerveau-retour':
      return { brains: { target: 'folded', variant: 'normal', pearls: 0 }, fromDeployed: true };
    case 'cerveau-annule':
      return { brains: { target: 'folded', variant: 'cancel', pearls: 0 }, fromDeployed: true };
    default:
      return { brains: FOLDED, fromDeployed: false };
  }
}

/** Le plan que le banc pose, et le niveau de voix simulé (ondes ∝ niveau). */
export function bancPlan(scene: string, narrow: boolean): { plan: StagePlan; voice: number } {
  if (scene.startsWith('ambiance-')) {
    return { plan: bancAmbient(scene.slice('ambiance-'.length), narrow), voice: 0 };
  }
  if (scene.startsWith('montrer-')) {
    return { plan: bancShow(scene.slice('montrer-'.length) as ShowFilter, narrow), voice: 0 };
  }
  const staged = bancScene(scene, narrow);
  if (staged) {
    return { plan: { mood: staged.mood, wakes: 0, brains: FOLDED, universe: staged.view, scene: staged.plan,
      bancPanelsOut: scene === 'panneau-sortie' }, voice: 0 };
  }
  const mood = Object.hasOwn(BANC_MOODS, scene) ? BANC_MOODS[scene] : 'repos';
  return {
    plan: { mood, wakes: scene === 'reveil' ? 1 : 0, brains: bancBrains(scene).brains,
      brainsFromDeployed: bancBrains(scene).fromDeployed, universe: bancUniverse(scene, narrow),
      bancCamera: scene === 'zoom' ? 'zoom' : scene === 'dezoom' ? 'dezoom' : undefined, scene: null },
    voice: mood === 'ecoute' || mood === 'parole' ? 0.7 : 0,
  };
}

/** SF-236-15 — ce que l'écran passe au cockpit au banc (univers de test, un tour qui travaille). */
export function bancScreen(scene: string): ScreenSignals {
  if (!scene.startsWith('cockpit')) {
    return IDLE_SCREEN;
  }
  return { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge', state: 'reflexion', turnActive: true,
    pulses: 3, delegations: 1, scene: BANC_SCENE_VIEWS['commande'] ?? null };
}

/** SF-236-13 — l'ambiance au banc (au repos, administrateur pour A4). */
function bancAmbient(name: string, narrow: boolean): StagePlan {
  const ambient = BANC_AMBIENTS[name] ?? null;
  const signals: ScreenSignals = { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge', ambient,
    admin: true };
  const view = universeView(signals, false, narrow);
  return { mood: 'repos', wakes: 0, brains: FOLDED, universe: view, scene: null,
    ambient: directAmbient(ambient, { universe: signals.universe, nodes: new Set(view.layout.nodes.map((n) => n.key)),
      busy: false, admin: true, costFresh: true, seq: 1 }) };
}

/** SF-236-11 — « Montrer » au banc : la demande résolue sur l'univers de test, comme à l'écran. */
function bancShow(filter: ShowFilter, narrow: boolean): StagePlan {
  const focus = resolveShow(testUniverse(), { filter, client: null });
  const signals: ScreenSignals = { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge', showFocus: focus,
    shown: focus?.items.map((i) => i.node.key) ?? null };
  return { mood: 'parole', wakes: 0, brains: FOLDED, universe: universeView(signals, false, narrow), scene: null,
    show: directShow(focus) };
}

/** SF-236-08 — les scènes 3D du banc (`scene-<nom>`, et les séquences des panneaux) sur l'univers de test. */
function bancScene(scene: string, narrow: boolean):
    { mood: HeartMood; view: UniverseView; plan: ScenePlan3d | null } | null {
  const name = scene === 'panneau-entree' || scene === 'panneau-sortie' ? 'sgen'
    : scene.startsWith('scene-') ? scene.slice('scene-'.length) : null;
  const view = name ? BANC_SCENE_VIEWS[name] : undefined;
  if (!view) {
    return null;
  }
  const signals: ScreenSignals = { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge', scene: view,
    pulses: 1, state: view.pinned ? 'repos' : 'reflexion', spokenTickets: name === 'tickets' ? ['FORGE-42'] : [],
    stepsDone: name === 'etape' ? { 's-forge#3': 1 } : {}, waiting: view.pinned };
  const universe = universeView(signals, false, narrow);
  // SF-236-09 — la mémoire de session du banc : des fichiers vus plus tôt (nuage du dépôt) et la scène elle-même.
  const memory = new SessionMemory();
  BANC_SESSION_FILES.forEach((f, i) => memory.see(sceneOf('S5', `f${i}`, 'Fichier', { detail: f.path },
    { mode: f.write ? 'write' : 'read', files: [baseName(f.path)] }), 'h-cagip', 's-forge', i));
  memory.see(view, 'h-cagip', 's-forge', 100);
  return { mood: view.pinned ? 'oui' : 'reflexion', view: universe,
    plan: stageScene(signals, universe, narrow, memory.snapshot(100)) };
}

/** SF-236-04 — l'univers posé par chaque scène du banc (univers de test : CAGIP, Edenred ; sujet « Migration Forge »). */
function bancUniverse(scene: string, narrow: boolean): UniverseView {
  if (!scene.startsWith('univers') && !scene.startsWith('cerveau') && !scene.startsWith('entree')
      && !scene.startsWith('cockpit')
      && scene !== 'focus-chantier' && scene !== 'zoom' && scene !== 'dezoom') {
    return EMPTY_VIEW;
  }
  const base = { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge' };
  switch (scene) {
    case 'univers-cite':
    case 'focus-chantier':
    case 'zoom':
    case 'dezoom':
      return universeView({ ...base, lit: ['s-forge#3'], said: ['s-forge#3'] }, false, narrow);
    case 'univers-montrer':
      return universeView({ ...base, shown: ['h-cagip', 'h-eden'] }, false, narrow);
    default:
      return universeView(base, false, narrow || scene === 'univers-telephone');
  }
}
