import { InjectionToken, signal } from '@angular/core';

import { normalize } from './jarvis-oral';

/**
 * **La conversation mains libres** (F-233 / SF-233-11) — pure : l'écran lui dit ce qui se passe (activité, état), elle
 * dit s'il faut réécouter, et quand la conversation s'éteint d'elle-même.
 *
 * - `active` : Jarvis réécoute seul dès qu'il revient au repos sans tour en cours ;
 * - `paused` : le PO a demandé la pause (bouton ou « Jarvis, pause ») — micro coupé, le mot d'éveil la reprend ;
 * - `idle`   : le repos ordinaire (avant le premier geste, ou après un long silence) — mot d'éveil seulement.
 */
export type ConversationMode = 'idle' | 'active' | 'paused';

/** Le premier « Commencer » a eu lieu sur cet appareil : les ouvertures suivantes démarrent seules si possible. */
export const CONVERSATION_READY_KEY = 'cg.jarvis.conversation.ready';
/** Le réglage local de la durée de silence qui clôt la conversation (ms), borné. */
export const CONVERSATION_IDLE_MS_KEY = 'cg.jarvis.conversationIdleMs';
export const CONVERSATION_IDLE_MS = 60_000;
export const MIN_CONVERSATION_IDLE_MS = 15_000;
export const MAX_CONVERSATION_IDLE_MS = 600_000;
/** Le délai avant de rouvrir l'écoute : le temps que la voix se taise et que le micro se libère. */
export const RELISTEN_DELAY_MS = 250;

/**
 * F-233 / SF-233-16 — **le chien de garde de la boucle** (audit du 2026-11-07) : toutes les 3 s, la conversation active
 * qui ne fait rien (ni écoute, ni tour, ni réécoute armée) depuis deux passages réécoute ; une ouverture d'écoute figée
 * est abandonnée ; une écoute sans micro est close ; le compagnon reçoit un signe de vie. 0 : éteint (tests).
 */
export const JARVIS_LOOP_WATCHDOG_MS = new InjectionToken<number>('JARVIS_LOOP_WATCHDOG_MS', {
  providedIn: 'root',
  factory: () => 3_000,
});
/** Une ouverture d'écoute plus longue est abandonnée (chaque étape a déjà sa borne, celle-ci est le filet). */
export const LISTEN_OPEN_LIMIT_MS = 15_000;
/** Le micro refusé est réessayé une fois après ce délai (une capture précédente se libère). */
export const MIC_RETRY_MS = 700;
/** La libération du micro de la détection de voix est attendue au plus autant avant de rouvrir l'écoute. */
export const VAD_RELEASE_WAIT_MS = 800;
/** Ce que Jarvis dit quand le micro reste fermé : la pause n'est plus muette. */
export const MIC_PAUSE_PHRASE = 'Je n’arrive pas à ouvrir le micro : je me mets en pause. Dites « Jarvis » pour reprendre.';

/** Ce que Jarvis dit en se mettant en pause, et en retournant au repos. */
export const PAUSE_PHRASE = 'Je me mets en pause.';
export const IDLE_PHRASE = 'Je me mets en veille.';
/** F-251 / SF-251-03 — une écoute rejetée alors que Jarvis a entendu quelqu'un : il le dit. */
export const PARDON_PHRASE = 'Pardon, Monsieur ?';
/** Au moins autant de voix entendue au micro pour dire « Pardon » (ms). */
export const PARDON_MIN_VOICED_MS = 400;
/** Au plus un « Pardon » par intervalle (ms). */
export const PARDON_MIN_INTERVAL_MS = 30_000;
export const IDLE_WAKE_HINT = ' Dites « Jarvis » pour reprendre.';

/** La durée de silence qui clôt la conversation sur cet appareil : réglée (bornée), sinon 60 s. */
export function conversationIdleMs(store: Pick<Storage, 'getItem'> | null = safeStorage()): number {
  let raw: string | null = null;
  try {
    raw = store?.getItem(CONVERSATION_IDLE_MS_KEY) ?? null;
  } catch {
    raw = null;
  }
  const value = Number(raw);
  if (raw === null || raw.trim() === '' || !Number.isFinite(value)) {
    return CONVERSATION_IDLE_MS;
  }
  return Math.min(MAX_CONVERSATION_IDLE_MS, Math.max(MIN_CONVERSATION_IDLE_MS, Math.round(value)));
}

const PAUSES = /^(pause|fais une pause|faites une pause|mets toi en pause|mettez vous en pause|en pause|veille|en veille|mets toi en veille|mettez vous en veille|va en veille|passe en veille|passez en veille)( s il (te|vous) plait| merci)?$/;

/** « Jarvis, pause », « mets-toi en veille » (dit seul) : l'ordre de mettre la conversation en pause. */
export function isPauseCommand(said: string | null | undefined): boolean {
  const words = normalize(said ?? '').replace(/^(jarvis|ok jarvis)\s+/, '').trim();
  return PAUSES.test(words);
}

const HESITATIONS = new Set(['euh', 'heu', 'hum', 'hmm', 'hm', 'mmh', 'mm', 'mmm', 'ah', 'oh', 'eh', 'bah', 'ben', 'bon',
  'hein', 'pff', 'tss', 'ouh', 'aah', 'ooh']);

/** Un bruit, pas une phrase : vide, une seule lettre, ou seulement des hésitations. Jamais envoyé. */
export function isNoise(said: string | null | undefined): boolean {
  const words = normalize(said ?? '').split(' ').filter(Boolean);
  if (words.length === 0) {
    return true;
  }
  if (words.length === 1 && words[0].length < 2) {
    return true;
  }
  return words.every((w) => HESITATIONS.has(w));
}

export class Conversation {
  readonly mode = signal<ConversationMode>('idle');
  private lastActivity = 0;

  constructor(private readonly idleMs: () => number = () => conversationIdleMs(),
              private readonly now: () => number = () => Date.now()) {}

  get active(): boolean {
    return this.mode() === 'active';
  }

  /** Démarre (ou reprend) la conversation : le silence se compte depuis maintenant. */
  start(): void {
    this.mode.set('active');
    this.touch();
  }

  pause(): void {
    this.mode.set('paused');
  }

  /** Retour au repos ordinaire (mot d'éveil seulement). */
  rest(): void {
    this.mode.set('idle');
  }

  /** Une demande a été dite, ou Jarvis a fini de parler : le silence repart de zéro. */
  touch(): void {
    this.lastActivity = this.now();
  }

  /** Vrai quand la conversation active n'a rien entendu depuis la durée réglée. */
  idleExpired(): boolean {
    return this.active && this.now() - this.lastActivity >= this.idleMs();
  }

  /** Faut-il rouvrir l'écoute ? Seulement en conversation, au repos, sans tour en cours. */
  shouldRelisten(state: string, turnRunning: boolean): boolean {
    return this.active && state === 'repos' && !turnRunning;
  }
}

/** La conversation peut-elle démarrer seule à l'ouverture ? (premier geste déjà fait sur l'appareil et sur la page) */
export function canAutoStart(store: Pick<Storage, 'getItem'> | null = safeStorage(),
                             nav: { userActivation?: { hasBeenActive: boolean } } | null =
                               typeof navigator === 'undefined' ? null : navigator as never): boolean {
  let ready = false;
  try {
    ready = store?.getItem(CONVERSATION_READY_KEY) === '1';
  } catch {
    ready = false;
  }
  return ready && nav?.userActivation?.hasBeenActive === true;
}

/** Retient sur l'appareil que le premier geste a eu lieu. */
export function rememberReady(store: Pick<Storage, 'setItem'> | null = safeStorage()): void {
  try {
    store?.setItem(CONVERSATION_READY_KEY, '1');
  } catch {
    // Stockage indisponible : « Commencer » sera reproposé.
  }
}

function safeStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}
