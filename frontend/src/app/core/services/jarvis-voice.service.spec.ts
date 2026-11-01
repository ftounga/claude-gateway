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
