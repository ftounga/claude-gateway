/**
 * **Le chronomètre du tour vocal** (F-233 / SF-233-01, D2) — pur : on lui donne l'heure, il range des marques en
 * millisecondes depuis la fin de parole détectée (t0). **Aucun contenu** : ni ce qui est dit, ni ce qui est lu.
 */

import { TurnTimingReport } from '../core/models/jarvis.models';

export type { TurnTimingReport };

/** Les étapes d'un tour vocal, dans l'ordre. */
export type TurnMark =
  | 'transcript' | 'reaction' | 'sent' | 'firstToken' | 'firstOralSentence' | 'firstAudioByte' | 'firstSound' | 'done';

/** Les compteurs d'interruption (SF-233-08 / SF-233-09). */
export type TurnCounter =
  | 'interruptionsDetected' | 'interruptionsRestored' | 'interruptionsConfirmed' | 'acknowledgements' | 'falseTriggers';

/** Une marque au-delà est écartée (la passerelle refuse plus de 10 minutes). */
export const MAX_MARK_MS = 600_000;

export class TurnClock {
  private readonly marks = new Map<TurnMark, number>();
  private readonly counters: Record<TurnCounter, number> = {
    interruptionsDetected: 0, interruptionsRestored: 0, interruptionsConfirmed: 0, acknowledgements: 0,
    falseTriggers: 0,
  };
  listenMode: TurnTimingReport['listenMode'] = 'BATCH';
  sttProvider: string | null = null;
  ttsProvider: string | null = null;
  toolTurn = false;
  workspaceId: string | null = null;
  /** F-235 / SF-235-07 (D14) — posé par l'échange avec le cerveau rapide ; absent pour un tour Opus d'avant. */
  fast: Partial<Pick<TurnTimingReport, 'brain' | 'fastModel' | 'fastFirstTokenMs' | 'groundingTrips'
    | 'fillersRemoved' | 'repeatsRemoved' | 'imitationsRemoved' | 'forcedDelegation' | 'delegationReason'
    | 'correction' | 'portraitOn' | 'portraitBlocked' | 'personalAllusion'
    | 'hostOffline' | 'stateQuestion' | 'stateAnswer' | 'stateBlockMs' | 'cardsLagMs' | 'cardsCount'>> = {};
  /** F-239 / SF-239-01 — la parole a été refusée (aucun tour) : raison et longueur, jamais le texte. */
  rejected: Pick<TurnTimingReport, 'rejectedKind' | 'rejectedChars'> | null = null;
  /** F-240 / SF-240-01 — ce qui a été dit (longueur, coupe, invitation), jamais le texte. */
  spoken: Partial<Pick<TurnTimingReport, 'spokenWords' | 'spokenChars' | 'spokenMs' | 'announceWords' | 'oralCut'
    | 'invitation'>> = {};
  private depth: Pick<TurnTimingReport, 'depthReached' | 'depthVia'> | null = null;
  private reported = false;

  /**
   * @param t0       l'instant de la fin de parole détectée
   * @param speechMs la durée de la parole, avant t0 (ou `null`)
   * @param now      l'horloge (injectable pour les tests)
   */
  constructor(readonly t0: number, readonly speechMs: number | null,
              private readonly now: () => number = () => performance.now()) {}

  /** Pose la marque `step` (la première occurrence seulement). */
  mark(step: TurnMark): void {
    if (!this.marks.has(step)) {
      this.marks.set(step, Math.max(0, Math.round(this.now() - this.t0)));
    }
  }

  /** F-240 / SF-240-01 — le temps écoulé depuis la marque `step` (ex. la durée dite depuis le premier son), ou `null`. */
  sinceMark(step: TurnMark): number | null {
    const at = this.marks.get(step);
    return at === undefined ? null : Math.max(0, Math.round(this.now() - this.t0 - at));
  }

  /** La marque posée, ou `null`. */
  at(step: TurnMark): number | null {
    return this.marks.get(step) ?? null;
  }

  count(counter: TurnCounter, by = 1): void {
    this.counters[counter] = Math.min(1_000, this.counters[counter] + by);
  }

  /**
   * F-240 / SF-240-01 — ce qui a été dit de la réponse : mots et caractères de `text` (la réponse dite, hors annonce),
   * sa durée audio si elle est connue. Une valeur déjà posée n'est pas effacée par une absence.
   */
  said(text: string, ms: number | null, extra: Partial<Pick<TurnTimingReport, 'announceWords' | 'oralCut'
    | 'invitation'>> = {}): void {
    this.spoken = {
      ...this.spoken,
      spokenWords: spokenWordCount(text),
      spokenChars: text.length,
      ...(ms !== null && ms >= 0 ? { spokenMs: Math.min(MAX_MARK_MS, Math.round(ms)) } : {}),
      ...extra,
    };
  }

  /** F-240 / SF-240-01 — l'étage atteint (le plus profond est gardé) et son chemin. */
  reach(depth: 1 | 2 | 3, via: 'voix' | 'toucher'): void {
    if (!this.depth || (this.depth.depthReached ?? 0) < depth) {
      this.depth = { depthReached: depth, depthVia: via };
    }
  }

  /** F-240 — l'étage atteint, ou 1 si rien n'a été ouvert. */
  get depthReached(): 1 | 2 | 3 {
    return this.depth?.depthReached ?? 1;
  }

  /** La mesure à envoyer, **une seule fois** (`null` ensuite). */
  takeReport(): TurnTimingReport | null {
    if (this.reported) {
      return null;
    }
    this.reported = true;
    const ms = (step: TurnMark) => {
      const value = this.marks.get(step);
      return value === undefined || value > MAX_MARK_MS ? null : value;
    };
    return {
      workspaceId: this.workspaceId,
      listenMode: this.listenMode,
      sttProvider: this.sttProvider,
      ttsProvider: this.ttsProvider,
      toolTurn: this.toolTurn,
      speechMs: this.speechMs === null ? null : Math.min(MAX_MARK_MS, Math.max(0, Math.round(this.speechMs))),
      transcriptMs: ms('transcript'),
      reactionMs: ms('reaction'),
      sentMs: ms('sent'),
      firstTokenMs: ms('firstToken'),
      firstOralSentenceMs: ms('firstOralSentence'),
      firstAudioByteMs: ms('firstAudioByte'),
      firstSoundMs: ms('firstSound'),
      doneMs: ms('done'),
      ...this.counters,
      ...this.fast,
      ...(this.rejected ?? {}),
      ...this.spoken,
      ...(this.depth ?? {}),
    };
  }
}

/**
 * F-240 / SF-240-01 — **combien de mots** : un groupe séparé par des blancs qui contient au moins une lettre ou un
 * chiffre (même règle que la passerelle, `JarvisSpokenWords`). L'élision et le trait d'union ne coupent pas ; la
 * ponctuation et les marques (🎙, ↪) ne comptent pas.
 */
export function spokenWordCount(text: string | null | undefined): number {
  if (!text || !text.trim()) {
    return 0;
  }
  return text.trim().split(/\s+/).filter((token) => /[\p{L}\p{N}]/u.test(token)).length;
}
