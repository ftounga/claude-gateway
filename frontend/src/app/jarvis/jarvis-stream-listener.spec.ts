import {
  CaptureGraph, ConversationMic, ECHO_HOLD_MS, JarvisStreamOpener, OutboxTimers, PRE_ROLL_MS, PreRollRing,
  StreamFailure, StreamOpenError, StreamingListener, AudioChunker, PacedOutbox, STT_ERROR_TYPES, failureDetail,
  isTransientFailure, downsample, parseSttMessage, sessionUrl, streamFailureOf, ticketFailureOf,
  toBase64, toPcm16,
} from './jarvis-stream-listener';

/** F-258 / SF-258-01 — des minuteries à la main pour l'envoi lissé. */
function manualTimers(): OutboxTimers & { advance(ms: number): void } {
  let t = 0;
  let next = 0;
  const due: { at: number; fn: () => void; id: number }[] = [];
  return {
    now: () => t,
    set: (fn, ms) => {
      due.push({ at: t + ms, fn, id: ++next });
      return next;
    },
    clear: (handle) => {
      const i = due.findIndex((d) => d.id === handle);
      if (i >= 0) {
        due.splice(i, 1);
      }
    },
    advance(ms: number) {
      const end = t + ms;
      for (;;) {
        due.sort((a, b) => a.at - b.at);
        const first = due[0];
        if (!first || first.at > end) {
          break;
        }
        due.shift();
        t = first.at;
        first.fn();
      }
      t = end;
    },
  };
}

/** Écouter en flux (F-233 / SF-233-05) : les fonctions pures. */
describe('Écoute en flux (F-233 / SF-233-05)', () => {
  it('lit les messages du fournisseur ; l’illisible ne casse rien', () => {
    expect(parseSttMessage('{"message_type":"session_started","session_id":"s"}').kind).toBe('started');
    expect(parseSttMessage('{"message_type":"partial_transcript","text":"Où en"}')).toEqual(
      { kind: 'partial', text: 'Où en' });
    expect(parseSttMessage('{"message_type":"committed_transcript","text":"Où en est-on ?"}')).toEqual(
      { kind: 'committed', text: 'Où en est-on ?' });
    expect(parseSttMessage('{"message_type":"committed_transcript_with_timestamps","text":"x"}').kind)
      .toBe('committed');
    expect(parseSttMessage('{"message_type":"auth_error"}').kind).toBe('error');
    expect(parseSttMessage('pas du json').kind).toBe('other');
    expect(parseSttMessage(new ArrayBuffer(2)).kind).toBe('other');
  });

  it('F-233 / SF-233-13 — un refus du fournisseur (`invalid_request`, champ `error`) est une erreur', () => {
    expect(parseSttMessage('{"message_type":"invalid_request","error":"Number of keyterms cannot exceed 50."}').kind)
      .toBe('error');
    expect(parseSttMessage('{"message_type":"whatever","error":"boom"}').kind).toBe('error');
    expect(parseSttMessage('{"message_type":"whatever","error":""}').kind).toBe('other');
  });

  it('F-233 / SF-233-13 — classe la raison d’un repli hors flux', () => {
    expect(ticketFailureOf(503)).toBe('ticket_indisponible');
    expect(ticketFailureOf(401)).toBe('ticket_refuse');
    expect(ticketFailureOf(403)).toBe('ticket_refuse');
    expect(ticketFailureOf(429)).toBe('ticket_refuse');
    expect(ticketFailureOf(502)).toBe('ticket_echec');
    expect(ticketFailureOf(null)).toBe('ticket_echec');
    expect(streamFailureOf(new StreamOpenError('micro'))).toBe('micro');
    expect(streamFailureOf(new StreamOpenError('capture'))).toBe('capture');
    expect(streamFailureOf(new Error('timeout'))).toBe('ouverture_delai');
    expect(streamFailureOf(new Error('refus'))).toBe('ouverture_refusee');
    expect(streamFailureOf(new Error('reseau'))).toBe('ouverture_reseau');
    expect(streamFailureOf(new Error('ferme'))).toBe('ouverture_fermee');
    expect(streamFailureOf('???')).toBe('autre');
  });

  it('rééchantillonne par moyenne, PCM 16 bits petit-boutiste, base64', () => {
    const input = new Float32Array([1, 1, 1, -1, -1, -1]);
    expect(Array.from(downsample(input, 48_000, 16_000))).toEqual([1, -1]);
    expect(downsample(input, 16_000, 16_000)).toBe(input);
    const pcm = toPcm16(new Float32Array([0, 1, -1, 2]));
    expect(Array.from(pcm)).toEqual([0, 0, 0xff, 0x7f, 0x00, 0x80, 0xff, 0x7f]);
    expect(toBase64(new Uint8Array([77, 97, 110]))).toBe('TWFu');
  });

  it('ajoute le jeton, encodé, à l’URL de session', () => {
    expect(sessionUrl({ url: 'wss://x/realtime?model_id=m', token: 'a b' })).toBe('wss://x/realtime?model_id=m&token=a%20b');
    expect(sessionUrl({ url: 'wss://x/realtime', token: 't' })).toBe('wss://x/realtime?token=t');
  });

  it('F-239 — la pré-écoute part au fournisseur par morceaux de 100 ms ; F-258 — lissée, pas d’un coup', async () => {
    const sent: { audio: string; commit: boolean }[] = [];
    class FakeSocket {
      static OPEN = 1;
      readyState = 1;
      onmessage: ((e: { data: string }) => void) | null = null;
      onerror: (() => void) | null = null;
      onclose: (() => void) | null = null;
      constructor() {
        setTimeout(() => this.onmessage?.({ data: JSON.stringify({ message_type: 'session_started' }) }));
      }
      send(raw: string): void {
        const m = JSON.parse(raw) as { audio_base_64: string; commit: boolean };
        sent.push({ audio: m.audio_base_64, commit: m.commit });
      }
      close(): void {
        this.readyState = 3;
      }
    }
    const original = window.WebSocket;
    (window as unknown as { WebSocket: unknown }).WebSocket = FakeSocket;
    const capture = spyOn(StreamingListener.prototype as unknown as { capture(): Promise<void> }, 'capture')
      .and.callFake(() => {
        sent.push({ audio: 'MICRO', commit: false });
        return Promise.resolve();
      });
    const timers = manualTimers();
    try {
      const listener = new StreamingListener({
        sessionId: 's', provider: 'elevenlabs', url: 'wss://x/realtime', token: 't', expiresAt: '2030-01-01T00:00:00Z',
        sampleRate: 16_000, maxSeconds: 120,
      }, { getTracks: () => [] } as unknown as MediaStream, { onPartial: () => undefined,
        onCommitted: () => undefined, onError: () => undefined }, null, timers);
      await listener.start(() => new Float32Array(4_000).fill(0.1));
      expect(capture).toHaveBeenCalled();
      // 4 000 échantillons à 16 kHz = 250 ms ⇒ trois morceaux (100, 100, 50 ms) : le premier part tout de suite,
      // les suivants à 25 ms d'écart (F-258 / SF-258-01).
      expect(sent.length).toBe(2);
      expect(sent[0].audio).not.toBe('MICRO');
      timers.advance(24);
      expect(sent.length).toBe(2);
      timers.advance(26);
      expect(sent.length).toBe(4);
      expect(sent.filter((m) => m.audio !== 'MICRO' && !m.commit).length).toBe(3);
      // F-239 / SF-239-04 — l'énoncé gardé commence par la pré-écoute : 4 000 échantillons × 2 octets + en-tête WAV.
      expect(listener.audioSeconds()).toBeCloseTo(0.25, 3);
      expect(listener.audio()!.size).toBe(44 + 8_000);
      expect(listener.audio()!.type).toBe('audio/wav');
      listener.close();
    } finally {
      (window as unknown as { WebSocket: unknown }).WebSocket = original;
    }
  });
});

describe('Le micro reste ouvert (F-251 / SF-251-02)', () => {
  const ticket = { sessionId: 's', provider: 'elevenlabs', url: 'wss://x/realtime', token: 't',
    expiresAt: '2030-01-01T00:00:00Z', sampleRate: 16_000, maxSeconds: 120 };
  const handlers = { onPartial: () => undefined, onCommitted: () => undefined, onError: () => undefined };

  function fakeGraph(): CaptureGraph & { closed: boolean } {
    return { rate: 16_000, closed: false, alive() { return !this.closed; }, resume: () => undefined,
      close() { this.closed = true; } };
  }

  function tracks(state = 'live'): MediaStream & { stopped: () => number } {
    let stopped = 0;
    const track = { readyState: state, stop: () => { stopped++; } };
    return { getTracks: () => [track], stopped: () => stopped } as unknown as MediaStream & { stopped: () => number };
  }

  class FakeSocket {
    static OPEN = 1;
    static sent: { audio: string }[] = [];
    readyState = 1;
    onmessage: ((e: { data: string }) => void) | null = null;
    onerror: (() => void) | null = null;
    onclose: (() => void) | null = null;
    constructor() {
      setTimeout(() => this.onmessage?.({ data: JSON.stringify({ message_type: 'session_started' }) }));
    }
    send(raw: string): void {
      FakeSocket.sent.push({ audio: (JSON.parse(raw) as { audio_base_64: string }).audio_base_64 });
    }
    close(): void {
      this.readyState = 3;
    }
  }

  let originalSocket: typeof WebSocket;
  beforeEach(() => {
    FakeSocket.sent = [];
    originalSocket = window.WebSocket;
    (window as unknown as { WebSocket: unknown }).WebSocket = FakeSocket;
  });
  afterEach(() => {
    (window as unknown as { WebSocket: unknown }).WebSocket = originalSocket;
  });

  it('l’anneau oublie les plus anciens au-delà de sa capacité, rend dans l’ordre, repart vide', () => {
    const ring = new PreRollRing(4);
    ring.push(new Float32Array([1, 2]));
    ring.push(new Float32Array([3, 4]));
    ring.push(new Float32Array([5, 6, 7]));
    expect(ring.length).toBe(4);
    expect(Array.from(ring.take())).toEqual([4, 5, 6, 7]);
    expect(ring.length).toBe(0);
    expect(ring.take().length).toBe(0);
  });

  it('le son gardé part à l’attachement ; ensuite le son vivant va à la session, pas au tampon', () => {
    const mic = new ConversationMic(tracks(), fakeGraph(), () => false);
    mic.feed(new Float32Array([0.1, 0.2]));
    const live: number[] = [];
    const preRoll = mic.attach((samples) => live.push(...samples));
    expect(preRoll.length).toBe(2);
    expect(preRoll[1]).toBeCloseTo(0.2, 5);
    mic.feed(new Float32Array([0.3]));
    expect(live.length).toBe(1);
    expect(mic.attach(() => undefined).length).toBe(0);
  });

  it('le tampon garde au plus 1,5 s', () => {
    const mic = new ConversationMic(tracks(), fakeGraph(), () => false);
    for (let i = 0; i < 40; i++) {
      mic.feed(new Float32Array(1_600));
    }
    expect(mic.attach(() => undefined).length).toBe(16_000 * PRE_ROLL_MS / 1000);
  });

  it('anti-écho : tampon vidé pendant la parole de Jarvis et ECHO_HOLD_MS après', () => {
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(0));
    spyOn(performance, 'now').and.callFake(() => Date.now());
    try {
      let speaking = false;
      const mic = new ConversationMic(tracks(), fakeGraph(), () => speaking);
      mic.feed(new Float32Array(100));
      speaking = true;
      mic.feed(new Float32Array(100));
      speaking = false;
      mic.feed(new Float32Array(100));
      expect(mic.attach(() => undefined).length).toBe(0);
      mic.detach();
      jasmine.clock().tick(ECHO_HOLD_MS);
      mic.feed(new Float32Array(100));
      expect(mic.attach(() => undefined).length).toBe(100);
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('détaché, le tampon repart vide ; usé, le micro le dit ; fermé, les pistes s’arrêtent', () => {
    const stream = tracks();
    const graph = fakeGraph();
    const mic = new ConversationMic(stream, graph, () => false);
    mic.feed(new Float32Array(10));
    mic.attach(() => undefined);
    mic.detach();
    expect(mic.attach(() => undefined).length).toBe(0);
    expect(mic.alive).toBeTrue();
    mic.close();
    expect(stream.stopped()).toBe(1);
    expect(graph.closed).toBeTrue();
    expect(mic.alive).toBeFalse();
    expect(new ConversationMic(tracks('ended'), fakeGraph(), () => false).alive).toBeFalse();
  });

  it('session sur micro gardé : le tampon d’abord, l’audio gardé commence par lui, les pistes survivent', async () => {
    const stream = tracks();
    const mic = new ConversationMic(stream, fakeGraph(), () => false);
    mic.feed(new Float32Array(3_200).fill(0.1));
    const timers = manualTimers();
    const listener = new StreamingListener(ticket, stream, handlers, mic, timers);
    await listener.start(null);
    expect(listener.preRollMs).toBe(200);
    timers.advance(25);
    expect(FakeSocket.sent.length).toBe(2);
    expect(listener.audioSeconds()).toBeCloseTo(0.2, 3);
    mic.feed(new Float32Array(1_600).fill(0.2));
    timers.advance(25);
    expect(FakeSocket.sent.length).toBe(3);
    expect(listener.audioSeconds()).toBeCloseTo(0.3, 3);
    listener.close();
    expect(stream.stopped()).toBe(0);
    mic.feed(new Float32Array(10));
    expect(FakeSocket.sent.length).toBe(3);
  });

  it('la phrase du mot d’éveil prime sur le tampon', async () => {
    const mic = new ConversationMic(tracks(), fakeGraph(), () => false);
    mic.feed(new Float32Array(1_600));
    const listener = new StreamingListener(ticket, tracks(), handlers, mic);
    await listener.start(() => new Float32Array(800));
    expect(listener.preRollMs).toBe(0);
    expect(listener.audioSeconds()).toBeCloseTo(0.05, 3);
    listener.close();
  });

  describe('JarvisStreamOpener', () => {
    function liveStream(): MediaStream {
      return new AudioContext().createMediaStreamDestination().stream;
    }

    const keep = (events: string[]) => ({ silenced: () => false, report: (d: string) => events.push(d) });

    it('réutilise le micro gardé d’une écoute à l’autre, le rouvre usé, le rend à la libération', async () => {
      const first = liveStream();
      const second = liveStream();
      const gum = spyOn(navigator.mediaDevices, 'getUserMedia').and.returnValues(Promise.resolve(first),
        Promise.resolve(second));
      const events: string[] = [];
      const opener = new JarvisStreamOpener();

      const a = await opener.open(ticket, handlers, null, keep(events));
      a.close();
      const b = await opener.open(ticket, handlers, null, keep(events));
      b.close();
      expect(gum).toHaveBeenCalledTimes(1);
      expect(first.getTracks()[0].readyState).toBe('live');
      expect(events[0]).toMatch(/^open ms=\d+$/);
      expect(events[1]).toBe('reuse');

      first.getTracks()[0].stop();
      const c = await opener.open(ticket, handlers, null, keep(events));
      c.close();
      expect(gum).toHaveBeenCalledTimes(2);

      expect(opener.keptStream()).toBe(second);
      expect(opener.releaseMic()).toBeTrue();
      expect(opener.keptStream()).toBeNull();
      expect(second.getTracks()[0].readyState).toBe('ended');
      expect(opener.releaseMic()).toBeFalse();
    });

    it('micro refusé : `fail` au journal, échec classé « micro »', async () => {
      spyOn(navigator.mediaDevices, 'getUserMedia').and.rejectWith(new DOMException('non', 'NotAllowedError'));
      const events: string[] = [];
      await expectAsync(new JarvisStreamOpener().open(ticket, handlers, null, keep(events)))
        .toBeRejectedWith(jasmine.objectContaining({ kind: 'micro' }));
      expect(events).toEqual(['fail']);
    });

    it('sans micro gardé : un micro par écoute, rendu à la fin (comportement d’avant)', async () => {
      const stream = liveStream();
      spyOn(navigator.mediaDevices, 'getUserMedia').and.resolveTo(stream);
      const opener = new JarvisStreamOpener();
      const listening = await opener.open(ticket, handlers, null, null);
      listening.close();
      expect(stream.getTracks()[0].readyState).toBe('ended');
      expect(opener.releaseMic()).toBeFalse();
    });
  });
});

describe('Jarvis n’est plus raccroché (F-258 / SF-258-01)', () => {
  const ticket = { sessionId: 's1', provider: 'elevenlabs', url: 'wss://x/realtime', token: 't1',
    expiresAt: '2030-01-01T00:00:00Z', sampleRate: 16_000, maxSeconds: 120 };

  /** Une session pilotée par le test : rien n'arrive tout seul. */
  class DrivenSocket {
    static OPEN = 1;
    static all: DrivenSocket[] = [];
    readyState = 1;
    sent: { audio: string; commit: boolean }[] = [];
    onmessage: ((e: { data: string }) => void) | null = null;
    onerror: (() => void) | null = null;
    onclose: ((e?: { code: number; reason: string }) => void) | null = null;
    constructor(readonly url: string) {
      DrivenSocket.all.push(this);
    }
    send(raw: string): void {
      const m = JSON.parse(raw) as { audio_base_64: string; commit: boolean };
      this.sent.push({ audio: m.audio_base_64, commit: m.commit });
    }
    close(): void {
      this.readyState = 3;
    }
    message(body: object): void {
      this.onmessage?.({ data: JSON.stringify(body) });
    }
    serverClose(code: number, reason: string): void {
      this.readyState = 3;
      this.onclose?.({ code, reason });
    }
  }

  let originalSocket: typeof WebSocket;
  beforeEach(() => {
    DrivenSocket.all = [];
    originalSocket = window.WebSocket;
    (window as unknown as { WebSocket: unknown }).WebSocket = DrivenSocket;
  });
  afterEach(() => {
    (window as unknown as { WebSocket: unknown }).WebSocket = originalSocket;
  });

  const settle = () => new Promise<void>((resolve) => setTimeout(resolve));
  const bytes = (b64: string) => atob(b64).length;

  function graph(rate: number): CaptureGraph {
    return { rate, alive: () => true, resume: () => undefined, close: () => undefined };
  }
  const liveTracks = { getTracks: () => [{ readyState: 'live', stop: () => undefined }] } as unknown as MediaStream;

  interface HandlerOptions {
    renew?: boolean;
    renewFails?: boolean;
  }

  function makeHandlers(options: HandlerOptions = {}) {
    const errors: (StreamFailure | undefined)[] = [];
    const traces: string[] = [];
    const renewals: { previous: string; seconds: number }[] = [];
    const committed: string[] = [];
    const handlers = {
      onPartial: () => undefined,
      onCommitted: (text: string) => {
        committed.push(text);
      },
      onError: (failure?: StreamFailure) => {
        errors.push(failure);
      },
      onTrace: (event: string, detail: string) => {
        traces.push(`${event} ${detail}`);
      },
      ...(options.renew === false ? {} : {
        renewTicket: async (previous: typeof ticket, seconds: number) => {
          renewals.push({ previous: previous.sessionId, seconds });
          if (options.renewFails) {
            throw new Error('ticket');
          }
          return { ...ticket, sessionId: `s${renewals.length + 1}`, token: `t${renewals.length + 1}` };
        },
      }),
    };
    return { handlers, errors, traces, renewals, committed };
  }

  /** Une écoute ouverte sur un micro gardé à `rate`, session acceptée. */
  async function opened(rate: number, options: HandlerOptions = {}) {
    const mic = new ConversationMic(liveTracks, graph(rate), () => false);
    const timers = manualTimers();
    const h = makeHandlers(options);
    const listener = new StreamingListener(ticket, liveTracks, h.handlers, mic, timers);
    const starting = listener.start(null);
    DrivenSocket.all[DrivenSocket.all.length - 1].message({ message_type: 'session_started' });
    await starting;
    return { mic, timers, listener, ...h };
  }

  /** `seconds` de micro à `rate`, livrés en blocs de 128 échantillons (AudioWorklet). */
  function feedBlocks(mic: ConversationMic, rate: number, seconds: number): void {
    const blocks = Math.round(rate * seconds / 128);
    for (let i = 0; i < blocks; i++) {
      mic.feed(new Float32Array(128).fill(0.1));
    }
  }

  it('reconnaît tous les types d’erreur documentés, avec leur nom ; l’inconnu reste une erreur « other »', () => {
    for (const type of STT_ERROR_TYPES) {
      expect(parseSttMessage(JSON.stringify({ message_type: type, error: 'x' })))
        .toEqual({ kind: 'error', text: '', errorType: type });
    }
    expect(parseSttMessage(JSON.stringify({ message_type: 'queue_overflow',
      error: 'Session terminated: audio data is being sent too frequently.' })).errorType).toBe('queue_overflow');
    expect(parseSttMessage('{"message_type":"brand_new_error"}')).toEqual({ kind: 'error', text: '', errorType: 'other' });
    expect(parseSttMessage('{"message_type":"warning","warning":"w"}').kind).toBe('other');
  });

  it('classe les coupures passagères et les écrit sans contenu', () => {
    expect(isTransientFailure({ type: 'queue_overflow', code: null, reason: null })).toBeTrue();
    expect(isTransientFailure({ type: 'resource_exhausted', code: null, reason: null })).toBeTrue();
    expect(isTransientFailure({ type: null, code: 1006, reason: null })).toBeTrue();
    expect(isTransientFailure({ type: 'auth_error', code: null, reason: null })).toBeFalse();
    expect(isTransientFailure({ type: 'quota_exceeded', code: null, reason: null })).toBeFalse();
    expect(failureDetail({ type: 'queue_overflow', code: 1000, reason: 'queue_overflow' }))
      .toBe('type=queue_overflow code=1000 reason=queue_overflow');
    expect(failureDetail({ type: null, code: 1006, reason: 'a b/é' })).toBe('type=- code=1006 reason=a_b__');
  });

  it('regroupe les blocs de 128 échantillons en blocs de 100 ms, sans perte au rééchantillonnage', () => {
    const chunker = new AudioChunker();
    const out: Float32Array[] = [];
    for (let i = 0; i < 75; i++) {
      out.push(...chunker.add(new Float32Array(128).fill(i), 48_000).map((c) => c.samples));
    }
    // 75 × 128 = 9 600 échantillons = 2 blocs de 4 800 (100 ms à 48 kHz), rien en reste.
    expect(out.length).toBe(2);
    expect(out.every((c) => c.length === 4_800)).toBeTrue();
    expect(chunker.pending).toBe(0);
    expect(downsample(out[0], 48_000, 16_000).length).toBe(1_600);
    chunker.add(new Float32Array(100), 48_000);
    expect(chunker.flush()!.samples.length).toBe(100);
    expect(chunker.flush()).toBeNull();
    // Un changement de débit rend d'abord le reste.
    chunker.add(new Float32Array(10), 48_000);
    const mixed = chunker.add(new Float32Array(1_600), 16_000);
    expect(mixed.map((c) => [c.samples.length, c.rate])).toEqual([[10, 48_000], [1_600, 16_000]]);
  });

  it('l’envoi lissé : tout de suite si rien n’attend, sinon un message tous les 25 ms, dans l’ordre', () => {
    const timers = manualTimers();
    const written: string[] = [];
    const outbox = new PacedOutbox((m) => written.push(m), 25, timers);
    for (let i = 0; i < 5; i++) {
      outbox.enqueue(`m${i}`);
    }
    expect(written).toEqual(['m0']);
    timers.advance(25);
    expect(written).toEqual(['m0', 'm1']);
    timers.advance(75);
    expect(written).toEqual(['m0', 'm1', 'm2', 'm3', 'm4']);
    timers.advance(100);
    outbox.enqueue('vivant');
    expect(written[written.length - 1]).toBe('vivant');
    outbox.enqueue('a');
    outbox.enqueue('b');
    outbox.clear();
    timers.advance(100);
    expect(written[written.length - 1]).toBe('vivant');
    expect(outbox.waiting).toBe(0);
  });

  it('minuterie en retard (fil occupé) : rattrapage par 4 messages au plus, la file ne dérive pas', () => {
    const base = manualTimers();
    // Chaque minuterie part 200 ms plus tard que demandé.
    const late: OutboxTimers = { now: base.now, set: (fn, ms) => base.set(fn, ms + 200), clear: base.clear };
    const written: string[] = [];
    const outbox = new PacedOutbox((m) => written.push(m), 25, late);
    for (let i = 0; i < 10; i++) {
      outbox.enqueue(`m${i}`);
    }
    expect(written.length).toBe(1);
    base.advance(225);
    expect(written.length).toBe(5);
    base.advance(225);
    expect(written.length).toBe(9);
    base.advance(225);
    expect(written.length).toBe(10);
  });

  it('1 s de micro à 48 kHz part en 10 messages de 100 ms, pas en 375 petits', async () => {
    const { mic, timers, listener } = await opened(48_000);
    feedBlocks(mic, 48_000, 1);
    timers.advance(1_000);
    const sent = DrivenSocket.all[0].sent;
    expect(sent.length).toBe(10);
    expect(sent.every((m) => bytes(m.audio) === 3_200)).toBeTrue();
    listener.close();
  });

  it('une rafale (navigateur occupé) est lissée : jamais plus d’un message par 25 ms', async () => {
    const { mic, timers, listener } = await opened(48_000);
    feedBlocks(mic, 48_000, 1.5);
    expect(DrivenSocket.all[0].sent.length).toBe(1);
    timers.advance(100);
    expect(DrivenSocket.all[0].sent.length).toBe(5);
    timers.advance(1_000);
    expect(DrivenSocket.all[0].sent.length).toBe(15);
    listener.close();
  });

  it('le commit pousse d’abord le reste du bloc en cours', async () => {
    const { mic, timers, listener } = await opened(48_000);
    mic.feed(new Float32Array(2_400));
    listener.commit();
    timers.advance(100);
    const sent = DrivenSocket.all[0].sent;
    expect(sent.map((m) => [bytes(m.audio), m.commit])).toEqual([[1_600, false], [0, true]]);
    listener.close();
  });

  it('queue_overflow : une réouverture avec un ticket neuf, le son non validé renvoyé, aucun repli', async () => {
    const { mic, timers, listener, errors, traces, renewals, committed } = await opened(16_000);
    feedBlocks(mic, 16_000, 0.5);
    timers.advance(1_000);
    DrivenSocket.all[0].message({ message_type: 'committed_transcript', text: 'Bonjour Jarvis' });
    feedBlocks(mic, 16_000, 0.3);
    timers.advance(1_000);
    DrivenSocket.all[0].message({ message_type: 'queue_overflow', error: 'trop' });
    // Pendant la réouverture, le micro continue d'être gardé.
    feedBlocks(mic, 16_000, 0.2);
    await settle();
    expect(renewals).toEqual([{ previous: 's1', seconds: 0 }]);
    expect(DrivenSocket.all.length).toBe(2);
    expect(DrivenSocket.all[1].url).toContain('token=t2');
    DrivenSocket.all[1].message({ message_type: 'session_started' });
    await settle();
    timers.advance(1_000);
    // Renvoyé : 0,3 s après la validation + 0,2 s pendant la réouverture = 5 blocs de 100 ms.
    expect(DrivenSocket.all[1].sent.length).toBe(5);
    expect(errors).toEqual([]);
    expect(committed).toEqual(['Bonjour Jarvis']);
    expect(traces).toContain('stream_error type=queue_overflow');
    expect(traces.some((t) => /^stream_reopen ok ms=\d+ resend_ms=500$/.test(t))).toBeTrue();
    // L'ancienne session se ferme ensuite côté serveur : tracé, sans effet.
    DrivenSocket.all[0].serverClose(1000, 'queue_overflow');
    expect(traces).toContain('stream_close type=queue_overflow code=1000 reason=queue_overflow');
    expect(errors).toEqual([]);
    // L'énoncé gardé n'a rien en double.
    expect(listener.audioSeconds()).toBeCloseTo(1.0, 3);
    // Une seconde coupure : plus de réouverture, le repli.
    DrivenSocket.all[1].serverClose(1006, '');
    await settle();
    expect(renewals.length).toBe(1);
    expect(errors).toEqual([{ type: null, code: 1006, reason: null }]);
    listener.close();
  });

  it('un commit demandé pendant la réouverture part après le son renvoyé', async () => {
    const { mic, timers, listener } = await opened(16_000);
    feedBlocks(mic, 16_000, 0.2);
    timers.advance(100);
    DrivenSocket.all[0].serverClose(1011, 'boom');
    listener.commit();
    await settle();
    DrivenSocket.all[1].message({ message_type: 'session_started' });
    await settle();
    timers.advance(1_000);
    const sent = DrivenSocket.all[1].sent;
    expect(sent.map((m) => m.commit)).toEqual([false, false, true]);
    listener.close();
  });

  it('une erreur durable (auth_error) ou sans ticket neuf possible : repli direct, nommé', async () => {
    const a = await opened(16_000);
    DrivenSocket.all[0].message({ message_type: 'auth_error', error: 'jeton' });
    await settle();
    expect(a.renewals).toEqual([]);
    expect(a.errors).toEqual([{ type: 'auth_error', code: null, reason: null }]);
    // La fermeture qui suit ne signale pas une seconde fois.
    DrivenSocket.all[0].serverClose(1000, 'auth_error');
    expect(a.errors.length).toBe(1);
    a.listener.close();

    const b = await opened(16_000, { renew: false });
    DrivenSocket.all[DrivenSocket.all.length - 1].message({ message_type: 'queue_overflow', error: 'trop' });
    expect(b.errors).toEqual([{ type: 'queue_overflow', code: null, reason: null }]);
    b.listener.close();

    const c = await opened(16_000, { renewFails: true });
    DrivenSocket.all[DrivenSocket.all.length - 1].message({ message_type: 'queue_overflow', error: 'trop' });
    await settle();
    expect(c.renewals.length).toBe(1);
    expect(c.errors).toEqual([{ type: 'queue_overflow', code: null, reason: null }]);
    expect(c.traces).toContain('stream_reopen fail ticket');
    c.listener.close();
  });

  it('fermée par l’écran pendant la réouverture : rien ne se rouvre ni ne se signale', async () => {
    const { listener, errors } = await opened(16_000);
    DrivenSocket.all[0].message({ message_type: 'queue_overflow', error: 'trop' });
    listener.close();
    await settle();
    expect(DrivenSocket.all.length).toBe(1);
    expect(errors).toEqual([]);
  });

  it('refusée à l’ouverture sur micro gardé : le tampon du micro reste pour la tentative suivante', async () => {
    const mic = new ConversationMic(liveTracks, graph(16_000), () => false);
    mic.feed(new Float32Array(1_600));
    const listener = new StreamingListener(ticket, liveTracks, makeHandlers().handlers, mic, manualTimers());
    const starting = listener.start(null);
    DrivenSocket.all[0].serverClose(1000, 'queue_overflow');
    await expectAsync(starting).toBeRejectedWithError('ferme');
    listener.close();
    expect(mic.attach(() => undefined).length).toBe(1_600);
  });
});
