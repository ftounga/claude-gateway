import { Injectable, inject } from '@angular/core';

import { AuthService } from '../core/services/auth.service';
import { ShowCommand } from './pieces/jarvis-piece-show';

/**
 * **Le cerveau rapide de Jarvis, côté écran** (F-235 / SF-235-05).
 *
 * `POST /api/jarvis/converse` (SSE) : `text` (une phrase complète, déjà passée par les garde-fous du serveur),
 * `delegated` (la demande part à Opus, l'annonce est à dire), `fallback` (le cerveau rapide s'efface : mode actuel),
 * `done`, `error`. **503** = coupe-circuit éteint : l'écran fait exactement ce qu'il faisait avant (tout à Opus).
 */

/** Une délégation annoncée par le cerveau rapide. */
export interface FastDelegation {
  delegationId: string;
  subjectId: string | null;
  chantier: number | null;
  label: string | null;
  announce: string;
  demande: string;
  automatic: boolean;
  reason: string | null;
}

/** La fin d'un échange rapide. */
export interface FastDone {
  brain: 'FAST' | 'FAST_DELEGATED' | 'OPUS';
  spokenText: string;
  firstTokenMs: number;
  model: string;
  /** SF-235-03 — ce que les garde-fous ont fait (compté, sans contenu). */
  guards?: FastGuards | null;
  /**
   * F-239 / SF-239-11 — le budget mensuel du cerveau rapide : `soft` dépassé (il continue, sobrement), `hard` plafond
   * atteint (réponse fixe, aucun appel) ; absent sinon. Jamais une bascule vers Opus.
   */
  budget?: 'soft' | 'hard' | null;
  /** F-241 / SF-241-03 — le portrait servait à l'échange (mesure, jamais le texte). */
  portrait?: boolean;
}

/** Les compteurs des garde-fous du serveur (SF-235-03, SF-235-09). */
export interface FastGuards {
  groundingTrips: number;
  fillersRemoved: number;
  repeatsRemoved: number;
  forcedDelegation: boolean;
  toolText: boolean;
  imitationsRemoved: number;
  /** F-241 / SF-241-03 (D12 a) — phrases écartées par les interdits du portrait. */
  portraitBlocked?: number;
}

/** F-235 / SF-235-07 (D14) — une parole qui corrige la réponse rapide précédente (dite il y a ≤ 30 s). */
export const CORRECTION_WINDOW_MS = 30_000;

/** « Non », « c'est faux », « vérifie », « demande à Opus »… (liste fermée, classement local, sans modèle). */
export function isCorrection(text: string): boolean {
  const n = text.toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^a-z ]+/g, ' ')
    .replace(/\s+/g, ' ').trim().replace(/^(jarvis|monsieur) /, '');
  return /^(non|non non|c est faux|non c est faux|c est pas ca|ce n est pas ca|non c est pas ca|faux|verifie|verifie ca|verifiez|demande a opus|demande le a opus|reflechis|reflechis y|tu te trompes|vous vous trompez)$/
    .test(n);
}

export interface FastBrainHandlers {
  onText(sentence: string): void;
  /** F-239 / SF-239-05 — la parole relue par le cerveau rapide (jamais dite), avant la réponse. */
  onUnderstood?(text: string): void;
  onDelegated(delegation: FastDelegation): void;
  onFallback(reason: string): void;
  onDone(done: FastDone): void;
  /** F-237 / SF-237-08 — un ordre d'écran du cerveau rapide (outil `afficher`), déjà validé par la passerelle. */
  onShow?(command: ShowCommand): void;
}

/** L'issue d'un appel : parti au bout, cerveau rapide éteint (503), ou en échec (jamais muet : l'écran se replie). */
export type FastBrainOutcome = 'ok' | 'unavailable' | 'failed';

export interface ConverseBody {
  subjectId: string | null;
  text: string;
  chantier?: number | null;
  heardBeforeCut?: string | null;
  /** F-237 / SF-237-08 — le bloc `## Écran` (ce que l'écran montre, numéroté). */
  screen?: string | null;
  /** F-241 / SF-241-03 (D17) — l'écran est en présentation : aucun portrait, note figée. */
  presentation?: boolean;
}

@Injectable({ providedIn: 'root' })
export class JarvisFastBrainClient {
  private readonly auth = inject(AuthService);

  /**
   * Pré-chauffe le cache du cerveau rapide (D6) et dit s'il est allumé : `true` (204), `false` (503 ou erreur —
   * l'écran garde le mode actuel).
   */
  async warm(subjectId: string | null): Promise<boolean> {
    try {
      const response = await fetch('/api/jarvis/converse/warm', {
        method: 'POST',
        headers: this.headers(),
        body: JSON.stringify(subjectId ? { subjectId } : {}),
      });
      return response.ok;
    } catch {
      return false;
    }
  }

  /** Converse ; ne lève jamais. */
  async converse(body: ConverseBody, handlers: FastBrainHandlers): Promise<FastBrainOutcome> {
    let finished = false;
    try {
      const response = await fetch('/api/jarvis/converse', {
        method: 'POST',
        headers: { ...this.headers(), Accept: 'text/event-stream' },
        body: JSON.stringify({
          ...(body.subjectId ? { subjectId: body.subjectId } : {}),
          text: body.text,
          ...(body.chantier ? { chantier: body.chantier } : {}),
          ...(body.heardBeforeCut ? { heardBeforeCut: body.heardBeforeCut } : {}),
          ...(body.screen ? { screen: body.screen } : {}),
          ...(body.presentation ? { presentation: true } : {}),
        }),
      });
      if (response.status === 503) {
        return 'unavailable';
      }
      if (!response.ok || !response.body) {
        return 'failed';
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let failed = false;
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        let sep: number;
        while ((sep = buffer.indexOf('\n\n')) >= 0) {
          const outcome = dispatch(buffer.slice(0, sep), handlers);
          buffer = buffer.slice(sep + 2);
          if (outcome === 'done') {
            finished = true;
          } else if (outcome === 'error') {
            failed = true;
          }
        }
      }
      return finished && !failed ? 'ok' : 'failed';
    } catch {
      return finished ? 'ok' : 'failed';
    }
  }

  private headers(): Record<string, string> {
    const token = this.auth.token();
    return { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) };
  }
}

/** Lit un événement SSE et le relaie ; rend `done`, `error` ou `null`. Exporté pour les tests. */
export function dispatch(raw: string, handlers: FastBrainHandlers): 'done' | 'error' | null {
  let event = 'message';
  const data: string[] = [];
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      data.push(line.slice(5).replace(/^ /, ''));
    }
  }
  let payload: Record<string, unknown> = {};
  try {
    payload = data.length > 0 ? JSON.parse(data.join('\n')) as Record<string, unknown> : {};
  } catch {
    return null;
  }
  switch (event) {
    case 'text':
      if (typeof payload['delta'] === 'string' && payload['delta'].trim()) {
        handlers.onText((payload['delta'] as string).trim());
      }
      return null;
    case 'understood':
      if (typeof payload['text'] === 'string' && payload['text'].trim()) {
        handlers.onUnderstood?.((payload['text'] as string).trim());
      }
      return null;
    case 'delegated':
      handlers.onDelegated(payload as unknown as FastDelegation);
      return null;
    case 'fallback':
      handlers.onFallback(String(payload['reason'] ?? ''));
      return null;
    case 'show':
      if (typeof payload['action'] === 'string') {
        handlers.onShow?.(payload as unknown as ShowCommand);
      }
      return null;
    case 'done':
      handlers.onDone(payload as unknown as FastDone);
      return 'done';
    case 'error':
      return 'error';
    default:
      return null;
  }
}

/** Une fin de délégation à annoncer. */
export interface Announcement {
  label: string;
  phrase: string;
  subjectId: string;
  confirm: boolean;
  /** F-240 / SF-240-01 — la délégation d'origine (sa mesure attend que cette fin soit dite). */
  delegationId?: string;
  /** F-240 / SF-240-02 (D5) — la phrase a été coupée par la garde des 35 mots. */
  cut?: boolean;
  /** F-240 / SF-240-02 (D6) — l'invitation ciblée à dire après la phrase (déjà filtrée), ou `null`. */
  invitation?: string | null;
}

/**
 * **La file d'annonces** (D13) : les fins de délégation, dites dans l'ordre de fin (FIFO), une à la fois, jamais
 * pendant que le PO parle ni pendant une autre phrase de Jarvis ; la dernière dite reste disponible (« redis-moi »).
 */
export class AnnouncementQueue {
  private readonly items: Announcement[] = [];
  private last: Announcement | null = null;

  push(item: Announcement): void {
    this.items.push(item);
  }

  get size(): number {
    return this.items.length;
  }

  /** La prochaine annonce si le moment est libre, ou `null`. */
  next(free: boolean): Announcement | null {
    if (!free || this.items.length === 0) {
      return null;
    }
    const item = this.items.shift() ?? null;
    this.last = item;
    return item;
  }

  /** La dernière annonce dite (« redis-moi »). */
  repeat(): Announcement | null {
    return this.last;
  }
}

/** La phrase dite pour une fin de délégation : son `label`, puis la ligne 🎙 de la réponse finale. */
export function announcementText(item: Announcement): string {
  const label = item.label.trim().replace(/[.!?…]+$/u, '');
  return label ? `Pour ${label} : ${item.phrase}` : item.phrase;
}

/** « Redis-moi », « répète », « tu peux répéter ? » (liste fermée). */
export function isRepeatCommand(text: string): boolean {
  const n = text.toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^a-z ]+/g, ' ')
    .replace(/\s+/g, ' ').trim().replace(/^(jarvis|monsieur) /, '');
  return ['redis moi', 'redis le', 'redis', 'repete', 'repete moi', 'repetez', 'tu peux repeter',
    'vous pouvez repeter', 'peux tu repeter', 'pouvez vous repeter'].includes(n);
}

/** La question posée quand aucun sujet n'est engagé (D19 : jamais de choix au hasard). */
export const ASK_SUBJECT = 'Pour quel client ou quel sujet, Monsieur ?';
