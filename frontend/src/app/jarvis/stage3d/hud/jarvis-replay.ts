import { AtelierMessage } from '../../../core/models/atelier.models';
import { NameIndex } from '../../scenes/jarvis-name-matcher';
import { JarvisSceneReader, SceneView } from '../../scenes/jarvis-scene-reader';
import { JarvisUniverse } from '../../scenes/jarvis-universe.models';

/**
 * F-236 / SF-236-22 — **le replay visuel d'un tour** (NF-9) et **« Pendant votre absence »** (NF-10). Fichier <b>pur</b>.
 *
 * <p>Un tour terminé est relu dans l'historique du fil (déjà isolé par `user_id` côté passerelle) et rejoué dans un
 * <b>lecteur privé</b> : une instance de {@link JarvisSceneReader} créée pour le replay seul, sur une horloge simulée.
 * Le bus vivant de l'écran n'est <b>jamais</b> alimenté : le replay produit une suite d'images (scène, nœuds cités,
 * appels, étapes) que l'écran substitue à celles du bus tant qu'il joue.</p>
 *
 * <p>Déterministe : le même tour, sur le même univers, donne exactement la même suite d'images.</p>
 */

/** Une étape rejouée : l'outil et ce sur quoi il porte (jamais un corps de message). */
export interface ReplayStep {
  tool: string;
  detail: string | null;
  output: string;
}

/** Une image du replay : ce que l'écran dessine à cet instant. */
export interface ReplayFrame {
  /** Millisecondes depuis le début du replay (horloge ×1). */
  at: number;
  lit: string[];
  scene: SceneView | null;
  pulses: number;
  stepsDone: Record<string, number>;
}

/** Un tour prêt à rejouer. */
export interface ReplayTrack {
  /** L'identifiant du message de l'utilisateur qui a ouvert le tour (`?replay=<messageId>`). */
  id: string;
  time: string;
  ask: string;
  frames: ReplayFrame[];
  /** Durée à ×1, en millisecondes. */
  duration: number;
}

/** Durée d'une étape à ×1 (un tour de 40 actions dure ≈ 80 s à ×1, 20 s à ×4). */
export const STEP_MS = 2000;
/** Temps laissé à la dernière scène après la dernière étape. */
export const TAIL_MS = 1500;
/** Pas de l'horloge simulée : une image toutes les 250 ms (« image par image »). */
export const FRAME_MS = 250;
/** Au plus autant d'étapes rejouées (les suivantes sont résumées par le compteur). */
export const MAX_STEPS = 60;
/** Au plus autant de tours enchaînés par « Pendant votre absence ». */
export const MAX_ABSENCE = 12;
export const SPEEDS = [1, 4] as const;
export type ReplaySpeed = typeof SPEEDS[number];

const ASK_MAX = 90;

/** Les étapes d'une réponse persistée (sa transcription), bornées. */
export function replaySteps(reply: AtelierMessage | null | undefined): ReplayStep[] {
  return (reply?.terminal?.blocks ?? []).slice(0, MAX_STEPS).map((b) => ({
    tool: b.tool ?? '',
    detail: b.command ?? null,
    output: b.hasOutput ? b.output ?? '' : '',
  }));
}

/**
 * Rejoue les étapes dans un lecteur <b>privé</b> sur une horloge simulée et rend la suite d'images (une toutes les
 * {@link FRAME_MS} ms). Aucune horloge réelle, aucun aléa : deux appels identiques rendent deux suites identiques.
 */
export function buildFrames(steps: readonly ReplayStep[], universe: JarvisUniverse | null,
    subjectId: string | null): ReplayFrame[] {
  const reader = new JarvisSceneReader(new NameIndex(universe), subjectId);
  reader.startTurn();
  const duration = steps.length * STEP_MS + TAIL_MS;
  const frames: ReplayFrame[] = [];
  let next = 0;
  let finished = false;
  for (let at = 0; at <= duration; at += FRAME_MS) {
    while (next < steps.length && next * STEP_MS <= at) {
      const step = steps[next];
      const t = next * STEP_MS;
      reader.action({ type: step.tool, path: step.detail ?? undefined }, t);
      if (step.output) {
        reader.commandOutput(step.output, t);
      }
      next++;
    }
    if (!finished && next === steps.length && at >= duration - TAIL_MS) {
      reader.done(at);
      finished = true;
    }
    reader.tick(at);
    const s = reader.state();
    frames.push({ at, lit: s.lit.map((l) => l.node.key), scene: s.scene, pulses: s.pulses,
      stepsDone: s.stepsDone });
  }
  return frames;
}

function oneLine(text: string, max: number): string {
  const flat = (text ?? '').replace(/\s+/g, ' ').trim();
  return flat.length > max ? `${flat.slice(0, max - 1)}…` : flat;
}

function clock(at: Date): string {
  return `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
}

/** Le tour ouvert par le message `userMessageId` (sa réponse suit), prêt à rejouer ; `null` s'il n'existe pas. */
export function trackOf(messages: readonly AtelierMessage[], userMessageId: string, universe: JarvisUniverse | null,
    subjectId: string | null): ReplayTrack | null {
  const i = messages.findIndex((m) => m.id === userMessageId && m.role === 'USER');
  if (i < 0) {
    return null;
  }
  return toTrack(messages, i, universe, subjectId);
}

function toTrack(messages: readonly AtelierMessage[], i: number, universe: JarvisUniverse | null,
    subjectId: string | null): ReplayTrack | null {
  const m = messages[i];
  const reply = messages[i + 1]?.role === 'ASSISTANT' ? messages[i + 1] : null;
  const steps = replaySteps(reply);
  if (steps.length === 0) {
    return null; // un tour sans action n'a rien à montrer
  }
  const frames = buildFrames(steps, universe, subjectId);
  const at = new Date(m.createdAt);
  return { id: m.id, time: Number.isNaN(at.getTime()) ? '' : clock(at), ask: oneLine(m.content, ASK_MAX), frames,
    duration: frames[frames.length - 1].at };
}

/**
 * NF-10 — les tours faits <b>après</b> `since` (la dernière visite sur cet appareil), du plus ancien au plus récent,
 * qui ont au moins une action ; au plus {@link MAX_ABSENCE} (les plus récents).
 */
export function absenceTracks(messages: readonly AtelierMessage[], since: number | null,
    universe: JarvisUniverse | null, subjectId: string | null): ReplayTrack[] {
  if (since === null || !Number.isFinite(since)) {
    return [];
  }
  const tracks: ReplayTrack[] = [];
  for (let i = 0; i < messages.length; i++) {
    const m = messages[i];
    const at = new Date(m.createdAt).getTime();
    if (m.role !== 'USER' || Number.isNaN(at) || at <= since) {
      continue;
    }
    const track = toTrack(messages, i, universe, subjectId);
    if (track) {
      tracks.push(track);
    }
  }
  return tracks.slice(-MAX_ABSENCE);
}

/**
 * Le lecteur du replay : position, vitesse ×1 / ×4, pause, image par image, enchaînement de plusieurs tours (NF-10).
 * Il ne connaît que ses images : l'écran l'avance avec le temps réel écoulé.
 */
export class ReplayPlayer {
  private index = 0;
  private position = 0;
  speed: ReplaySpeed = 1;
  paused = false;

  constructor(readonly tracks: readonly ReplayTrack[]) {}

  get track(): ReplayTrack | null {
    return this.tracks[this.index] ?? null;
  }

  get trackIndex(): number {
    return this.index;
  }

  get ended(): boolean {
    return this.track === null;
  }

  /** Position dans le tour courant (ms, horloge ×1). */
  get at(): number {
    return this.position;
  }

  /** L'image affichée : la dernière dont l'instant est atteint. */
  frame(): ReplayFrame | null {
    const t = this.track;
    if (!t) {
      return null;
    }
    const i = Math.min(t.frames.length - 1, Math.floor(this.position / FRAME_MS));
    return t.frames[Math.max(0, i)];
  }

  /** Le temps réel passe : avance de `dtMs × vitesse` ; passe au tour suivant en fin de tour. */
  advance(dtMs: number): void {
    const t = this.track;
    if (!t || this.paused || !(dtMs > 0)) {
      return;
    }
    this.position += dtMs * this.speed;
    if (this.position > t.duration) {
      this.index++;
      this.position = 0;
    }
  }

  setSpeed(speed: ReplaySpeed): void {
    this.speed = speed;
  }

  togglePause(): void {
    this.paused = !this.paused;
  }

  /** Image par image : met en pause et avance (ou recule) d'une image, sans quitter le tour. */
  step(direction: 1 | -1): void {
    const t = this.track;
    if (!t) {
      return;
    }
    this.paused = true;
    const i = Math.floor(this.position / FRAME_MS) + direction;
    this.position = Math.max(0, Math.min(t.frames.length - 1, i)) * FRAME_MS;
  }

  /** Tour suivant (NF-10), ou fin. */
  skip(): void {
    this.index++;
    this.position = 0;
  }
}
