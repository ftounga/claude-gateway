import { TestBed } from '@angular/core/testing';

import { NameIndex } from './jarvis-name-matcher';
import { JarvisSceneBus } from './jarvis-scene-bus.service';
import { JarvisSceneComponent } from './jarvis-scene.component';
import { JarvisSceneReader } from './jarvis-scene-reader';
import { resolveScene } from './jarvis-scene-registry';
import { testUniverse } from './jarvis-universe.fixture';

describe('F-234 / SF-234-06 — S9 chantier, S11 attentes, S2 oui et question, S14 points de route', () => {
  let reader: JarvisSceneReader;

  beforeEach(() => {
    reader = new JarvisSceneReader(new NameIndex(testUniverse()), 's-forge');
    reader.startTurn();
  });

  it('le registre donne S9, S11, S2, S14', () => {
    expect(resolveScene('update_plan_step').scene).toBe('S9');
    expect(resolveScene('lancer_chantier_autonome').scene).toBe('S9');
    expect(resolveScene('record_blocker').scene).toBe('S11');
    expect(resolveScene('demander').scene).toBe('S2');
    expect(resolveScene('decouper_demande').scene).toBe('S14');
  });

  it('S9 : « chantier 3 · étape 2 · faite » ⇒ le chantier reconnu, l\'étape cochée, compteur avancé', () => {
    reader.action({ type: 'update_plan_step', path: 'chantier 3 · étape 2 · faite' }, 0);
    const scene = reader.state().scene!;
    expect(scene.id).toBe('S9');
    expect(scene.data).toEqual(jasmine.objectContaining({
      progress: 'step', chantierKey: 's-forge#3', step: 2, status: 'faite',
    }));
    expect(reader.state().stepsDone).toEqual({ 's-forge#3': 1 });
    expect(reader.state().lit.map((l) => l.node.key)).toContain('s-forge#3');
    // L'univers relu à la fin du tour compte déjà l'étape.
    reader.setIndex(new NameIndex(testUniverse()));
    expect(reader.state().stepsDone).toEqual({});
  });

  it('S9 : le plan du tour se pose ; la phase s\'écrit', () => {
    reader.plan([{ title: 'a', status: 'done' }, { title: 'b', status: 'pending' }], 0);
    expect(reader.state().scene?.libelle).toBe('Le plan se pose');
    expect(reader.state().scene?.detail).toBe('2 étapes · 1 faite');
    reader.action({ type: 'submit_diagnosis', path: 'chantier 3 · confiance haute' }, 5000);
    reader.tick(5000);
    expect(reader.state().scene?.data?.progress).toBe('phase');
    expect(reader.state().scene?.data?.status).toBe('confiance haute');
  });

  it('S11 : record_blocker attache la personne ; la carte levée la détache', () => {
    reader.action({ type: 'record_blocker', path: 'Zahi · validation' }, 0);
    let scene = reader.state().scene!;
    expect(scene.id).toBe('S11');
    expect(scene.data).toEqual(jasmine.objectContaining({ person: 'Zahi', category: 'validation', attached: true }));
    expect(reader.state().lit.some((l) => l.node.kind === 'person')).toBeTrue();
    reader.attente({ status: 'FAIT', qui: 'Zahi' }, 3000);
    scene = reader.state().scene!;
    expect(scene.libelle).toBe('Attente levée');
    expect(scene.data?.attached).toBeFalse();
    reader.action({ type: 'record_blocker', path: 'validation' }, 9000);
    reader.tick(9000);
    expect(reader.state().scene?.data?.person).toBeUndefined();
    expect(reader.state().scene?.data?.category).toBe('validation');
  });

  it('S2 : l\'autorisation passe devant, compte à rebours, expire seule ; la scène d\'avant reprend', () => {
    reader.action({ type: 'read', path: 'a.ts' }, 0);
    reader.confirm('Envoyer la note au CODIR ?', 100, 42_000);
    expect(reader.state().scene?.id).toBe('S2');
    expect(reader.state().scene?.data?.remaining).toBe(42);
    reader.action({ type: 'bash', path: 'ls' }, 200);
    expect(reader.state().scene?.id).toBe('S2');
    expect(reader.tick(1_300)).toBeTrue();
    expect(reader.state().scene?.data?.remaining).toBe(41);
    reader.tick(42_200);
    expect(reader.state().scene?.id).not.toBe('S2');
  });

  it('S2 : une question structurée attend, tranchée elle s\'efface', () => {
    reader.question(0, null);
    expect(reader.state().scene?.data?.question).toBeTrue();
    reader.questionResolved(10);
    expect(reader.state().scene).toBeNull();
  });

  it('S14 : une puce par question, mise à jour sur la même scène', () => {
    reader.questions([{ libelle: 'Le DNS', state: 'EN_COURS' }, { libelle: 'Le TTL', state: 'EN_ATTENTE' }], 0);
    expect(reader.state().scene?.libelle).toBe('Points de route · 0/2');
    reader.questions([{ libelle: 'Le DNS', state: 'TERMINEE' }, { libelle: 'Le TTL', state: 'EN_COURS' }], 100);
    const scene = reader.state().scene!;
    expect(scene.libelle).toBe('Points de route · 1/2');
    expect(scene.data?.questions?.map((q) => q.state)).toEqual(['TERMINEE', 'EN_COURS']);
  });
});

describe('F-234 / SF-234-06 — rendu', () => {
  let bus: JarvisSceneBus;
  let now = 0;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [JarvisSceneComponent] });
    bus = TestBed.inject(JarvisSceneBus);
    bus.setUniverse(testUniverse());
    bus.setSubject('s-forge');
    bus.useClock(() => now);
    now = 0;
  });

  afterEach(() => bus.ngOnDestroy());

  it('S9 : losange, phase, étape cochée, 2/5 → 3/5', () => {
    const fixture = TestBed.createComponent(JarvisSceneComponent);
    bus.turnStarted();
    bus.action({ type: 'update_plan_step', path: 'chantier 3 · étape 3 · faite' });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-scene="S9"] .chantier__name')?.textContent).toContain('Ch. 3 — Bascule DNS');
    expect(el.querySelector('.chantier__phase')?.textContent).toContain('Exécution');
    expect(el.querySelector('.progress__count')?.textContent).toBe('3/5');
    expect(el.querySelector('.stepline')?.classList).toContain('is-done');
    bus.aborted();
  });

  it('S2 : compte à rebours écrit ; S11 : la personne et le chantier', () => {
    const fixture = TestBed.createComponent(JarvisSceneComponent);
    bus.turnStarted();
    bus.confirm('Envoyer la note ?', 42_000);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-scene="S2"] .ask__clock')?.textContent).toBe('0:42');
    bus.confirmResolved();
    now = 5000;
    bus.action({ type: 'record_blocker', path: 'Zahi · validation' });
    bus.tick();
    fixture.detectChanges();
    expect(el.querySelector('[data-scene="S11"] .attente__name')?.textContent).toBe('Zahi');
    expect(el.querySelector('.attente__chantier')?.textContent).toBe('Ch. 3');
    bus.aborted();
  });
});
