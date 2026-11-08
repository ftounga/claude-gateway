import { fakeAsync, tick } from '@angular/core/testing';

import { heardPart } from '../core/services/jarvis-voice.service';
import { BargeInActions, BargeInController, RESTORE_AFTER_MS, encodeWav, VadStartError, resolveMicVad, vadFailureDetail } from './jarvis-barge-in';

/** Se laisser couper (F-233 / SF-233-08) : baisser, rétablir, couper ; ce qui a été entendu. */
describe('Coupure par la voix (F-233 / SF-233-08)', () => {
  let speaking: boolean;
  let actions: jasmine.SpyObj<BargeInActions>;

  beforeEach(() => {
    speaking = true;
    actions = jasmine.createSpyObj<BargeInActions>('BargeInActions', ['speaking', 'duck', 'cut', 'count']);
    actions.speaking.and.callFake(() => speaking);
  });

  it('voix détectée → baisse ; confirmée → coupe, une seule fois', () => {
    const c = new BargeInController(actions);
    c.voiceStart();
    expect(actions.duck).toHaveBeenCalledWith(true);
    expect(actions.count).toHaveBeenCalledWith('interruptionsDetected');
    c.voiceConfirmed();
    c.voiceConfirmed();
    expect(actions.cut).toHaveBeenCalledTimes(1);
    expect(actions.count).toHaveBeenCalledWith('interruptionsConfirmed');
    expect(c.capturing).toBeTrue();
  });

  it('non confirmée dans le délai, ou fausse alerte : le volume revient', fakeAsync(() => {
    const c = new BargeInController(actions);
    c.voiceStart();
    tick(RESTORE_AFTER_MS);
    expect(actions.duck).toHaveBeenCalledWith(false);
    expect(actions.count).toHaveBeenCalledWith('interruptionsRestored');
    actions.duck.calls.reset();
    c.voiceStart();
    c.misfire();
    expect(actions.duck).toHaveBeenCalledWith(false);
    expect(actions.cut).not.toHaveBeenCalled();
  }));

  it('Jarvis silencieux : rien ne se passe', () => {
    speaking = false;
    const c = new BargeInController(actions);
    c.voiceStart();
    c.voiceConfirmed();
    expect(actions.duck).not.toHaveBeenCalled();
    expect(actions.cut).not.toHaveBeenCalled();
  });

  it('la part entendue d’une phrase coupée suit l’horloge audio (mots entiers)', () => {
    expect(heardPart('Les tests passent sur la branche principale', 1, 2)).toBe('Les tests passent');
    expect(heardPart('Un deux trois', 0, 2)).toBe('');
    expect(heardPart('Un deux trois', 9, 2)).toBe('Un deux trois');
    // Durée inconnue (flux) : estimée au débit moyen.
    expect(heardPart('abcd efgh ijkl mnop', 0.5, Infinity)).toBe('abcd efgh ijkl mnop'.split(' ').slice(0, 1).join(' '));
  });

  it('encode le WAV 16 bits mono 16 kHz', async () => {
    const wav = encodeWav(new Float32Array([0, 1, -1]));
    expect(wav.type).toBe('audio/wav');
    const bytes = new Uint8Array(await wav.arrayBuffer());
    expect(bytes.length).toBe(44 + 6);
    expect(String.fromCharCode(...bytes.slice(0, 4))).toBe('RIFF');
    expect(new DataView(bytes.buffer).getUint32(24, true)).toBe(16000);
    expect(new DataView(bytes.buffer).getInt16(46, true)).toBe(0x7fff);
  });
});

describe('Se laisser couper — le démarrage (F-251 / SF-251-05)', () => {
  it('lit MicVAD à la racine (webpack) ou sous default (esbuild, production) ; rien : TypeError', () => {
    const micVad = { new: () => undefined };
    expect(resolveMicVad({ MicVAD: micVad })).toBe(micVad);
    expect(resolveMicVad({ default: { MicVAD: micVad } })).toBe(micVad);
    expect(() => resolveMicVad({})).toThrowError(TypeError);
  });

  it('le détail du journal donne l’étape, le nom, le message et la première ligne', () => {
    const cause = new TypeError('Cannot read properties of undefined');
    cause.stack = 'TypeError: x\n    at http://h/js/main-AB12.js:2:97\n    at z';
    expect(vadFailureDetail(new VadStartError('chargement', cause)))
      .toBe('chargement TypeError Cannot read properties of undefined at main-AB12.js:2:97');
    expect(vadFailureDetail(new Error('boom'))).toContain('inconnue Error boom');
    expect(vadFailureDetail(null)).toBe('inconnue inconnue');
  });
});
