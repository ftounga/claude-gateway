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
    | 'correction'>> = {};
  /** F-239 / SF-239-01 — la parole a été refusée (aucun tour) : raison et longueur, jamais le texte. */
  rejected: Pick<TurnTimingReport, 'rejectedKind' | 'rejectedChars'> | null = null;
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

  /** La marque posée, ou `null`. */
  at(step: TurnMark): number | null {
    return this.marks.get(step) ?? null;
  }

  count(counter: TurnCounter, by = 1): void {
    this.counters[counter] = Math.min(1_000, this.counters[counter] + by);
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
    };
  }
}
