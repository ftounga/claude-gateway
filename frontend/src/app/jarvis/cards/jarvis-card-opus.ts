import { JarvisCard } from './jarvis-card.models';

/**
 * F-248 / SF-248-08 — **les clés citées par Opus** (cadrage §4.8) : dans chaque ligne 🎙 **jouée**, les clés techniques
 * d'un ticket (`PROJ-123`) ou d'une revue (`projet!42`) ; demandées à la passerelle **après le premier mot**
 * (`GET /api/jarvis/cards?keys=`), 6 au plus par tour, chacune une fois. Les numéros seuls (`#17`, `!42` sans projet)
 * et les versions ne désignent rien sans contexte : ignorés. Pur.
 */
export const MAX_OPUS_KEYS = 6;

const TICKET = /\b[A-Za-z][A-Za-z0-9]{1,15}-\d{1,7}\b/g;
const REVIEW = /(?:^|[\s(«"'])([A-Za-z0-9._/-]{1,80}![0-9]{1,7})\b/g;

/** Les clés d'un ticket ou d'une revue d'une phrase, dans l'ordre. */
export function opusKeys(sentence: string): string[] {
  const out: string[] = [];
  const add = (k: string) => {
    if (!out.some((x) => x.toLowerCase() === k.toLowerCase())) {
      out.push(k);
    }
  };
  for (const m of (sentence ?? '').matchAll(REVIEW)) {
    add(m[1]);
  }
  for (const m of (sentence ?? '').matchAll(TICKET)) {
    add(m[0]);
  }
  return out;
}

/** Le jeu d'un tour d'Opus : les clés déjà demandées et les pancartes revenues. */
export class OpusCards {
  private readonly asked = new Set<string>();
  readonly cards = new Map<string, JarvisCard>();

  constructor(readonly id: string) {}

  /** Les clés neuves d'une phrase jouée (6 au plus pour le tour). */
  take(sentence: string): string[] {
    const fresh: string[] = [];
    for (const k of opusKeys(sentence)) {
      const n = k.toLowerCase();
      if (this.asked.size >= MAX_OPUS_KEYS) {
        break;
      }
      if (!this.asked.has(n)) {
        this.asked.add(n);
        fresh.push(k);
      }
    }
    return fresh;
  }

  /** Range les pancartes revenues ; rend le jeu entier (ordre d'arrivée). */
  add(cards: readonly JarvisCard[]): JarvisCard[] {
    for (const c of cards) {
      this.cards.set(c.id, { ...c, targeted: true });
    }
    return [...this.cards.values()];
  }
}
