import { ArchitectureGraph } from './architecture-graph';
import { TOUT_GRAPH, TROIS_TIERS_GRAPH, sixtyNodesGraph } from './architecture-graph.fixture';
import { ArchPhaseKind, ArchState, DEFAULT_ARCH_STATE } from './architecture-stage-director';

/**
 * F-238 / SF-238-09 — **les scènes d'architecture du banc** (`/jarvis-banc?banc=architecture…&t=`), développement
 * seulement, aucun réseau : graphes réels du renderer (fixtures).
 */
export const ARCHITECTURE_BANC_SCENES = [
  'architecture', 'architecture-dessus', 'architecture-eclate', 'architecture-sortie', 'architecture-tout',
  'architecture-60', 'architecture-allume',
] as const;

export interface ArchitectureBanc {
  graph: ArchitectureGraph;
  state: ArchState;
  phase: ArchPhaseKind;
  overlay: boolean;
}

export function bancArchitecture(scene: string, phone: boolean): ArchitectureBanc {
  const base: ArchState = { ...DEFAULT_ARCH_STATE, phone };
  switch (scene) {
    case 'architecture-dessus':
      return { graph: TROIS_TIERS_GRAPH, state: { ...base, view: 'dessus' }, phase: 'repos', overlay: true };
    case 'architecture-eclate':
      return { graph: TROIS_TIERS_GRAPH, state: { ...base, exploded: true }, phase: 'repos', overlay: false };
    case 'architecture-sortie':
      return { graph: TROIS_TIERS_GRAPH, state: base, phase: 'sortie', overlay: false };
    case 'architecture-tout':
      return { graph: TOUT_GRAPH, state: base, phase: 'entree', overlay: false };
    case 'architecture-60':
      return { graph: sixtyNodesGraph(), state: base, phase: 'repos', overlay: false };
    case 'architecture-allume':
      // Jarvis parle de l'ALB et du flux Utilisateurs → ALB : héros en blanc chaud, le reste en contexte.
      return { graph: TROIS_TIERS_GRAPH, state: { ...base, focus: 'alb', lit: ['alb', 'e1'] }, phase: 'repos',
        overlay: false };
    default:
      return { graph: TROIS_TIERS_GRAPH, state: base, phase: 'entree', overlay: false };
  }
}
