import { downsample, parseSttMessage, sessionUrl, toBase64, toPcm16 } from './jarvis-stream-listener';

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
});
