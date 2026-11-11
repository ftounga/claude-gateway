import {
  JarvisVoiceShadow, PCM_CHUNK_BYTES, PING_EVERY_MS, PcmChunker, VoiceShadowDeps, readVoiceLoop, serviceEventTrace,
  writeVoiceLoop,
} from './jarvis-voice-shadow';
import { VoiceLink, VoiceLinkHandlers, VoiceTicket } from './jarvis-voice-link';

const TICKET: VoiceTicket = { ticket: 't', url: '/voice/ws', expiresAt: '2999-01-01T00:00:00Z', sampleRate: 16000 };

function harness(opts: { ticketError?: unknown; micError?: boolean } = {}) {
  const traces: string[] = [];
  const asked: (string | null)[] = [];
  const intervals: { fn: () => void; ms: number; cleared: boolean }[] = [];
  const link = jasmine.createSpyObj<VoiceLink>('VoiceLink', ['open', 'close', 'ping', 'sendPcm']);
  link.open.and.resolveTo();
  let linkTicket: (() => Promise<VoiceTicket>) | null = null;
  let linkHandlers: VoiceLinkHandlers | null = null;
  let now = 0;
  let onPcm: ((pcm: Uint8Array) => void) | null = null;
  const mic = { close: jasmine.createSpy('close') };
  const deps: VoiceShadowDeps = {
    ticket: async (ws) => {
      asked.push(ws);
      if (opts.ticketError) {
        throw opts.ticketError;
      }
      return TICKET;
    },
    openMic: async (fn) => {
      if (opts.micError) {
        throw new Error('NotAllowedError');
      }
      onPcm = fn;
      return mic;
    },
    link: (source, handlers) => {
      linkTicket = source.ticket;
      linkHandlers = handlers;
      return link;
    },
    now: () => now,
    trace: (d) => traces.push(d),
    setInterval: (fn, ms) => {
      const i = { fn, ms, cleared: false };
      intervals.push(i);
      return i;
    },
    clearInterval: (h) => {
      if (h) {
        (h as { cleared: boolean }).cleared = true;
      }
    },
  };
  return {
    shadow: new JarvisVoiceShadow(deps), link, mic, traces, asked, intervals,
    linkTicket: () => linkTicket!, pcm: (b: Uint8Array) => onPcm!(b), handlers: () => linkHandlers!,
    at: (t: number) => { now = t; },
  };
}

describe('JarvisVoiceShadow (F-257 / SF-257-03)', () => {
  it('legacy : aucun ticket, aucun lien', async () => {
    const h = harness();
    await h.shadow.start('legacy', 'ws-1');
    expect(h.asked).toEqual([]);
    expect(h.shadow.active).toBeFalse();
  });

  it('stream : ticket du sujet, lien ouvert, micro branché sur le lien, ping régulier', async () => {
    const h = harness();
    await h.shadow.start('stream', 'ws-1');
    expect(h.asked).toEqual(['ws-1']);
    expect(h.link.open).toHaveBeenCalledTimes(1);
    expect(h.shadow.active).toBeTrue();
    h.pcm(new Uint8Array(2));
    expect(h.link.sendPcm).toHaveBeenCalledTimes(1);
    expect(h.intervals[0].ms).toBe(PING_EVERY_MS);
    h.intervals[0].fn();
    expect(h.link.ping).toHaveBeenCalled();
    // Le premier ticket sert à la première ouverture ; une reprise en redemande un.
    expect(await h.linkTicket()()).toBe(TICKET);
    await h.linkTicket()();
    expect(h.asked).toEqual(['ws-1', 'ws-1']);
    // Un second démarrage pendant la conversation n'ouvre rien de plus.
    await h.shadow.start('stream', 'ws-1');
    expect(h.link.open).toHaveBeenCalledTimes(1);
  });

  it('503 (coupe-circuit fermé) : rien n’est ouvert, tracé « off »', async () => {
    const h = harness({ ticketError: { status: 503 } });
    await h.shadow.start('stream', null);
    expect(h.shadow.active).toBeFalse();
    expect(h.traces).toEqual(['closed off']);
  });

  it('micro refusé : lien fermé, tracé', async () => {
    const h = harness({ micError: true });
    await h.shadow.start('stream', null);
    expect(h.shadow.active).toBeFalse();
    expect(h.link.close).toHaveBeenCalled();
    expect(h.traces).toContain('closed micro');
  });

  it('stop() rend le micro, ferme le lien et arrête le ping', async () => {
    const h = harness();
    await h.shadow.start('stream', null);
    h.shadow.stop();
    expect(h.mic.close).toHaveBeenCalled();
    expect(h.link.close).toHaveBeenCalled();
    expect(h.intervals[0].cleared).toBeTrue();
    expect(h.shadow.active).toBeFalse();
  });

  it('le réglage par appareil : legacy par défaut, stream mémorisé', () => {
    const store = new Map<string, string>();
    const storage = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => store.set(k, v) };
    expect(readVoiceLoop(storage)).toBe('legacy');
    writeVoiceLoop(storage, 'stream');
    expect(readVoiceLoop(storage)).toBe('stream');
    expect(readVoiceLoop(null)).toBe('legacy');
  });

  it('regroupe les blocs de 2,7 ms en envois de ≈ 100 ms, dans l’ordre (pas de rafale chez le fournisseur)', () => {
    const sent: Uint8Array[] = [];
    const chunker = new PcmChunker(PCM_CHUNK_BYTES, (pcm) => sent.push(pcm));
    for (let i = 0; i < 12; i++) {
      chunker.push(new Uint8Array(256).fill(i));
    }
    expect(sent.length).toBe(0);
    chunker.push(new Uint8Array(256).fill(12));
    expect(sent.length).toBe(1);
    expect(sent[0].length).toBe(3328);
    expect(sent[0][0]).toBe(0);
    expect(sent[0][3327]).toBe(12);
    chunker.push(new Uint8Array(10));
    chunker.flush();
    expect(sent.length).toBe(2);
    expect(sent[1].length).toBe(10);
    chunker.flush();
    expect(sent.length).toBe(2);
  });

  it('F-257 / SF-257-05 — journalise le tour et l’écoute du service, jamais le texte', () => {
    expect(serviceEventTrace({ type: 'turn', state: 'candidate', by: 'modele', silenceMs: 100, speechMs: 1300,
      probability: 0.98, serverTs: 42 })).toBe('turn candidate by=modele silence=100 speech=1300 p=0.98 srv=42');
    const confirmed = serviceEventTrace({ type: 'turn', state: 'confirmed', text: 'Montre-moi CAGIP', by: 'modele',
      via: 'final', finalMs: 210 })!;
    expect(confirmed).toBe('turn confirmed by=modele via=final final=210');
    expect(confirmed).not.toContain('CAGIP');
    expect(serviceEventTrace({ type: 'stt', state: 'reopen', detail: 'session=2' })).toBe('stt reopen session=2');
    expect(serviceEventTrace({ type: 'transcript', final: true, text: 'secret' })).toBeNull();
    expect(serviceEventTrace({ type: 'turn', state: 'speech', via: 'vad <script>' })).toBe('turn speech via=vadscript');
  });

  it('F-257 / SF-257-06 — prend la main quand le lien est prêt, rend la main s’il tombe ; les tours remontent', async () => {
    const h = harness();
    const changes: [boolean, string][] = [];
    const turns: Record<string, unknown>[] = [];
    await h.shadow.start('stream', null, { onReadyChange: (r, d) => changes.push([r, d]), onTurn: (e) => turns.push(e) });
    expect(h.shadow.ready).toBeFalse();
    h.handlers().onState!('ready', 'ms=80');
    expect(h.shadow.ready).toBeTrue();
    h.handlers().onState!('ready', 'ms=80'); // pas de doublon
    h.handlers().onEvent!({ type: 'turn', state: 'confirmed', text: 'Bonjour' });
    h.handlers().onEvent!({ type: 'stt', state: 'reopen' });
    h.handlers().onState!('reconnecting', 'code=1006');
    expect(h.shadow.ready).toBeFalse();
    expect(changes).toEqual([[true, 'ready ms=80'], [false, 'reconnecting code=1006']]);
    expect(turns).toEqual([{ type: 'turn', state: 'confirmed', text: 'Bonjour' }]);
    // Une fermeture voulue (fin de conversation) n'annonce pas de repli.
    h.shadow.stop();
    expect(changes.length).toBe(2);
  });

  it('F-257 / SF-257-06 — le micro du lien envoie du silence quand Jarvis parle, et 150 ms après', async () => {
    const h = harness();
    let speaking = false;
    await h.shadow.start('stream', null, { silenced: () => speaking });
    const voice = new Uint8Array([1, 2, 3, 4]);
    h.pcm(voice);
    expect(h.link.sendPcm.calls.mostRecent().args[0]).toBe(voice);
    speaking = true;
    h.at(1000);
    h.pcm(voice);
    expect(Array.from(h.link.sendPcm.calls.mostRecent().args[0])).toEqual([0, 0, 0, 0]);
    speaking = false;
    h.at(1100);
    h.pcm(voice);
    expect(Array.from(h.link.sendPcm.calls.mostRecent().args[0])).toEqual([0, 0, 0, 0]);
    h.at(1151);
    h.pcm(voice);
    expect(h.link.sendPcm.calls.mostRecent().args[0]).toBe(voice);
  });
});
