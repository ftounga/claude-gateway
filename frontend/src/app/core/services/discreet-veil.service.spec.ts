import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import {
  DEFAULT_VEIL_SETTINGS,
  DiscreetVeilService,
  VEIL_FAVICON,
  VEIL_SETTINGS_KEY,
  VEIL_TITLE,
  readVeilSettings,
} from './discreet-veil.service';
import { TabAlertService } from './tab-alert.service';

describe('DiscreetVeilService (F-259 / SF-259-01)', () => {
  let service: DiscreetVeilService;
  let link: HTMLLinkElement;
  let originalTitle: string;

  function escape(init: KeyboardEventInit = {}): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true, ...init });
    document.body.dispatchEvent(event);
    return event;
  }

  function create(stored: string | null = JSON.stringify({ enabled: true, delayMin: 2 })): void {
    localStorage.removeItem(VEIL_SETTINGS_KEY);
    if (stored !== null) {
      localStorage.setItem(VEIL_SETTINGS_KEY, stored);
    }
    TestBed.configureTestingModule({});
    service = TestBed.inject(DiscreetVeilService);
  }

  beforeEach(() => {
    originalTitle = document.title;
    document.title = 'Portail — écran';
    link = document.querySelector('link[rel="icon"]') ?? document.head.appendChild(
      Object.assign(document.createElement('link'), { rel: 'icon' }));
    link.setAttribute('href', 'logo.png');
    link.setAttribute('type', 'image/png');
  });

  afterEach(() => {
    service?.detach();
    localStorage.removeItem(VEIL_SETTINGS_KEY);
    document.title = originalTitle;
  });

  it('est désactivé par défaut, délai 2 min ; une valeur illisible ou hors liste retombe sur le défaut', () => {
    expect(readVeilSettings(null)).toEqual(DEFAULT_VEIL_SETTINGS);
    expect(DEFAULT_VEIL_SETTINGS).toEqual({ enabled: false, delayMin: 2 });
    expect(readVeilSettings('{pas du json')).toEqual(DEFAULT_VEIL_SETTINGS);
    expect(readVeilSettings(JSON.stringify({ enabled: true, delayMin: 3 }))).toEqual({ enabled: true, delayMin: 2 });
    expect(readVeilSettings(JSON.stringify({ enabled: true, delayMin: 10 }))).toEqual({ enabled: true, delayMin: 10 });
  });

  it('retrouve le réglage enregistré (même appareil)', () => {
    create(null);
    service.update({ enabled: true, delayMin: 5 });
    expect(JSON.parse(localStorage.getItem(VEIL_SETTINGS_KEY)!)).toEqual({ enabled: true, delayMin: 5 });
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    expect(TestBed.inject(DiscreetVeilService).settings()).toEqual({ enabled: true, delayMin: 5 });
  });

  it('stockage qui lève une exception : lu comme désactivé, sans erreur, et signalé', () => {
    spyOn(Storage.prototype, 'getItem').and.throwError('SecurityError');
    spyOn(Storage.prototype, 'setItem').and.throwError('SecurityError');
    TestBed.configureTestingModule({});
    service = TestBed.inject(DiscreetVeilService);
    expect(service.settings().enabled).toBeFalse();
    expect(service.storageAvailable()).toBeFalse();
    expect(() => service.update({ enabled: true })).not.toThrow();
    expect(service.storageAvailable()).toBeFalse();
  });

  it('double Échap à moins de 500 ms pose le voile ; le premier Échap poursuit son chemin', fakeAsync(() => {
    create();
    service.attach();
    const first = escape();
    expect(first.defaultPrevented).toBeFalse();
    tick(300);
    const second = escape();
    expect(second.defaultPrevented).toBeTrue();
    expect(service.veiled()).toBeTrue();
  }));

  it('deux Échap espacés de plus de 500 ms ne posent pas le voile ; une répétition de touche non plus', fakeAsync(() => {
    create();
    service.attach();
    escape();
    tick(600);
    escape();
    expect(service.veiled()).toBeFalse();
    tick(600);
    escape();
    escape({ repeat: true });
    expect(service.veiled()).toBeFalse();
  }));

  it('réglage désactivé : ni raccourci ni inactivité', fakeAsync(() => {
    create(JSON.stringify({ enabled: false, delayMin: 1 }));
    service.attach();
    escape();
    escape();
    tick(5 * 60_000);
    expect(service.veiled()).toBeFalse();
  }));

  it('non monté (page publique) : ni raccourci ni inactivité', fakeAsync(() => {
    create();
    escape();
    escape();
    tick(5 * 60_000);
    expect(service.veiled()).toBeFalse();
  }));

  it("pose le voile après le délai d'inactivité", fakeAsync(() => {
    create(JSON.stringify({ enabled: true, delayMin: 1 }));
    service.attach();
    tick(59_000);
    expect(service.veiled()).toBeFalse();
    tick(1_000);
    expect(service.veiled()).toBeTrue();
  }));

  for (const type of ['mousemove', 'mousedown', 'keydown', 'wheel', 'touchstart', 'pointerdown']) {
    it(`une activité (${type}) avant l'échéance relance le compte`, fakeAsync(() => {
      create(JSON.stringify({ enabled: true, delayMin: 1 }));
      service.attach();
      tick(50_000);
      window.dispatchEvent(new Event(type));
      tick(50_000);
      expect(service.veiled()).toBeFalse();
      tick(10_000);
      expect(service.veiled()).toBeTrue();
    }));
  }

  it("titre « Documentation » et icône neutre pendant le voile, rétablis à l'identique au retrait", () => {
    create();
    service.attach();
    service.veil();
    expect(document.title).toBe(VEIL_TITLE);
    expect(link.getAttribute('href')).toBe(VEIL_FAVICON);
    service.unveil();
    expect(document.title).toBe('Portail — écran');
    expect(link.getAttribute('href')).toBe('logo.png');
    expect(link.getAttribute('type')).toBe('image/png');
  });

  it("un titre changé par la page pendant le voile est rétabli au retrait, l'onglet reste neutre", async () => {
    create();
    service.attach();
    service.veil();
    document.title = 'Autre écran';
    await new Promise((resolve) => setTimeout(resolve));
    expect(document.title).toBe(VEIL_TITLE);
    service.unveil();
    expect(document.title).toBe('Autre écran');
  });

  it('pendant le voile, les touches sont retenues : rien ne se tape dessous, Échap ne ferme rien', () => {
    create();
    service.attach();
    service.veil();
    const seen = jasmine.createSpy('keydown');
    document.addEventListener('keydown', seen);
    const key = new KeyboardEvent('keydown', { key: 'a', bubbles: true, cancelable: true });
    document.body.dispatchEvent(key);
    const esc = escape();
    document.removeEventListener('keydown', seen);
    expect(seen).not.toHaveBeenCalled();
    expect(key.defaultPrevented).toBeTrue();
    expect(esc.defaultPrevented).toBeTrue();
    expect(service.veiled()).toBeTrue();
  });

  it('le clavier et la souris n’enlèvent pas le voile', () => {
    create();
    service.attach();
    service.veil();
    window.dispatchEvent(new Event('mousemove'));
    escape();
    expect(service.veiled()).toBeTrue();
  });

  it('démonter le dernier écran couvert enlève le voile et rétablit le titre', () => {
    create();
    service.attach();
    service.veil();
    service.detach();
    expect(service.veiled()).toBeFalse();
    expect(document.title).toBe('Portail — écran');
  });

  it("sous le voile, le signal d'onglet ne touche pas au titre neutre", () => {
    create();
    service.attach();
    service.veil();
    spyOnProperty(document, 'hidden', 'get').and.returnValue(true);
    TestBed.inject(TabAlertService).signalTurnDone();
    expect(document.title).toBe(VEIL_TITLE);
  });
});
