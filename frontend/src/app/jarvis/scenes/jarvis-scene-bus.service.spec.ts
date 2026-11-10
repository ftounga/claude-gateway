import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { JARVIS_UNIVERSE_URL, JarvisSceneBus } from './jarvis-scene-bus.service';
import { testUniverse } from './jarvis-universe.fixture';

describe('F-234 / SF-234-02 — fil des scènes', () => {
  let bus: JarvisSceneBus;
  let http: HttpTestingController;
  let now = 0;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    bus = TestBed.inject(JarvisSceneBus);
    http = TestBed.inject(HttpTestingController);
    now = 0;
    bus.useClock(() => now);
  });

  afterEach(() => {
    bus.ngOnDestroy();
    http.verify();
  });

  it('lit l\'univers à l\'ouverture, aucun appel pendant le tour, relu à la fin', () => {
    bus.load();
    http.expectOne(JARVIS_UNIVERSE_URL).flush(testUniverse());
    bus.setSubject('s-forge');

    bus.turnStarted();
    bus.action({ type: 'read', path: 'Doc archi/a.md' });
    bus.text('🎙 Chez CAGIP, ');
    bus.text('le chantier 3 avance.\n');
    bus.load(); // ignoré : un tour tourne
    http.expectNone(JARVIS_UNIVERSE_URL);
    expect(bus.state().lit.map((l) => l.node.key)).toContain('h-cagip');
    expect(bus.state().lit.map((l) => l.node.key)).toContain('s-forge#3');

    bus.done();
    http.expectOne(JARVIS_UNIVERSE_URL).flush(testUniverse());
  });

  it('F-253 / SF-253-02 — un ordre du cerveau rapide pose le « Montrer » ; une vue qui ne se résout pas ne change rien', () => {
    bus.setUniverse(testUniverse());
    expect(bus.showRequest({ filter: 'clients', client: null })).toBeTrue();
    expect(bus.focus()?.filter).toBe('clients');
    expect(bus.showRequest({ filter: 'clients', client: 'inconnu' })).toBeFalse();
    expect(bus.focus()?.filter).toBe('clients');
    expect(bus.dismissShow()).toBeTrue();
    expect(bus.focus()).toBeNull();
  });

  it('un univers illisible laisse l\'écran tel quel (scène générique quand même)', () => {
    bus.load();
    http.expectOne(JARVIS_UNIVERSE_URL).flush('boom', { status: 500, statusText: 'KO' });
    bus.turnStarted();
    bus.action({ type: 'outil_futur' });
    expect(bus.state().scene?.libelle).toBe('Outil futur');
    bus.aborted();
  });

  it('l\'horloge éteint les nœuds et efface la scène', () => {
    bus.setUniverse(testUniverse());
    bus.turnStarted();
    bus.text('CAGIP.\n');
    bus.action({ type: 'bash', path: 'ls' });
    bus.aborted();
    now = 60_000;
    bus.tick();
    expect(bus.state().lit).toEqual([]);
    expect(bus.state().scene).toBeNull();
  });
});
