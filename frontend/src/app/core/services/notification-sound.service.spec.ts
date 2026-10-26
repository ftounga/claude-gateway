import { TestBed, fakeAsync, tick } from '@angular/core/testing';

import {
  ACTION_NOTES,
  ACTION_REPEAT_MS,
  DEFAULT_SOUND_SETTINGS,
  INFO_NOTES,
  NotificationSoundService,
  SOUND_COOLDOWN_MS,
  SOUND_SETTINGS_KEY,
  readSoundSettings,
} from './notification-sound.service';

/** Un faux contexte Web Audio : compte les oscillateurs démarrés et relève leurs fréquences et crêtes. */
class FakeAudioContext {
  static instances: FakeAudioContext[] = [];
  state: AudioContextState = 'suspended';
  currentTime = 0;
  destination = {};
  started: number[] = [];
  peaks: number[] = [];
  resumed = 0;

  constructor() {
    FakeAudioContext.instances.push(this);
  }

  resume(): Promise<void> {
    this.resumed++;
    this.state = 'running';
    return Promise.resolve();
  }

  createOscillator(): unknown {
    const frequency = { value: 0, setValueAtTime: (v: number) => { frequency.value = v; } };
    return {
      type: 'sine',
      frequency,
      connect: () => undefined,
      start: () => this.started.push(frequency.value),
      stop: () => undefined,
    };
  }

  createGain(): unknown {
    return {
      gain: {
        setValueAtTime: () => undefined,
        exponentialRampToValueAtTime: (v: number) => {
          if (v > 0.001) {
            this.peaks.push(v);
          }
        },
      },
      connect: () => undefined,
    };
  }
}

describe('NotificationSoundService (F-217 / SF-217-02)', () => {
  const scope = globalThis as unknown as { AudioContext?: unknown };
  let original: unknown;
  let service: NotificationSoundService;

  function ctx(): FakeAudioContext {
    return FakeAudioContext.instances[FakeAudioContext.instances.length - 1];
  }

  function gesture(): void {
    document.dispatchEvent(new Event('pointerdown'));
  }

  function create(): NotificationSoundService {
    TestBed.configureTestingModule({});
    service = TestBed.inject(NotificationSoundService);
    return service;
  }

  beforeEach(() => {
    original = scope.AudioContext;
    scope.AudioContext = FakeAudioContext;
    FakeAudioContext.instances = [];
    localStorage.removeItem(SOUND_SETTINGS_KEY);
  });

  afterEach(() => {
    service?.settleAll();
    scope.AudioContext = original;
    localStorage.removeItem(SOUND_SETTINGS_KEY);
  });

  it('est activé par défaut, volume 70 %, les deux catégories', () => {
    create();
    expect(service.settings()).toEqual(DEFAULT_SOUND_SETTINGS);
    expect(DEFAULT_SOUND_SETTINGS.enabled).toBeTrue();
  });

  it('lit des réglages illisibles ou hors bornes comme les valeurs par défaut', () => {
    expect(readSoundSettings('pas du json')).toEqual(DEFAULT_SOUND_SETTINGS);
    expect(readSoundSettings('{"volume": 4, "enabled": "oui"}')).toEqual({ ...DEFAULT_SOUND_SETTINGS, volume: 1 });
    expect(readSoundSettings('{"volume": -1, "info": false}')).toEqual({ ...DEFAULT_SOUND_SETTINGS, volume: 0, info: false });
  });

  it('mémorise un changement sur l\'appareil', () => {
    create();
    service.update({ volume: 0.3, info: false });
    expect(readSoundSettings(localStorage.getItem(SOUND_SETTINGS_KEY))).toEqual({ ...DEFAULT_SOUND_SETTINGS, volume: 0.3, info: false });
  });

  it('se tait tant qu\'aucun geste n\'a eu lieu sur la page, puis sonne après le premier clic', () => {
    create();
    // Karma : selon le lanceur, un geste a pu déjà avoir lieu ; on force l'état « jamais touché ».
    (service as unknown as { unlocked: boolean }).unlocked = false;
    service.info();
    expect(FakeAudioContext.instances.length).toBe(0);

    gesture();
    service.info();
    expect(ctx().started).toEqual(INFO_NOTES.map((n) => n.frequency));
    expect(ctx().resumed).toBeGreaterThan(0);
  });

  it('joue deux sons distincts : trois notes pour l\'action, deux pour l\'information', () => {
    create();
    gesture();
    service.test('action');
    expect(ctx().started).toEqual(ACTION_NOTES.map((n) => n.frequency));
    ctx().started = [];
    service.test('info');
    expect(ctx().started).toEqual(INFO_NOTES.map((n) => n.frequency));
  });

  it('le volume règle la crête de chaque note', () => {
    create();
    gesture();
    service.update({ volume: 0.5 });
    service.test('info');
    expect(ctx().peaks).toEqual(INFO_NOTES.map((n) => n.peak * 0.5));
  });

  it('respecte l\'interrupteur général, la catégorie et le volume nul', () => {
    create();
    gesture();
    service.update({ enabled: false });
    service.play('action');
    service.update({ enabled: true, info: false });
    service.play('info');
    service.update({ info: true, volume: 0 });
    service.play('info');
    // Le premier geste crée (et reprend) le contexte — indispensable sur Safari iOS — mais aucune note ne part.
    expect(FakeAudioContext.instances.every((c) => c.started.length === 0)).toBeTrue();
  });

  it('une rafale ne fait qu\'un son (anti-rafale de 2 s)', fakeAsync(() => {
    create();
    gesture();
    service.info();
    service.info();
    expect(ctx().started.length).toBe(INFO_NOTES.length);
    tick(SOUND_COOLDOWN_MS);
    service.info();
    expect(ctx().started.length).toBe(INFO_NOTES.length * 2);
  }));

  it('une action restée sans geste est rejouée une seule fois après 30 s', fakeAsync(() => {
    create();
    gesture();
    service.actionRequired('confirm:1');
    expect(ctx().started.length).toBe(ACTION_NOTES.length);
    tick(ACTION_REPEAT_MS);
    expect(ctx().started.length).toBe(ACTION_NOTES.length * 2);
    tick(ACTION_REPEAT_MS * 3);
    expect(ctx().started.length).toBe(ACTION_NOTES.length * 2);
  }));

  it('une action tranchée avant 30 s n\'est pas rejouée', fakeAsync(() => {
    create();
    gesture();
    service.actionRequired('question:7');
    service.settle('question:7');
    tick(ACTION_REPEAT_MS);
    expect(ctx().started.length).toBe(ACTION_NOTES.length);
  }));

  it('le rejeu d\'état d\'une même demande ne reprogramme rien', fakeAsync(() => {
    create();
    gesture();
    service.actionRequired('confirm:2');
    tick(SOUND_COOLDOWN_MS + 1);
    service.actionRequired('confirm:2');
    expect(ctx().started.length).toBe(ACTION_NOTES.length);
    service.settleAll();
    tick(ACTION_REPEAT_MS);
    expect(ctx().started.length).toBe(ACTION_NOTES.length);
  }));

  it('sans Web Audio, rien ne casse', () => {
    scope.AudioContext = undefined;
    create();
    gesture();
    expect(() => service.test('action')).not.toThrow();
  });
});
