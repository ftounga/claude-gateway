/**
 * **La boucle en flux, en ombre** (F-257 / SF-257-03) — réglée par appareil (`cg.jarvis.loop`), elle tient le lien
 * vocal pendant la conversation et y envoie le micro en continu. L'ancienne boucle reste seule à décider et à répondre
 * tant que le service ne prend pas le tour (SF-257-04 →). Rien n'est ouvert en `legacy` (défaut) ni si la passerelle
 * refuse (coupe-circuit, 503).
 */

import { downsample, LISTEN_AUDIO, openCaptureGraph, PRE_ROLL_RATE, toPcm16 } from './jarvis-stream-listener';
import { VoiceLink, VoiceLinkHandlers, VoiceLinkState, VoiceTicket, voiceSocketUrl } from './jarvis-voice-link';

/** Le réglage par appareil. */
export const VOICE_LOOP_KEY = 'cg.jarvis.loop';
export type VoiceLoop = 'legacy' | 'stream';
/** Un `ping` toutes les… (mesure de l'aller-retour et du décalage d'horloge). */
export const PING_EVERY_MS = 10_000;

export function readVoiceLoop(storage: Pick<Storage, 'getItem'> | null): VoiceLoop {
  try {
    return storage?.getItem(VOICE_LOOP_KEY) === 'stream' ? 'stream' : 'legacy';
  } catch {
    return 'legacy';
  }
}

export function writeVoiceLoop(storage: Pick<Storage, 'setItem'> | null, loop: VoiceLoop): void {
  try {
    storage?.setItem(VOICE_LOOP_KEY, loop);
  } catch {
    // Stockage indisponible : le réglage vaut pour cette ouverture.
  }
}

/** Le micro du lien : rendu par `close()`. */
export interface LinkMic {
  close(): void;
}

/** Ce dont l'ombre a besoin (injectable pour les tests). */
export interface VoiceShadowDeps {
  ticket(workspaceId: string | null): Promise<VoiceTicket>;
  /** Ouvre le micro et appelle `onPcm` avec des blocs PCM 16 bits à 16 kHz. */
  openMic(onPcm: (pcm: Uint8Array) => void): Promise<LinkMic>;
  link(deps: { ticket: () => Promise<VoiceTicket> }, handlers: VoiceLinkHandlers): VoiceLink;
  trace(detail: string): void;
  setInterval(fn: () => void, ms: number): unknown;
  clearInterval(handle: unknown): void;
}

export class JarvisVoiceShadow {
  private link: VoiceLink | null = null;
  private mic: LinkMic | null = null;
  private pinger: unknown = null;
  private generation = 0;

  constructor(private readonly deps: VoiceShadowDeps) {}

  get active(): boolean {
    return this.link !== null;
  }

  /** Ouvre l'ombre pour la conversation (sans effet en `legacy` ou si elle est déjà ouverte). */
  async start(loop: VoiceLoop, workspaceId: string | null): Promise<void> {
    if (loop !== 'stream' || this.link) {
      return;
    }
    const generation = ++this.generation;
    let first: VoiceTicket;
    try {
      first = await this.deps.ticket(workspaceId);
    } catch (error) {
      this.deps.trace(`closed ${statusOf(error) === 503 ? 'off' : 'ticket'}`);
      return;
    }
    if (generation !== this.generation) {
      return;
    }
    let pending: VoiceTicket | null = first;
    const link = this.deps.link({
      ticket: async () => {
        const t = pending ?? await this.deps.ticket(workspaceId);
        pending = null;
        return t;
      },
    }, {
      onState: (state: VoiceLinkState, detail: string) => this.deps.trace(`${state} ${detail}`.trim()),
    });
    this.link = link;
    try {
      this.mic = await this.deps.openMic((pcm) => link.sendPcm(pcm));
    } catch {
      this.deps.trace('closed micro');
      this.stop();
      return;
    }
    if (generation !== this.generation) {
      this.mic?.close();
      this.mic = null;
      return;
    }
    await link.open();
    this.pinger = this.deps.setInterval(() => link.ping(), PING_EVERY_MS);
  }

  /** Ferme l'ombre (pause, fin de conversation, sortie de l'écran). */
  stop(): void {
    this.generation++;
    this.deps.clearInterval(this.pinger);
    this.pinger = null;
    this.mic?.close();
    this.mic = null;
    this.link?.close();
    this.link = null;
  }
}

function statusOf(error: unknown): number | null {
  const status = (error as { status?: unknown } | null)?.status;
  return typeof status === 'number' ? status : null;
}

/**
 * Un envoi tous les ≈ 100 ms (3 200 octets à 16 kHz) : le module de capture rend des blocs de 128 échantillons
 * (2,7 ms) ; les envoyer un à un, en rafale quand le fil principal est occupé, a fait fermer les sessions du fournisseur
 * (`queue_overflow`, référence du banc F-256 §7).
 */
export const PCM_CHUNK_BYTES = 3_200;

/** Regroupe des blocs PCM en envois d'au moins `size` octets, dans l'ordre ; `flush()` rend le reste. */
export class PcmChunker {
  private parts: Uint8Array[] = [];
  private bytes = 0;

  constructor(private readonly size: number, private readonly emit: (pcm: Uint8Array) => void) {}

  push(pcm: Uint8Array): void {
    this.parts.push(pcm);
    this.bytes += pcm.length;
    if (this.bytes >= this.size) {
      this.flush();
    }
  }

  flush(): void {
    if (this.bytes === 0) {
      return;
    }
    const out = new Uint8Array(this.bytes);
    let at = 0;
    for (const part of this.parts) {
      out.set(part, at);
      at += part.length;
    }
    this.parts = [];
    this.bytes = 0;
    this.emit(out);
  }
}

/** Le micro réel du lien : contraintes d'écho de l'écoute, capture existante, 16 kHz PCM 16 bits par ≈ 100 ms. */
export async function openLinkMic(onPcm: (pcm: Uint8Array) => void): Promise<LinkMic> {
  const stream = await navigator.mediaDevices.getUserMedia({ audio: LISTEN_AUDIO });
  const chunker = new PcmChunker(PCM_CHUNK_BYTES, onPcm);
  try {
    const opened = await openCaptureGraph(stream,
      (samples, rate) => chunker.push(toPcm16(downsample(samples, rate, PRE_ROLL_RATE))));
    return {
      close: () => {
        opened.source.disconnect();
        opened.node.disconnect();
        void opened.context.close().catch(() => undefined);
        if (opened.tapUrl) {
          URL.revokeObjectURL(opened.tapUrl);
        }
        stream.getTracks().forEach((track) => track.stop());
      },
    };
  } catch (error) {
    stream.getTracks().forEach((track) => track.stop());
    throw error;
  }
}

/** Le vrai lien, sur le vrai WebSocket. */
export function realVoiceLink(source: { ticket: () => Promise<VoiceTicket> }, handlers: VoiceLinkHandlers): VoiceLink {
  return new VoiceLink({
    ticket: source.ticket,
    socket: (url) => new WebSocket(voiceSocketUrl(url, window.location)),
    now: () => performance.now(),
    wallNow: () => Date.now(),
    setTimeout: (fn, ms) => window.setTimeout(fn, ms),
    clearTimeout: (handle) => window.clearTimeout(handle as number),
  }, handlers);
}
