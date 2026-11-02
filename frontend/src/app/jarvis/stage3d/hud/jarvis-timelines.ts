import { AtelierMessage } from '../../../core/models/atelier.models';
import { GENERIC_SCENE, resolveScene } from '../../scenes/jarvis-scene-registry';
import { SceneView } from '../../scenes/jarvis-scene-reader';

/**
 * F-236 / SF-236-17 — **les chronologies** (NF-3, NF-4 / N14). Fichier <b>pur</b>.
 *
 * <ul>
 *   <li><b>Chronologie du tour</b> : une perle par appel d'outil (`pulses`), colorée par la <b>famille</b> de la
 *   scène en cours, d'après le registre F-234 — lue sur le bus, <b>aucun appel</b> ;</li>
 *   <li><b>Chronologie de la journée</b> : les tours du jour du sujet, lus <b>hors tour, à la demande</b> dans
 *   l'historique du fil (`GET /api/workspaces/{id}/chat`, isolé par `user_id` côté passerelle) ; une perle par outil
 *   de la transcription.</li>
 * </ul>
 */

/** Les familles d'action, dans la grammaire des couleurs (§23) : aucune couleur hors de son sens. */
export type ActionFamily = 'poste' | 'attente' | 'reussite' | 'cerveau' | 'autre';

export const FAMILY_TONE: Readonly<Record<ActionFamily, 'normal' | 'wait' | 'green' | 'deep' | 'off'>> = {
  poste: 'normal', attente: 'wait', reussite: 'green', cerveau: 'deep', autre: 'off',
};

export const FAMILY_WORD: Readonly<Record<ActionFamily, string>> = {
  poste: 'travail sur le poste', attente: 'attend une réponse', reussite: 'création, étape faite',
  cerveau: 'délégation, passation, mémoire', autre: 'autre action',
};

/**
 * La famille de chaque scène F-234 : le poste (commande, fichiers, tickets, web, vidéo, point), ce qui attend le PO
 * (« oui », message, attente, points de route), ce qui réussit (création, étape), le cerveau (passation, mémoire,
 * essaim, veille), le reste (générique).
 */
export const SCENE_FAMILY: Readonly<Record<string, ActionFamily>> = {
  S3: 'poste', S4: 'poste', S5: 'poste', S5b: 'poste', S6: 'poste', S15: 'poste', S16: 'poste', S18: 'poste',
  S19: 'poste',
  S1: 'attente', S2: 'attente', S11: 'attente', S14: 'attente',
  S7: 'reussite', S9: 'reussite',
  S10: 'cerveau', S12: 'cerveau', S13: 'cerveau', S17: 'cerveau',
  [GENERIC_SCENE]: 'autre',
};

export function familyOf(sceneId: string | null | undefined): ActionFamily {
  return sceneId && Object.hasOwn(SCENE_FAMILY, sceneId) ? SCENE_FAMILY[sceneId] : 'autre';
}

/** Au plus autant de perles dessinées ; au-delà, « +n ». */
export const MAX_PEARLS = 40;

export interface Pearl {
  family: ActionFamily;
}

/** Ce que la chronologie du tour lit de l'écran. */
export interface TurnTimelineInput {
  turnActive: boolean;
  pulses: number;
  scene: SceneView | null;
}

/**
 * La chronologie du tour en cours : les perles s'ajoutent à chaque appel d'outil, la famille étant celle de la
 * scène affichée à ce moment ; remise à zéro au début d'un tour, gardée à la fin (relire ce qui s'est fait).
 */
export class TurnTimeline {
  private pearls: Pearl[] = [];
  private seen = 0;
  private active = false;

  /** Relit l'écran ; `true` si la chronologie a changé. */
  observe(s: TurnTimelineInput): boolean {
    let changed = false;
    if (s.turnActive && !this.active) {
      this.pearls = [];
      this.seen = 0;
      changed = true;
    }
    this.active = s.turnActive;
    if (s.pulses < this.seen) {
      this.seen = s.pulses; // compteur remis à zéro par un nouveau tour du lecteur
    }
    if (s.pulses > this.seen) {
      const family = familyOf(s.scene?.id);
      for (let i = this.seen; i < s.pulses; i++) {
        this.pearls.push({ family });
      }
      this.seen = s.pulses;
      changed = true;
    }
    return changed;
  }

  snapshot(): { pearls: Pearl[]; more: number } {
    const extra = Math.max(0, this.pearls.length - MAX_PEARLS);
    return { pearls: this.pearls.slice(extra), more: extra };
  }
}

/** Un tour de la journée, tel que relu dans l'historique du fil. */
export interface DayTurn {
  id: string;
  /** « 08:10 ». */
  time: string;
  /** Minutes depuis minuit (position sur l'axe). */
  minute: number;
  /** La demande, sur une ligne, bornée. */
  ask: string;
  /** La première phrase de la réponse, bornée ; `null` sans réponse. */
  answer: string | null;
  actions: number;
  pearls: Pearl[];
  /** Le tour a été dit à Jarvis. */
  voice: boolean;
}

const ASK_MAX = 90;
const ANSWER_MAX = 160;

function oneLine(text: string, max: number): string {
  const flat = (text ?? '').replace(/\s+/g, ' ').trim();
  return flat.length > max ? `${flat.slice(0, max - 1)}…` : flat;
}

function firstSentence(text: string): string {
  const flat = (text ?? '').replace(/[#*_`>]+/g, ' ').replace(/\s+/g, ' ').trim();
  const end = flat.search(/[.!?](\s|$)/);
  return end > 0 ? flat.slice(0, end + 1) : flat;
}

function localDay(d: Date): string {
  return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
}

/**
 * Les tours du jour (`today` en heure locale) : chaque message de l'utilisateur ouvre un tour ; le message de
 * l'assistant qui suit en donne la réponse et la transcription (une perle par outil, famille du registre).
 */
export function dayTurns(messages: readonly AtelierMessage[], today: Date): DayTurn[] {
  const day = localDay(today);
  const turns: DayTurn[] = [];
  for (let i = 0; i < messages.length; i++) {
    const m = messages[i];
    if (m.role !== 'USER') {
      continue;
    }
    const at = new Date(m.createdAt);
    if (Number.isNaN(at.getTime()) || localDay(at) !== day) {
      continue;
    }
    const reply = messages[i + 1]?.role === 'ASSISTANT' ? messages[i + 1] : null;
    const blocks = reply?.terminal?.blocks ?? [];
    const omitted = reply?.terminal?.omittedBlocks ?? 0;
    const pearls = blocks.map((b) => ({ family: familyOf(resolveScene(b.tool).scene) }));
    turns.push({
      id: m.id,
      time: `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`,
      minute: at.getHours() * 60 + at.getMinutes(),
      ask: oneLine(m.content, ASK_MAX),
      answer: reply?.content ? oneLine(firstSentence(reply.content), ANSWER_MAX) : null,
      actions: blocks.length + omitted,
      pearls: pearls.slice(0, MAX_PEARLS),
      voice: m.via === 'JARVIS',
    });
  }
  return turns;
}

/**
 * L'axe de la journée : du premier tour (arrondi à l'heure, 8 h au plus tard) à maintenant — ou au dernier tour s'il
 * est plus tard (horloge du poste en avance), arrondi à l'heure suivante.
 */
export function dayAxis(turns: readonly DayTurn[], now: Date): { from: number; to: number } {
  const nowMinute = now.getHours() * 60 + now.getMinutes();
  const minutes = turns.map((t) => t.minute);
  const first = minutes.length > 0 ? Math.min(...minutes) : nowMinute;
  const last = minutes.length > 0 ? Math.ceil((Math.max(...minutes) + 1) / 60) * 60 : 0;
  const from = Math.min(8 * 60, Math.floor(first / 60) * 60);
  return { from, to: Math.max(from + 60, nowMinute, last) };
}
