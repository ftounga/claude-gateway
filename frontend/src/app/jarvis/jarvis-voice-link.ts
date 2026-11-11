/**
 * **Le lien vocal en flux** (F-257 / SF-257-03, ADR-021) — un WebSocket unique, par conversation, vers le service
 * `jarvis-voice` : l'audio du micro monte en continu (PCM 16 kHz mono 16 bits), les événements et l'audio de Jarvis
 * descendent. Le ticket de session vocale est émis par la passerelle et passe au **premier message** (`hello`),
 * jamais dans l'URL.
 *
 * Reprise : une coupure réseau (ou un déploiement du service) rouvre le lien avec un délai croissant ; un ticket
 * refusé (4401) ou expiré en redemande un. Rien n'est gardé de ce qui est dit : le lien ne journalise que des codes et
 * des durées.
 */

import { JarvisVoiceTicket } from '../core/models/jarvis.models';

/** Le ticket rendu par `POST /api/jarvis/voice-session`. */
export type VoiceTicket = JarvisVoiceTicket;

/** Les codes de fermeture du service (`jarvis-voice/app/main.py`). */
export const CLOSE_BAD_HELLO = 4400;
export const CLOSE_TICKET_REJECTED = 4401;
export const CLOSE_FULL = 4429;
export const CLOSE_GATEWAY_DOWN = 4503;

/** Délais de reprise (ms), puis le dernier en boucle. */
export const RECONNECT_DELAYS_MS = [250, 500, 1_000, 2_000, 5_000];
/** Au-delà, on cesse de reprendre : l'écran reste sur l'ancienne boucle. */
export const MAX_RECONNECTS = 8;
/** Le service doit répondre `ready` dans ce délai. */
export const READY_TIMEOUT_MS = 5_000;

/** L'état du lien. */
export type VoiceLinkState = 'idle' | 'connecting' | 'ready' | 'reconnecting' | 'closed';

/** Ce que le lien rapporte (journal d'écoute, mesure) : jamais de contenu. */
export interface VoiceLinkHandlers {
  onState?(state: VoiceLinkState, detail: string): void;
  /** Un événement JSON du service (sauf `ready`/`pong`, traités ici). */
  onEvent?(event: Record<string, unknown>): void;
  /** Un bloc d'audio de Jarvis (SF-257-07). */
  onAudio?(chunk: ArrayBuffer): void;
  /** Aller-retour mesuré d'un `ping` (ms) et décalage d'horloge estimé serveur − page (ms). */
  onPong?(rttMs: number, offsetMs: number): void;
}

/** Ce dont le lien a besoin du monde (injectable pour les tests). */
export interface VoiceLinkDeps {
  ticket(): Promise<VoiceTicket>;
  socket(url: string): WebSocket;
  now(): number;
  wallNow(): number;
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
}

/** L'URL absolue du service à partir de l'adresse rendue (relative : même hôte, `wss` sous HTTPS). */
export function voiceSocketUrl(url: string, location: Pick<Location, 'protocol' | 'host'>): string {
  if (/^wss?:\/\//.test(url)) {
    return url;
  }
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${location.host}${url.startsWith('/') ? '' : '/'}${url}`;
}

/** Le délai avant la `attempt`-ième reprise (1 = première). */
export function reconnectDelay(attempt: number): number {
  return RECONNECT_DELAYS_MS[Math.min(RECONNECT_DELAYS_MS.length - 1, Math.max(0, attempt - 1))];
}

export class VoiceLink {
  private socket: WebSocket | null = null;
  private state: VoiceLinkState = 'idle';
  private attempts = 0;
  private stopped = false;
  private readyTimer: unknown = null;
  private retryTimer: unknown = null;
  private ticket: VoiceTicket | null = null;
  /** Identifiant de session rendu par `ready`. */
  sessionId: string | null = null;
  /** Compteurs sans contenu (mesure). */
  framesSent = 0;
  reconnects = 0;
  readyMs: number | null = null;
  lastCloseCode: number | null = null;

  constructor(private readonly deps: VoiceLinkDeps, private readonly handlers: VoiceLinkHandlers = {}) {}

  get current(): VoiceLinkState {
    return this.state;
  }

  /** Ouvre le lien (sans effet s'il est déjà ouvert ou en cours). */
  async open(): Promise<void> {
    if (this.state === 'connecting' || this.state === 'ready') {
      return;
    }
    this.stopped = false;
    await this.connect();
  }

  /** Envoie un bloc de PCM 16 bits (sans effet hors de l'état `ready`). */
  sendPcm(pcm: Uint8Array): boolean {
    if (this.state !== 'ready' || !this.socket || this.socket.readyState !== WebSocket.OPEN) {
      return false;
    }
    this.socket.send(pcm);
    this.framesSent++;
    return true;
  }

  /** Mesure l'aller-retour et le décalage d'horloge (crochet du banc F-256). */
  ping(): void {
    if (this.state === 'ready' && this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify({ type: 'ping', clientTs: this.deps.wallNow() }));
    }
  }

  /** Ferme le lien pour de bon (fin de conversation). */
  close(): void {
    this.stopped = true;
    this.clearTimers();
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState <= WebSocket.OPEN) {
      socket.close(1000, 'fin');
    }
    this.setState('closed', 'fin');
  }

  private async connect(): Promise<void> {
    this.setState(this.attempts === 0 ? 'connecting' : 'reconnecting', `essai=${this.attempts}`);
    const startedAt = this.deps.now();
    try {
      if (!this.ticket || this.expired(this.ticket)) {
        this.ticket = await this.deps.ticket();
      }
    } catch {
      this.scheduleRetry('ticket');
      return;
    }
    if (this.stopped) {
      return;
    }
    const ticket = this.ticket;
    const socket = this.deps.socket(ticket.url);
    socket.binaryType = 'arraybuffer';
    this.socket = socket;
    socket.onopen = () => socket.send(JSON.stringify({ type: 'hello', ticket: ticket.ticket }));
    socket.onmessage = (message) => this.onMessage(socket, message, startedAt);
    socket.onclose = (event) => this.onClose(socket, event.code);
    this.readyTimer = this.deps.setTimeout(() => {
      if (this.socket === socket && this.state !== 'ready') {
        socket.close(4000, 'delai');
      }
    }, READY_TIMEOUT_MS);
  }

  private onMessage(socket: WebSocket, message: MessageEvent, startedAt: number): void {
    if (this.socket !== socket) {
      return;
    }
    if (typeof message.data !== 'string') {
      this.handlers.onAudio?.(message.data as ArrayBuffer);
      return;
    }
    let event: Record<string, unknown>;
    try {
      event = JSON.parse(message.data);
    } catch {
      return;
    }
    if (event['type'] === 'ready') {
      this.deps.clearTimeout(this.readyTimer);
      this.sessionId = typeof event['sessionId'] === 'string' ? event['sessionId'] : null;
      this.readyMs = Math.round(this.deps.now() - startedAt);
      this.attempts = 0;
      this.setState('ready', `ms=${this.readyMs}`);
    } else if (event['type'] === 'pong') {
      const sent = Number(event['clientTs']);
      const server = Number(event['serverTs']);
      const back = this.deps.wallNow();
      if (Number.isFinite(sent) && Number.isFinite(server)) {
        const rtt = Math.max(0, back - sent);
        this.handlers.onPong?.(rtt, Math.round(server - (sent + rtt / 2)));
      }
    } else {
      this.handlers.onEvent?.(event);
    }
  }

  private onClose(socket: WebSocket, code: number): void {
    if (this.socket !== socket) {
      return;
    }
    this.socket = null;
    this.lastCloseCode = code;
    this.deps.clearTimeout(this.readyTimer);
    if (this.stopped) {
      return;
    }
    if (code === CLOSE_TICKET_REJECTED) {
      this.ticket = null;
    }
    this.scheduleRetry(`code=${code}`);
  }

  private scheduleRetry(reason: string): void {
    if (this.stopped) {
      return;
    }
    this.attempts++;
    if (this.attempts > MAX_RECONNECTS) {
      this.stopped = true;
      this.setState('closed', `abandon ${reason}`);
      return;
    }
    this.reconnects++;
    this.setState('reconnecting', reason);
    this.retryTimer = this.deps.setTimeout(() => void this.connect(), reconnectDelay(this.attempts));
  }

  private expired(ticket: VoiceTicket): boolean {
    const at = Date.parse(ticket.expiresAt);
    return Number.isFinite(at) && at - this.deps.wallNow() < 30_000;
  }

  private clearTimers(): void {
    this.deps.clearTimeout(this.readyTimer);
    this.deps.clearTimeout(this.retryTimer);
  }

  private setState(state: VoiceLinkState, detail: string): void {
    this.state = state;
    this.handlers.onState?.(state, detail);
  }
}
