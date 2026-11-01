import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { JarvisConstellationComponent } from './jarvis-constellation.component';
import { NameIndex } from './jarvis-name-matcher';
import { JarvisSceneBus } from './jarvis-scene-bus.service';
import { JarvisSceneComponent } from './jarvis-scene.component';
import { JarvisSceneReader } from './jarvis-scene-reader';
import { resolveScene } from './jarvis-scene-registry';
import { testUniverse } from './jarvis-universe.fixture';

describe('F-234 / SF-234-08 — S6 web, S10 passation, S12 mémoire, S13 essaim, S15 le point, S16, S17', () => {
  let reader: JarvisSceneReader;

  beforeEach(() => {
    reader = new JarvisSceneReader(new NameIndex(testUniverse()), 's-forge');
    reader.startTurn();
  });

  it('le registre donne les scènes dédiées', () => {
    expect(resolveScene('web').scene).toBe('S6');
    expect(resolveScene('ouvrir_sujet').scene).toBe('S10');
    expect(resolveScene('recall').scene).toBe('S12');
    expect(resolveScene('gouvernance_proposer').scene).toBe('S12');
    expect(resolveScene('explore').scene).toBe('S13');
    expect(resolveScene('sujets_etat').scene).toBe('S15');
    expect(resolveScene('signaler_besoin').scene).toBe('S16');
    expect(resolveScene('veille_poser').scene).toBe('S17');
    // Vague D non livrée (D-PO-7) : Radar et Teams restent sur la générique.
    expect(resolveScene('radar_find_subject').scene).toBe('GENERIQUE');
    expect(resolveScene('teams_read_conversation').scene).toBe('GENERIQUE');
  });

  it('S6 : la requête, ou le domaine seul', () => {
    reader.action({ type: 'web', path: 'kubernetes ingress timeout' }, 0);
    expect(reader.state().scene?.data).toEqual({ address: 'kubernetes ingress timeout', web: 'search' });
    reader.action({ type: 'web', path: 'docs.aws.amazon.com' }, 5000);
    reader.tick(5000);
    expect(reader.state().scene?.data?.web).toBe('page');
  });

  it('S10 : ouvrir_sujet étiqueté ⇒ le fil va vers ce sujet ; l\'événement handoff aussi', () => {
    reader.action({ type: 'ouvrir_sujet', path: 'Doc archi' }, 0);
    expect(reader.state().scene?.data).toEqual(jasmine.objectContaining({
      fromLabel: 'Migration Forge', toLabel: 'Doc archi', toKey: 's-doc',
    }));
    reader.handoff('Doc archi', 's-doc', 100);
    expect(reader.state().scene?.data?.toKey).toBe('s-doc');
    expect(reader.state().lit.map((l) => l.node.key)).toContain('s-doc');
  });

  it('S12 : recall puis l\'événement recall ⇒ « tour 34 » ; le fil se résume', () => {
    reader.action({ type: 'recall', path: 'TTL du DNS' }, 0);
    reader.recalled('tour 34', 200);
    const scene = reader.state().scene!;
    expect(scene.id).toBe('S12');
    expect(scene.data?.repere).toBe('tour 34');
    expect(scene.detail).toBe('TTL du DNS');
    reader.compaction(12, 5000);
    reader.tick(5000);
    expect(reader.state().scene?.libelle).toBe('Le fil se résume');
  });

  it('S13 : 2 explore ⇒ 2 satellites', () => {
    reader.action({ type: 'explore', path: 'Où est configuré le TTL ?' }, 0);
    reader.action({ type: 'explore', path: 'Quels pods redémarrent ?' }, 300);
    const scene = reader.state().scene!;
    expect(scene.id).toBe('S13');
    expect(scene.libelle).toBe('2 explorations déléguées');
    expect(scene.data?.satellites).toEqual(['Où est configuré le TTL ?', 'Quels pods redémarrent ?']);
  });
});

@Component({
  imports: [JarvisConstellationComponent, JarvisSceneComponent],
  template: `<app-jarvis-constellation [subjectId]="subject()"><button class="orb">orbe</button></app-jarvis-constellation>
             <app-jarvis-scene />`,
})
class HostComponent {
  readonly subject = signal<string | null>('s-forge');
}

describe('F-234 / SF-234-08 — rendu', () => {
  let bus: JarvisSceneBus;
  let now = 0;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    bus = TestBed.inject(JarvisSceneBus);
    bus.setUniverse(testUniverse());
    bus.setSubject('s-forge');
    bus.useClock(() => now);
    now = 0;
  });

  afterEach(() => bus.ngOnDestroy());

  it('sujets_etat ⇒ vague sur les sujets du client ; passation ⇒ fil vers le sujet ; barre d\'adresse', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    bus.turnStarted();
    bus.action({ type: 'sujets_etat' });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('.cst__subject.is-wave').length).toBe(5);
    expect(el.querySelector('[data-scene="S15"]')?.textContent).toContain('4 sujets chez CAGIP');

    now = 5000;
    bus.action({ type: 'ouvrir_sujet', path: 'Doc archi' });
    bus.tick();
    fixture.detectChanges();
    expect(el.querySelector('.cst__thread--handoff')).not.toBeNull();
    expect(el.querySelector('[data-scene="S10"] .handoff__to')?.textContent).toBe('Doc archi');

    now = 10000;
    bus.action({ type: 'web', path: 'docs.aws.amazon.com' });
    bus.tick();
    fixture.detectChanges();
    expect(el.querySelector('[data-scene="S6"] .address')?.textContent).toContain('docs.aws.amazon.com');
    bus.aborted();
  });
});
