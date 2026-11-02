import type { Ambient } from '../../scenes/jarvis-scene-bus.service';
import { ScreenSignals } from '../jarvis-stage-director';
import type { Piece } from '../../pieces/jarvis-piece.models';

/**
 * F-236 / SF-236-24 — **la vue cockpit sur un écran externe** (NF-12, `?cockpit=1`). Fichier <b>pur</b> (le canal est
 * injecté).
 *
 * <p>L'écran principal (le « meneur ») relaie ce qu'il passe à sa scène à une seconde fenêtre (le « suiveur ») par un
 * `BroadcastChannel` du navigateur : <b>aucun réseau</b>, même origine, même appareil. Le suiveur annonce sa venue
 * (`hello`) ; le meneur ne publie qu'en présence d'un suiveur, et lui renvoie aussitôt l'état courant. Chacun annonce
 * son départ (`bye`) : l'autre continue sans erreur. Un meneur qui arrive après le suiveur s'annonce (`leader`).</p>
 */

export const COCKPIT_CHANNEL = 'jarvis-cockpit';

/** Le canal (le `BroadcastChannel` du navigateur, ou une doublure de test). */
export interface LinkChannel {
  postMessage(message: unknown): void;
  close(): void;
  onmessage: ((event: { data: unknown }) => void) | null;
}

/** L'ambiance sur le fil : l'ensemble des sujets vivants devient une liste (clonage structuré, JSON). */
type WireAmbient = Omit<Ambient, 'liveSubjects'> & { liveSubjects: string[] };
export type WireSignals = Omit<ScreenSignals, 'ambient'> & { ambient: WireAmbient | null };

export type LinkMessage =
  | { type: 'hello' }
  | { type: 'leader' }
  | { type: 'bye'; from: 'leader' | 'follower' }
  | { type: 'signals'; leader: string; seq: number; sentAt: number; signals: WireSignals }
  /** F-237 / SF-237-12 — la pièce épinglée au cockpit (`null` : désépinglée). Objet clonable, aucun binaire. */
  | { type: 'piece'; leader: string; piece: Piece | null };

export function encodeSignals(s: ScreenSignals): WireSignals {
  return { ...s, ambient: s.ambient ? { ...s.ambient, liveSubjects: [...s.ambient.liveSubjects] } : null };
}

export function decodeSignals(w: WireSignals): ScreenSignals {
  return { ...w, ambient: w.ambient ? { ...w.ambient, liveSubjects: new Set(w.ambient.liveSubjects) } : null };
}

function isMessage(data: unknown): data is LinkMessage {
  const type = (data as { type?: unknown } | null)?.type;
  return type === 'hello' || type === 'leader' || type === 'bye' || type === 'signals' || type === 'piece';
}

/** Le meneur : publie l'état de sa scène tant qu'un suiveur écoute. */
export class CockpitLeader {
  private followers = false;
  private seq = 0;
  private last: ScreenSignals | null = null;
  private pinned: Piece | null = null;
  /** Deux écrans principaux ouverts : le suiveur suit celui qui a parlé en dernier. */
  private readonly id = Math.random().toString(36).slice(2);

  constructor(private readonly channel: LinkChannel, private readonly now: () => number = () => Date.now()) {
    channel.onmessage = (event) => {
      const m = event.data;
      if (!isMessage(m)) {
        return;
      }
      if (m.type === 'hello') {
        this.followers = true;
        if (this.last) {
          this.send(this.last);
        }
        if (this.pinned) {
          this.sendPiece();
        }
      } else if (m.type === 'bye' && m.from === 'follower') {
        this.followers = false;
      }
    };
    // Un écran principal ouvert (ou rechargé) après la vue cockpit s'annonce : elle se présente à nouveau.
    try {
      channel.postMessage({ type: 'leader' });
    } catch {
      // canal indisponible
    }
  }

  get listening(): boolean {
    return this.followers;
  }

  /** L'état de la scène a changé : relayé s'il y a un suiveur. */
  publish(s: ScreenSignals): void {
    this.last = s;
    if (this.followers) {
      this.send(s);
    }
  }

  /** F-237 / SF-237-12 — épingle une pièce au cockpit (ou la retire) ; rend vrai si un cockpit écoute. */
  pin(piece: Piece | null): boolean {
    this.pinned = piece;
    if (this.followers) {
      this.sendPiece();
    }
    return this.followers;
  }

  private sendPiece(): void {
    try {
      this.channel.postMessage({ type: 'piece', leader: this.id, piece: this.pinned });
    } catch {
      // canal fermé
    }
  }

  private send(s: ScreenSignals): void {
    try {
      this.channel.postMessage({ type: 'signals', leader: this.id, seq: ++this.seq, sentAt: this.now(), signals: encodeSignals(s) });
    } catch {
      // canal fermé ou donnée non clonable : le suiveur garde sa dernière image
    }
  }

  close(): void {
    try {
      this.channel.postMessage({ type: 'bye', from: 'leader' });
    } catch {
      // déjà fermé
    }
    this.channel.onmessage = null;
    this.channel.close();
  }
}

/** Le suiveur : reçoit l'état du meneur ; `null` quand le meneur part. */
export class CockpitFollower {
  private seq = 0;
  private leader: string | null = null;

  constructor(private readonly channel: LinkChannel,
      private readonly onSignals: (signals: ScreenSignals | null, latencyMs: number | null) => void,
      private readonly now: () => number = () => Date.now(),
      private readonly onPiece: (piece: Piece | null) => void = () => undefined) {
    channel.onmessage = (event) => {
      const m = event.data;
      if (!isMessage(m)) {
        return;
      }
      if (m.type === 'signals' && (m.leader !== this.leader || m.seq > this.seq)) {
        this.leader = m.leader;
        this.seq = m.seq;
        this.onSignals(decodeSignals(m.signals), Math.max(0, this.now() - m.sentAt));
      } else if (m.type === 'bye' && m.from === 'leader') {
        this.seq = 0;
        this.leader = null;
        this.onSignals(null, null);
      } else if (m.type === 'leader') {
        this.channel.postMessage({ type: 'hello' });
      } else if (m.type === 'piece') {
        this.onPiece(m.piece);
      }
    };
    channel.postMessage({ type: 'hello' });
  }

  close(): void {
    try {
      this.channel.postMessage({ type: 'bye', from: 'follower' });
    } catch {
      // déjà fermé
    }
    this.channel.onmessage = null;
    this.channel.close();
  }
}

/** Le canal du navigateur, s'il existe. */
export function browserChannel(): LinkChannel | null {
  return typeof BroadcastChannel === 'function' ? new BroadcastChannel(COCKPIT_CHANNEL) as unknown as LinkChannel
    : null;
}

/** Un écran (Window Management API) : sa zone disponible. */
export interface ScreenArea {
  availLeft: number;
  availTop: number;
  availWidth: number;
  availHeight: number;
  isPrimary?: boolean;
}

/**
 * Où ouvrir la fenêtre du cockpit : sur un <b>autre</b> écran que l'écran courant (le premier non principal), plein
 * cadre ; `null` sans second écran (le navigateur choisit).
 */
export function cockpitPlacement(screens: readonly ScreenArea[], current: ScreenArea | null): string | null {
  const other = screens.find((s) => s !== current && !s.isPrimary) ?? screens.find((s) => s !== current) ?? null;
  if (!other) {
    return null;
  }
  return `left=${other.availLeft},top=${other.availTop},width=${other.availWidth},height=${other.availHeight}`;
}
