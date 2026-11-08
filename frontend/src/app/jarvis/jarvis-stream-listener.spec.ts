import {
  CaptureGraph, ConversationMic, ECHO_HOLD_MS, JarvisStreamOpener, PRE_ROLL_MS, PreRollRing, StreamOpenError,
  StreamingListener, downsample, parseSttMessage, sessionUrl, streamFailureOf, ticketFailureOf,
  toBase64, toPcm16,
} from './jarvis-stream-listener';

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

  it('F-239 — la pré-écoute part au fournisseur avant le micro, par morceaux de 100 ms', async () => {
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
    try {
      const listener = new StreamingListener({
        sessionId: 's', provider: 'elevenlabs', url: 'wss://x/realtime', token: 't', expiresAt: '2030-01-01T00:00:00Z',
        sampleRate: 16_000, maxSeconds: 120,
      }, { getTracks: () => [] } as unknown as MediaStream, { onPartial: () => undefined,
        onCommitted: () => undefined, onError: () => undefined });
      await listener.start(() => new Float32Array(4_000).fill(0.1));
      expect(capture).toHaveBeenCalled();
      // 4 000 échantillons à 16 kHz = 250 ms ⇒ trois morceaux (100, 100, 50 ms), puis le micro.
      expect(sent.length).toBe(4);
      expect(sent.slice(0, 3).every((m) => m.audio !== 'MICRO' && !m.commit)).toBeTrue();
      expect(sent[3].audio).toBe('MICRO');
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
    const listener = new StreamingListener(ticket, stream, handlers, mic);
    await listener.start(null);
    expect(listener.preRollMs).toBe(200);
    expect(FakeSocket.sent.length).toBe(2);
    expect(listener.audioSeconds()).toBeCloseTo(0.2, 3);
    mic.feed(new Float32Array(1_600).fill(0.2));
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

      expect(opener.releaseMic()).toBeTrue();
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
