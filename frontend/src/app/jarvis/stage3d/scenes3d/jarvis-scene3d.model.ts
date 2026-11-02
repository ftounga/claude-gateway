import { JarvisUniverse } from '../../scenes/jarvis-universe.models';
import { SceneView } from '../../scenes/jarvis-scene-reader';
import { HoloRole } from '../jarvis-motion';

/**
 * F-236 / SF-236-08 — **le contrat des scènes 3D** (cadrage §3.2, §9.2). Fichier <b>pur</b> (aucun import de
 * `three`) : une scène 3D est une <b>fonction</b> de la scène F-234 en cours vers un <b>plan de scène</b> déclaratif
 * (panneaux holographiques + effets + nœud à cadrer). Le moteur ne fait que dessiner et interpoler ce plan.
 */

/** La teinte d'un panneau ou d'un effet : un rôle de la grammaire §23, rien d'autre. */
export type SceneTone = Extract<HoloRole, 'normal' | 'wait' | 'deep' | 'green' | 'alert' | 'off'>;

/** Ce qu'un panneau montre en plus de son titre. */
export type PanelKind = 'label' | 'terminal' | 'cards' | 'sheets' | 'chantier' | 'question' | 'envelope' | 'address'
  | 'creation' | 'film' | 'memory' | 'swarm' | 'route' | 'dial' | 'cost' | 'alert' | 'show';

/** Une ligne d'un panneau (texte déjà masqué et borné par le lecteur F-234, jamais un corps). */
export interface PanelLine {
  text: string;
  /** Mise en avant (carte nommée, feuillet en cours d'écriture, point traité…). */
  hot?: boolean;
  /** Teinte propre de la ligne (sinon celle du panneau). */
  tone?: SceneTone;
}

/** Un panneau holographique ancré à un nœud (ou au cœur), toujours face à l'écran (T6). */
export interface Panel3d {
  /** Identité stable : un même identifiant d'un plan à l'autre = le même panneau (pas de réapparition). */
  id: string;
  /** La clé du nœud d'ancrage (`@coeur` = le cœur). */
  anchor: string;
  tone: SceneTone;
  kind: PanelKind;
  title: string;
  lines: PanelLine[];
  /** Police à chasse fixe (commandes, clés, adresses). */
  mono?: boolean;
  /** Avancement 0-1 (barre de chantier, compte à rebours). */
  progress?: number;
  /** Devant tout, jamais écarté par la borne du pool (S2). */
  front?: boolean;
}

/** Un effet 3D déclaratif (le moteur choisit sa recette). Les SF suivantes en ajoutent. */
export type Effect3d =
  /** S-GEN — un anneau d'impulsion par appel (`seq` avance à chaque appel d'outil). */
  | { kind: 'pulse'; anchor: string; tone: SceneTone; seq: number };

/** Le plan d'une scène 3D. */
export interface ScenePlan3d {
  /** La scène F-234 (`GENERIQUE`, `S3`…) et le rendu choisi. */
  id: string;
  rendered: 'dedicated' | 'generic';
  panels: Panel3d[];
  effects: Effect3d[];
  /** Le nœud que la scène met en avant (allumé, cadré), s'il existe dans l'univers. */
  focus: string | null;
}

/** Ce qu'une scène 3D sait en plus de la scène F-234 (lu, jamais écrit). */
export interface SceneContext {
  universe: JarvisUniverse | null;
  subjectId: string | null;
  /** Les clés posées dans l'univers 3D (un ancrage absent retombe sur le cœur). */
  nodes: ReadonlySet<string>;
  /** Le compteur d'appels d'outil du tour (une impulsion par appel). */
  pulses: number;
  /** Téléphone (portrait étroit). */
  narrow: boolean;
}

/** Une scène 3D : pure, déterministe. */
export type Scene3d = (scene: SceneView, ctx: SceneContext) => ScenePlan3d;
