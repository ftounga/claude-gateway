import { JarvisUniverse } from '../../scenes/jarvis-universe.models';
import { SceneView } from '../../scenes/jarvis-scene-reader';
import { HoloRole } from '../jarvis-motion';
import { SessionSnapshot } from './jarvis-session-memory';

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
  /** SF-236-09 (N11) — les lignes défilent lentement (pages qui défilent : un document lu). */
  scroll?: boolean;
  /** SF-236-11 (T-P3) — le panneau vole vers le compteur « À envoyer » (1,1 s), puis s'efface. */
  fly?: 'to-send';
}

/**
 * SF-236-12 — des ancrages d'effets hors de l'univers, placés par rapport au cœur et à la caméra : le globe du web
 * (derrière le cœur), les profondeurs (sous lui), le dessus (carte du savoir), le côté (ce qui se construit).
 */
export const ANCHOR_GLOBE = '@globe';
export const ANCHOR_BELOW = '@profondeur';
export const ANCHOR_ABOVE = '@dessus';
export const ANCHOR_SIDE = '@cote';

/** SF-236-09 — un point du nuage du dépôt (N9) : un amas par dossier, lecture ou écriture. */
export interface CloudPoint {
  angle: number;
  height: number;
  write: boolean;
  /** Le fichier de l'action en cours (il brille). */
  hot: boolean;
}

/** Un effet 3D déclaratif (le moteur choisit sa recette). Les SF suivantes en ajoutent. */
export type Effect3d =
  /** S-GEN — un anneau d'impulsion par appel (`seq` avance à chaque appel d'outil). */
  | { kind: 'pulse'; anchor: string; tone: SceneTone; seq: number }
  /** SF-236-09 (N6) — un flux de particules d'un nœud vers un autre ; `rate` 0-1 = débit (∝ sortie). */
  | { kind: 'flow'; from: string; to: string; tone: SceneTone; rate: number; lift?: number }
  /**
   * SF-236-09 — des cartes holographiques : en <b>éventail</b> (S4 tickets, ≤ 6, sorties du client en arc, 90 ms
   * d'écart) ou en <b>pile</b> (S5 feuillets) ; `hot` = la carte nommée (ou le feuillet en cours d'écriture).
   */
  | { kind: 'cards'; anchor: string; count: number; hot: number | null; layout: 'fan' | 'stack' | 'film';
    tone: SceneTone }
  /** SF-236-09 — la loupe qui balaie (S5 recherche). */
  | { kind: 'scan'; anchor: string; tone: SceneTone }
  /** SF-236-09 (N9) — le nuage du dépôt près du sujet : un amas par dossier, lecture / écriture. */
  | { kind: 'cloud'; anchor: string; points: readonly CloudPoint[] }
  /**
   * SF-236-10 — une liaison d'attente (L-attente, pointillé, teinte d'attente) entre un chantier et une personne ;
   * `broken` = l'attente est levée : le filament se scinde en deux fragments qui s'éteignent (T-L4, 0,7 s).
   */
  | { kind: 'tether'; from: string; to: string | null; tone: SceneTone; broken: boolean }
  /** SF-236-10 — S2 : l'arc qui se vide autour du cœur au rythme des secondes restantes (0-1). */
  | { kind: 'countdown'; fraction: number; tone: SceneTone }
  /** SF-236-10 — S14 : la couronne de perles sous le cœur, une par point de route (traité = vert). */
  | { kind: 'crown'; done: readonly boolean[] }
  /** SF-236-10 — S15 : une vague sur les nœuds donnés (80 ms d'écart). */
  | { kind: 'wave'; keys: readonly string[]; tone: SceneTone; seq: number }
  /** SF-236-12 — S6 : le globe filaire derrière le cœur. */
  | { kind: 'globe' }
  /** SF-236-12 — S13 + N8 : l'essaim des sous-agents, petits cœurs satellites reliés au cœur (L-essaim). */
  | { kind: 'swarm'; count: number; tone: SceneTone }
  /** SF-236-12 — S17 : le cadran d'une veille, attaché à son nœud. */
  | { kind: 'dial'; anchor: string; tone: SceneTone }
  /** SF-236-25 (NF-15) — le radar des alertes : un anneau autour de l'univers, un écho par alerte réelle. */
  | { kind: 'radar'; echoes: readonly { anchor: string; tone: SceneTone }[] }
  /** SF-236-26 (NF-16) — la carte d'activité : un anneau à 7 segments autour de chaque client (éclat 0-1 par jour). */
  | { kind: 'activity'; rings: readonly { anchor: string; levels: readonly number[] }[] };

/** Le plan d'une scène 3D. */
export interface ScenePlan3d {
  /** La scène F-234 (`GENERIQUE`, `S3`…) et le rendu choisi. */
  id: string;
  rendered: 'dedicated' | 'generic';
  panels: Panel3d[];
  effects: Effect3d[];
  /**
   * Le nœud que la scène met en avant (allumé, cadré), s'il existe dans l'univers ; `@coeur` = la scène est centrée
   * sur le cœur (la caméra prend la vue d'ensemble) ; `null` = la caméra suit les nœuds cités.
   */
  focus: string | null;
  /** SF-236-10 — un travelling lent (S15 « le point » : 60°, S10 passation) ; joué une fois par `id`. */
  travel?: { id: string; degrees: number };
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
  /** SF-236-09 — la mémoire de session de l'écran (tickets vus, fichiers du nuage) ; jamais un contenu. */
  session: SessionSnapshot;
  /** SF-236-09 — les clés de tickets nommées dans la phrase dite (la carte s'illumine). */
  spokenTickets: readonly string[];
  /** SF-236-10 — étapes faites pendant le tour, par chantier. */
  stepsDone: Readonly<Record<string, number>>;
}

/** Une scène 3D : pure, déterministe. */
export type Scene3d = (scene: SceneView, ctx: SceneContext) => ScenePlan3d;
