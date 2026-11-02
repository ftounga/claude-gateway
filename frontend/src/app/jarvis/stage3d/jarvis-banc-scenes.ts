import { testUniverse } from '../scenes/jarvis-universe.fixture';
import { BrainsState, FOLDED } from './jarvis-brains';
import { HeartMood } from './jarvis-heart';
import { EMPTY_VIEW, IDLE_SCREEN, StagePlan, UniverseView, universeView } from './jarvis-stage-director';

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
  const mood = Object.hasOwn(BANC_MOODS, scene) ? BANC_MOODS[scene] : 'repos';
  return {
    plan: { mood, wakes: scene === 'reveil' ? 1 : 0, brains: bancBrains(scene).brains,
      brainsFromDeployed: bancBrains(scene).fromDeployed, universe: bancUniverse(scene, narrow),
      bancCamera: scene === 'zoom' ? 'zoom' : scene === 'dezoom' ? 'dezoom' : undefined },
    voice: mood === 'ecoute' || mood === 'parole' ? 0.7 : 0,
  };
}

/** SF-236-04 — l'univers posé par chaque scène du banc (univers de test : CAGIP, Edenred ; sujet « Migration Forge »). */
function bancUniverse(scene: string, narrow: boolean): UniverseView {
  if (!scene.startsWith('univers') && !scene.startsWith('cerveau') && !scene.startsWith('entree')
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
