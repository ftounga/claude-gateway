import {
  CLOSE_TICKET_REJECTED, MAX_RECONNECTS, VoiceLink, VoiceLinkDeps, VoiceLinkState, VoiceTicket, reconnectDelay,
  voiceSocketUrl,
} from './jarvis-voice-link';

/** Un WebSocket simulé : ce que le lien envoie, et des gestes du serveur. */
class FakeSocket {
  readyState: number = WebSocket.CONNECTING;
  binaryType = '';
  sent: (string | Uint8Array)[] = [];
  closedWith: number | null = null;
  onopen: (() => void) | null = null;
  onmessage: ((m: { data: unknown }) => void) | null = null;
  onclose: ((e: { code: number }) => void) | null = null;

  constructor(readonly url: string) {}

  send(data: string | Uint8Array): void {
    this.sent.push(data);
  }
  close(code = 1000): void {
    this.closedWith = code;
    this.serverClose(code);
  }
  open(): void {
    this.readyState = WebSocket.OPEN;
    this.onopen?.();
  }
  message(data: unknown): void {
    this.onmessage?.({ data });
  }
  serverClose(code: number): void {
    this.readyState = WebSocket.CLOSED;
    this.onclose?.({ code });
  }
}

function harness(ticketFails = false) {
  const sockets: FakeSocket[] = [];
  const timers: { fn: () => void; ms: number; cleared: boolean }[] = [];
  const states: string[] = [];
  let tickets = 0;
  let now = 0;
  const deps: VoiceLinkDeps = {
    ticket: async () => {
      tickets++;
      if (ticketFails) {
        throw new Error('503');
      }
      return { ticket: `t${tickets}`, url: 'wss://h/voice/ws', expiresAt: '2999-01-01T00:00:00Z', sampleRate: 16000 };
    },
    socket: (url) => {
      const s = new FakeSocket(url);
      sockets.push(s);
      return s as unknown as WebSocket;
    },
    now: () => now,
    wallNow: () => 1_000_000 + now,
    setTimeout: (fn, ms) => {
      const t = { fn, ms, cleared: false };
      timers.push(t);
      return t;
    },
    clearTimeout: (h) => {
      if (h) {
        (h as { cleared: boolean }).cleared = true;
      }
    },
  };
  const pongs: [number, number][] = [];
  const events: Record<string, unknown>[] = [];
  const link = new VoiceLink(deps, {
    onState: (s: VoiceLinkState, d: string) => states.push(`${s}:${d}`),
    onPong: (rtt, offset) => pongs.push([rtt, offset]),
    onEvent: (e) => events.push(e),
  });
  return {
    link, sockets, timers, states, pongs, events,
    tickets: () => tickets,
    advance: (ms: number) => { now += ms; },
    fire: () => { const t = timers.filter((x) => !x.cleared).pop(); t!.cleared = true; t!.fn(); },
  };
}

describe('VoiceLink (F-257 / SF-257-03)', () => {
  it('envoie le ticket au premier message (jamais dans l’URL), puis passe prêt et envoie le PCM', async () => {
    const h = harness();
    await h.link.open();
    const s = h.sockets[0];
    expect(s.url).toBe('wss://h/voice/ws');
    expect(s.url).not.toContain('t1');
    s.open();
    expect(JSON.parse(s.sent[0] as string)).toEqual({ type: 'hello', ticket: 't1' });
    expect(h.link.sendPcm(new Uint8Array(4))).toBeFalse();
    h.advance(120);
    s.message(JSON.stringify({ type: 'ready', sessionId: 'abc', serverTs: 1 }));
    expect(h.link.current).toBe('ready');
    expect(h.link.readyMs).toBe(120);
    expect(h.link.sessionId).toBe('abc');
    expect(h.link.sendPcm(new Uint8Array(4))).toBeTrue();
    expect(h.link.framesSent).toBe(1);
  });

  it('mesure l’aller-retour et le décalage d’horloge au pong', async () => {
    const h = harness();
    await h.link.open();
    const s = h.sockets[0];
    s.open();
    s.message(JSON.stringify({ type: 'ready', sessionId: 'a' }));
    h.link.ping();
    const ping = JSON.parse(s.sent[1] as string);
    expect(ping.type).toBe('ping');
    h.advance(40);
    s.message(JSON.stringify({ type: 'pong', clientTs: ping.clientTs, serverTs: ping.clientTs + 520 }));
    expect(h.pongs).toEqual([[40, 500]]);
  });

  it('reprend après une coupure, avec un nouveau socket et le même ticket', async () => {
    const h = harness();
    await h.link.open();
    h.sockets[0].open();
    h.sockets[0].message(JSON.stringify({ type: 'ready', sessionId: 'a' }));
    h.sockets[0].serverClose(1006);
    expect(h.link.current).toBe('reconnecting');
    expect(h.link.lastCloseCode).toBe(1006);
    h.fire();
    await Promise.resolve();
    expect(h.sockets.length).toBe(2);
    expect(h.tickets()).toBe(1);
    expect(h.link.reconnects).toBe(1);
  });

  it('un ticket refusé (4401) en redemande un', async () => {
    const h = harness();
    await h.link.open();
    h.sockets[0].open();
    h.sockets[0].serverClose(CLOSE_TICKET_REJECTED);
    h.fire();
    await Promise.resolve();
    await Promise.resolve();
    expect(h.tickets()).toBe(2);
  });

  it('abandonne après le plafond de reprises', async () => {
    const h = harness(true);
    await h.link.open();
    for (let i = 0; i < MAX_RECONNECTS; i++) {
      h.fire();
      await Promise.resolve();
      await Promise.resolve();
    }
    expect(h.link.current).toBe('closed');
    expect(h.states[h.states.length - 1]).toContain('abandon');
  });

  it('close() ferme pour de bon : aucune reprise', async () => {
    const h = harness();
    await h.link.open();
    h.sockets[0].open();
    h.link.close();
    expect(h.sockets[0].closedWith).toBe(1000);
    expect(h.link.current).toBe('closed');
    expect(h.timers.filter((t) => !t.cleared).length).toBe(0);
  });

  it('les événements du service sont remontés ; l’audio aussi', async () => {
    const audio: ArrayBuffer[] = [];
    const h = harness();
    const link = new VoiceLink((h.link as unknown as { deps: VoiceLinkDeps }).deps, {
      onEvent: (e) => h.events.push(e), onAudio: (a) => audio.push(a),
    });
    await link.open();
    const s = h.sockets[h.sockets.length - 1];
    s.open();
    s.message(JSON.stringify({ type: 'ready', sessionId: 'b' }));
    s.message(JSON.stringify({ type: 'turn', state: 'candidate' }));
    s.message(new ArrayBuffer(8));
    expect(h.events).toEqual([{ type: 'turn', state: 'candidate' }]);
    expect(audio.length).toBe(1);
  });

  it('URL : relative ⇒ même hôte, wss sous https ; absolue gardée ; délais croissants', () => {
    expect(voiceSocketUrl('/voice/ws', { protocol: 'https:', host: 'p.example' })).toBe('wss://p.example/voice/ws');
    expect(voiceSocketUrl('/voice/ws', { protocol: 'http:', host: 'localhost:4200' })).toBe('ws://localhost:4200/voice/ws');
    expect(voiceSocketUrl('wss://x/voice/ws', { protocol: 'https:', host: 'p' })).toBe('wss://x/voice/ws');
    expect(reconnectDelay(1)).toBe(250);
    expect(reconnectDelay(99)).toBe(5000);
  });
});

export type { VoiceTicket };
