import { Component, signal } from '@angular/core';
import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { JarvisConstellationComponent } from './jarvis-constellation.component';
import { CALLING_MS, JARVIS_UNIVERSE_URL, JarvisSceneBus } from './jarvis-scene-bus.service';
import { JarvisSceneComponent } from './jarvis-scene.component';
import { testUniverse } from './jarvis-universe.fixture';

@Component({
  imports: [JarvisConstellationComponent, JarvisSceneComponent],
  template: `<app-jarvis-constellation [subjectId]="subject()"><button class="orb">orbe</button></app-jarvis-constellation>
             <app-jarvis-scene />`,
})
class HostComponent {
  readonly subject = signal<string | null>('s-forge');
}

describe('F-234 / SF-234-10 — ambiance hors tour', () => {
  let bus: JarvisSceneBus;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HostComponent], providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    bus = TestBed.inject(JarvisSceneBus);
    http = TestBed.inject(HttpTestingController);
    bus.setUniverse(testUniverse());
    bus.setSubject('s-forge');
    bus.useClock(() => 0);
  });

  afterEach(() => bus.ngOnDestroy());

  it('aucune scène d\'ambiance pendant un tour ; au repos, le coût (administrateur) s\'écrit', () => {
    bus.turnStarted();
    expect(bus.ambient().cost).toBeNull();
    expect(bus.ambient().liveSubjects.size).toBe(0);
    bus.done('0,42');
    http.expectOne(JARVIS_UNIVERSE_URL).flush(testUniverse());
    expect(bus.ambient().cost).toBe('0,42');
    bus.turnStarted();
    expect(bus.ambient().cost).toBeNull();
    bus.done(null);
    http.expectOne(JARVIS_UNIVERSE_URL).flush(testUniverse());
    expect(bus.ambient().cost).toBeNull(); // costEur absent (non-admin) ⇒ rien
    http.verify();
  });

  it('A2 : un autre sujet actif au registre relevé respire ; le sujet courant non', () => {
    bus.liveTerminals(['s-doc', 's-forge']);
    expect([...bus.ambient().liveSubjects].sort()).toEqual(['s-doc']);
  });

  it('A3 : QUESTION_ASKED d\'un autre sujet ⇒ il clignote une fois ; le premier relevé n\'est pas une nouveauté', fakeAsync(() => {
    bus.notifications([{ id: 'n1', event: 'QUESTION_ASKED', workspaceId: 's-doc' }]);
    expect(bus.ambient().calling).toBeNull();
    bus.notifications([{ id: 'n1', event: 'QUESTION_ASKED', workspaceId: 's-doc' },
      { id: 'n2', event: 'QUESTION_ASKED', workspaceId: 's-doc' }]);
    expect(bus.ambient().calling).toEqual({ subjectId: 's-doc', label: 'Doc archi' });
    tick(CALLING_MS);
    expect(bus.ambient().calling).toBeNull();
    bus.notifications([{ id: 'n3', event: 'QUESTION_ASKED', workspaceId: 's-forge' }]);
    expect(bus.ambient().calling).toBeNull(); // le sujet courant ne s'appelle pas lui-même
  }));

  it('A1 : la porte du runner ⇒ le poste s\'assombrit ; A5 : la veille sonne sur son client', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    http.match(JARVIS_UNIVERSE_URL).forEach((r) => r.flush(testUniverse()));
    bus.turnStarted();
    bus.aborted('runner_offline');
    bus.alert('s-runner', 'Veille Jira — 2 changements');
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.cst__client[data-key="h-cagip"]')?.classList).toContain('is-down');
    expect(el.querySelector('.cst__client[data-key="h-eden"]')?.classList).toContain('is-ringing');
    expect(el.querySelector('.cst__wave')).not.toBeNull();
    const ambient = el.querySelector('[data-scene="AMBIANCE"]')?.textContent ?? '';
    expect(ambient).toContain('Le poste CAGIP ne répond pas');
    expect(ambient).toContain('Veille Jira — 2 changements · Edenred');
  });
});
