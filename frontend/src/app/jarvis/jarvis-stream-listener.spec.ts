import {
  StreamingListener, downsample, parseSttMessage, sessionUrl, toBase64, toPcm16,
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
