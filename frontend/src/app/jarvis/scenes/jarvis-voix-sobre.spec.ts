import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { JarvisConstellationComponent } from './jarvis-constellation.component';
import { NameIndex } from './jarvis-name-matcher';
import { JarvisSceneBus, SOBER_KEY } from './jarvis-scene-bus.service';
import { JarvisSceneComponent } from './jarvis-scene.component';
import { CUT_FREEZE_MS, JarvisSceneReader } from './jarvis-scene-reader';
import { testUniverse } from './jarvis-universe.fixture';

describe('F-234 / SF-234-11 — voix, mode sobre, mesure', () => {
  let reader: JarvisSceneReader;

  beforeEach(() => {
    reader = new JarvisSceneReader(new NameIndex(testUniverse()), 's-forge');
    reader.startTurn();
  });

  it('coupure ⇒ scène figée ≤ 1 s, rien ne la remplace, puis effacée', () => {
    reader.action({ type: 'bash', path: 'ls' }, 0);
    reader.cut(100);
    expect(reader.state().frozen).toBeTrue();
    reader.action({ type: 'read', path: 'a.ts' }, 200);
    expect(reader.state().scene?.id).toBe('S3');
    reader.tick(100 + CUT_FREEZE_MS);
    expect(reader.state().scene).toBeNull();
    expect(reader.state().frozen).toBeFalse();
  });

  it('nom surligné pendant la phrase qui le contient', () => {
    reader.saying('Le chantier avance chez CAGIP.', 0);
    expect(reader.state().said).toEqual(['h-cagip']);
    expect(reader.state().lit.map((l) => l.node.key)).toContain('h-cagip');
    reader.saying('Rien d’autre.', 500);
    expect(reader.state().said).toEqual([]);
  });

  it('mesure sans contenu : scènes par identifiant, part générique, nœuds, Montrer ; remise à zéro', () => {
    reader.action({ type: 'bash', path: 'ls' }, 0);
    reader.action({ type: 'inconnu_outil', path: 'CAGIP' }, 5000);
    reader.text('🖥 montrer: clients\n', 6000);
    const stats = reader.takeStats();
    expect(stats.scenes).toEqual({ S3: 1, GENERIQUE: 1 });
    expect(stats.lit).toBe(1);
    expect(stats.shows).toBe(1);
    expect(JSON.stringify(stats)).not.toContain('CAGIP');
    expect(reader.takeStats()).toEqual({ scenes: {}, lit: 0, shows: 0 });
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

describe('F-234 / SF-234-11 — mode sobre à l\'écran', () => {
  let bus: JarvisSceneBus;

  afterEach(() => {
    bus.ngOnDestroy();
    try {
      localStorage.removeItem(SOBER_KEY);
    } catch {
      // rien
    }
  });

  it('sobre ⇒ ni constellation ni scène, le fil d\'Ariane reste ; persistant sur l\'appareil', () => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    bus = TestBed.inject(JarvisSceneBus);
    bus.setUniverse(testUniverse());
    bus.useClock(() => 0);
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    bus.turnStarted();
    bus.action({ type: 'bash', path: 'ls' });
    bus.setSober(true);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.cst__map')?.classList).toContain('is-sober');
    expect(el.querySelector('.scene')).toBeNull();
    expect(el.querySelector('.cst__crumbs')).not.toBeNull();
    expect(localStorage.getItem(SOBER_KEY)).toBe('1');
    bus.aborted();
  });

  it('rendu correct si le stockage lève', () => {
    spyOn(Storage.prototype, 'getItem').and.throwError('bloqué');
    spyOn(Storage.prototype, 'setItem').and.throwError('bloqué');
    TestBed.configureTestingModule({ imports: [HostComponent] });
    bus = TestBed.inject(JarvisSceneBus);
    expect(bus.sober()).toBeFalse();
    bus.setSober(true);
    expect(bus.sober()).toBeTrue();
  });
});
