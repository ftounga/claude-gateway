import { ConverseBody, FastBrainHandlers, FastBrainOutcome, JarvisFastBrainClient } from './jarvis-fast-brain';
import { isSuspended } from './jarvis-turn-end';

/**
 * **La réponse provisoire du cerveau rapide** (F-252 / SF-252-02, correctif A).
 *
 * Le cerveau rapide part dès la fin de parole avec le texte du flux, en silence : ses événements sont gardés, dans
 * l'ordre, sans atteindre l'écran ni la voix. La retranscription reste la référence : même texte, la réponse est adoptée
 * (rejeu puis flux direct) ; sinon elle est jetée et l'appel normal part avec la retranscription.
 */

/** Normalise une parole : casse, espaces, ponctuation ignorés, **sauf « ? »** ; accents et chiffres gardés. */
export function normalizeUtterance(text: string): string {
  return text.toLowerCase().normalize('NFC')
    .replace(/[^\p{L}\p{N}?]+/gu, ' ')
    .replace(/\s*\?\s*/g, ' ? ')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Vrai si les deux paroles disent la même chose au sens de {@link normalizeUtterance}. */
export function sameUtterance(a: string, b: string): boolean {
  return normalizeUtterance(a) === normalizeUtterance(b);
}

/** Ce qui, en dehors du texte, doit être identique pour qu'une réponse provisoire reste valable. */
function sameRequest(a: ConverseBody, b: ConverseBody): boolean {
  return (a.subjectId ?? null) === (b.subjectId ?? null)
    && (a.chantier ?? null) === (b.chantier ?? null)
    && (a.heardBeforeCut ?? null) === (b.heardBeforeCut ?? null)
    && (a.screen ?? null) === (b.screen ?? null)
    && !!a.presentation === !!b.presentation;
}

export class JarvisSpeculation {
  private readonly controller = new AbortController();
  private readonly events: ((handlers: FastBrainHandlers) => void)[] = [];
  private target: FastBrainHandlers | null = null;
  private dropped = false;
  private readonly finished: Promise<FastBrainOutcome>;

  /** Lance l'appel provisoire ; rien de ce qu'il reçoit n'est joué avant {@link adopt}. */
  constructor(private readonly client: JarvisFastBrainClient, readonly body: ConverseBody,
              readonly speculationId: string = crypto.randomUUID(), readonly anticipated = false) {
    this.finished = client.converse({ ...body, speculationId }, this.holding(), this.controller.signal);
  }

  /** Vrai si cette réponse provisoire vaut pour ce texte de référence et cette requête. */
  matches(reference: string, request: ConverseBody): boolean {
    return !this.dropped && sameUtterance(this.body.text, reference) && sameRequest(this.body, request);
  }

  /** La réponse est retenue : verdict, rejeu dans l'ordre, puis les événements suivants passent directement. */
  adopt(reference: string, handlers: FastBrainHandlers): Promise<FastBrainOutcome> {
    void this.client.verdict(this.speculationId, true, reference);
    this.target = handlers;
    this.events.splice(0).forEach((event) => event(handlers));
    return this.finished;
  }

  /** La réponse est jetée : verdict, appel interrompu, rien de joué. */
  drop(): void {
    if (this.dropped || this.target) {
      return;
    }
    this.dropped = true;
    this.events.length = 0;
    void this.client.verdict(this.speculationId, false, null);
    this.controller.abort();
  }

  private held(event: (handlers: FastBrainHandlers) => void): void {
    if (this.dropped) {
      return;
    }
    if (this.target) {
      event(this.target);
    } else {
      this.events.push(event);
    }
  }

  private holding(): FastBrainHandlers {
    return {
      onText: (sentence) => this.held((h) => h.onText(sentence)),
      onUnderstood: (text) => this.held((h) => h.onUnderstood?.(text)),
      onDelegated: (delegation) => this.held((h) => h.onDelegated(delegation)),
      onFallback: (reason) => this.held((h) => h.onFallback(reason)),
      onDone: (done) => this.held((h) => h.onDone(done)),
      onShow: (command) => this.held((h) => h.onShow?.(command)),
      onCards: (event) => this.held((h) => h.onCards?.(event)),
    };
  }
}

/** F-252 / SF-252-04 — le silence (ms) à partir duquel une phrase close peut partir en avance. */
export const ANTICIPATE_SILENCE_MS = 600;

/**
 * **Quand anticiper la fin de parole** (F-252 / SF-252-04, correctif F) — pur. Une fois par silence, à partir de
 * {@link ANTICIPATE_SILENCE_MS} et sur une phrase close ; la voix qui revient réarme. La fin de tour reste décidée
 * ailleurs (fournisseur, délai de grâce) : anticiper ne dit jamais rien.
 */
export class AnticipationWatch {
  private lastVoice: number;
  private armed = true;

  constructor(now: number) {
    this.lastVoice = now;
  }

  /** Une voix est entendue à cet instant. */
  voice(now: number): void {
    this.lastVoice = now;
    this.armed = true;
  }

  /** Vrai une seule fois par silence, si ce texte peut partir. */
  due(now: number, text: string): boolean {
    if (!this.armed || !text.trim() || isSuspended(text) || now - this.lastVoice < ANTICIPATE_SILENCE_MS) {
      return false;
    }
    this.armed = false;
    return true;
  }

  /** Le silence en cours (ms). */
  silenceMs(now: number): number {
    return Math.max(0, Math.round(now - this.lastVoice));
  }
}
