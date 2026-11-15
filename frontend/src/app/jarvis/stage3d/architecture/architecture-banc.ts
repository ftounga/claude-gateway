import { ArchitectureGraph } from './architecture-graph';
import { TOUT_GRAPH, TROIS_TIERS_GRAPH, sixtyNodesGraph } from './architecture-graph.fixture';
import { ArchPhaseKind, ArchState, DEFAULT_ARCH_STATE } from './architecture-stage-director';

/**
 * F-238 / SF-238-09 — **les scènes d'architecture du banc** (`/jarvis-banc?banc=architecture…&t=`), développement
 * seulement, aucun réseau : graphes réels du renderer (fixtures).
 */
export const ARCHITECTURE_BANC_SCENES = [
  'architecture', 'architecture-dessus', 'architecture-eclate', 'architecture-sortie', 'architecture-tout',
  'architecture-60', 'architecture-allume', 'architecture-narration',
] as const;

/**
 * SF-238-12 — la narration du banc (cadrage §11.4) : quatre phrases, une toutes les 3 s ; `&t=` pose l'instant
 * (ex. 200 / 600 / 1 200 / 3 000 ms après le début d'une phrase). Aucune voix, aucun réseau.
 */
export const BANC_NARRATION: readonly { at: number; text: string }[] = [
  { at: 0, text: 'Le trafic entre par l\'ALB, Monsieur, puis se répartit sur les deux zones.' },
  { at: 3000, text: 'Les instances écrivent dans RDS primaire.' },
  { at: 6000, text: 'RDS primaire est répliquée vers RDS secours, en zone B.' },
  { at: 9000, text: 'Les sauvegardes partent dans S3 statiques.' },
];

export interface ArchitectureBanc {
  graph: ArchitectureGraph;
  state: ArchState;
  phase: ArchPhaseKind;
  overlay: boolean;
  script: readonly { at: number; text: string }[];
}

export function bancArchitecture(scene: string, phone: boolean): ArchitectureBanc {
  const base: ArchState = { ...DEFAULT_ARCH_STATE, phone };
  const banc = (b: Omit<ArchitectureBanc, 'script'>): ArchitectureBanc => ({ ...b, script: [] });
  switch (scene) {
    case 'architecture-dessus':
      return banc({ graph: TROIS_TIERS_GRAPH, state: { ...base, view: 'dessus' }, phase: 'repos', overlay: true });
    case 'architecture-eclate':
      return banc({ graph: TROIS_TIERS_GRAPH, state: { ...base, exploded: true }, phase: 'repos', overlay: false });
    case 'architecture-sortie':
      return banc({ graph: TROIS_TIERS_GRAPH, state: base, phase: 'sortie', overlay: false });
    case 'architecture-tout':
      return banc({ graph: TOUT_GRAPH, state: base, phase: 'entree', overlay: false });
    case 'architecture-60':
      return banc({ graph: sixtyNodesGraph(), state: base, phase: 'repos', overlay: false });
    case 'architecture-allume':
      // Jarvis parle de l'ALB et du flux Utilisateurs → ALB : héros en blanc chaud, le reste en contexte.
      return banc({ graph: TROIS_TIERS_GRAPH, state: { ...base, focus: 'alb', lit: ['alb', 'e1'] }, phase: 'repos',
        overlay: false });
    case 'architecture-narration':
      return { graph: TROIS_TIERS_GRAPH, state: base, phase: 'repos', overlay: false, script: BANC_NARRATION };
    default:
      return banc({ graph: TROIS_TIERS_GRAPH, state: base, phase: 'entree', overlay: false });
  }
}
