import { TestBed } from '@angular/core/testing';
import { HttpClient } from '@angular/common/http';

import { AuthService } from './auth.service';
import { JarvisVoiceService } from './jarvis-voice.service';

/** Un faux `AudioContext` dont l'état et le réveil sont pilotés par le test. */
function fakeContext(state: AudioContextState, resumeTo: AudioContextState | null) {
  return class {
    state: AudioContextState = state;
    destination = {};
    createAnalyser() {
      return { fftSize: 0, connect: () => undefined };
    }
    createGain() {
      return { connect: () => undefined };
    }
    resume(): Promise<void> {
      if (resumeTo === null) {
        return new Promise<void>(() => undefined); // Chrome sans geste : la promesse reste en suspens.
      }
      this.state = resumeTo;
      return Promise.resolve();
    }
  };
}

/** **Le son est-il permis sans geste ?** (F-233 / SF-233-12) */
describe('JarvisVoiceService.audioAllowed (F-233 / SF-233-12)', () => {
  const win = window as unknown as Record<string, unknown>;
  const nav = navigator as unknown as Record<string, unknown>;
  let savedContext: unknown;
  let savedPolicy: unknown;

  function service(): JarvisVoiceService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [JarvisVoiceService, { provide: HttpClient, useValue: {} }, { provide: AuthService, useValue: {} }],
    });
    return TestBed.inject(JarvisVoiceService);
  }

  beforeEach(() => {
    savedContext = win['AudioContext'];
    savedPolicy = nav['getAutoplayPolicy'];
    nav['getAutoplayPolicy'] = undefined;
  });

  afterEach(() => {
    win['AudioContext'] = savedContext;
    nav['getAutoplayPolicy'] = savedPolicy;
  });

  it('contexte déjà en marche : permis', async () => {
    win['AudioContext'] = fakeContext('running', 'running');
    expect(await service().audioAllowed(10)).toBeTrue();
  });

  it('contexte suspendu que resume() réveille (PWA installée, engagement média) : permis', async () => {
    win['AudioContext'] = fakeContext('suspended', 'running');
    expect(await service().audioAllowed(10)).toBeTrue();
  });

  it('contexte suspendu qui ne se réveille pas sans geste : bloqué', async () => {
    win['AudioContext'] = fakeContext('suspended', null);
    expect(await service().audioAllowed(10)).toBeFalse();
  });

  it('la politique d’autoplay du navigateur prime quand elle existe', async () => {
    win['AudioContext'] = fakeContext('running', 'running');
    nav['getAutoplayPolicy'] = () => 'disallowed';
    expect(await service().audioAllowed(10)).toBeFalse();
    nav['getAutoplayPolicy'] = () => 'allowed';
    expect(await service().audioAllowed(10)).toBeTrue();
  });
});

/** **La lecture a une borne** (F-233 / SF-233-16, audit du 2026-11-07 : 46 s de « parole » pour 6 mots). */
describe('JarvisVoiceService — chien de garde de lecture (F-233 / SF-233-16)', () => {
  const win = window as unknown as Record<string, unknown>;
  let savedAudio: unknown;
  let savedContext: unknown;
  let fake: { currentTime: number; ended: boolean; readyState: number; networkState: number; volume: number;
    src: string; onplaying: (() => void) | null; onended: (() => void) | null; onerror: (() => void) | null;
    play: () => Promise<void>; pause: () => void };

  function service(): JarvisVoiceService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [JarvisVoiceService, { provide: HttpClient, useValue: {} }, { provide: AuthService, useValue: {} }],
    });
    return TestBed.inject(JarvisVoiceService);
  }

  function play(voice: JarvisVoiceService): Promise<void> {
    return (voice as unknown as { play: (f: unknown, a: AbortController, h: unknown, r: boolean, t: string)
      => Promise<void> }).play({ url: 'blob:x', alignment: null }, new AbortController(), undefined, false, 'Bonjour.');
  }

  beforeEach(() => {
    savedAudio = win['Audio'];
    savedContext = win['AudioContext'];
    win['AudioContext'] = undefined;
    fake = { currentTime: 0, ended: false, readyState: 0, networkState: 2, volume: 1, src: '', onplaying: null,
      onended: null, onerror: null, play: () => Promise.resolve(), pause: () => undefined };
    win['Audio'] = function FakeAudio() {
      return fake;
    };
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date('2026-11-07T14:00:00Z'));
  });

  afterEach(() => {
    jasmine.clock().uninstall();
    win['Audio'] = savedAudio;
    win['AudioContext'] = savedContext;
  });

  it('jamais démarrée : close en échec après 10 s, et le calage est journalisé', async () => {
    const voice = service();
    const traced: string[] = [];
    voice.traceHook = (event) => traced.push(event);
    const result = play(voice).then(() => 'fini', (error) => `rejet:${error}`);
    jasmine.clock().tick(9_000);
    jasmine.clock().tick(2_000);
    expect(await result).toBe('rejet:echec');
    expect(traced).toEqual(['voice_stall']);
  });

  it('démarrée puis figée 8 s : close en échec', async () => {
    const voice = service();
    const result = play(voice).then(() => 'fini', (error) => `rejet:${error}`);
    fake.onplaying?.();
    for (let i = 1; i <= 3; i++) {
      fake.currentTime = i * 0.5;
      jasmine.clock().tick(1_000);
    }
    jasmine.clock().tick(9_000);
    expect(await result).toBe('rejet:echec');
  });

  it('une lecture qui avance n’est jamais coupée ; sa fin la résout', async () => {
    const voice = service();
    const traced: string[] = [];
    voice.traceHook = (event) => traced.push(event);
    const result = play(voice).then(() => 'fini', (error) => `rejet:${error}`);
    fake.onplaying?.();
    for (let i = 1; i <= 20; i++) {
      fake.currentTime = i;
      jasmine.clock().tick(1_000);
    }
    fake.onended?.();
    expect(await result).toBe('fini');
    expect(traced).toEqual([]);
  });
});
