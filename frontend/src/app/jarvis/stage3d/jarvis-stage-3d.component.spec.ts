import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed, fakeAsync, flush, flushMicrotasks, tick } from '@angular/core/testing';

import { BancParams } from './jarvis-banc';
import { JARVIS_3D_LOADER, JarvisEngineFactory } from './jarvis-engine.contract';
import {
  ContextLossPolicy, JARVIS_RENDER_PROBE, JARVIS_VIEW_KEY, RenderProbe,
} from './jarvis-render-capability';
import { JARVIS_BANC, JARVIS_CONTEXT_LOSS, JARVIS_FRAME_HOST, JarvisStage3dComponent } from './jarvis-stage-3d.component';
import { FakeEngine, FakeFrameHost, NO_WEBGL_PROBE, PC_PROBE, fakeEngineFactory } from './jarvis-stage3d.testing';

@Component({
  imports: [JarvisStage3dComponent],
  template: '<app-jarvis-stage-3d #stage [sober]="sober()" />',
})
class HostComponent {
  readonly sober = signal(false);
}

describe('F-236 / SF-236-01 — JarvisStage3dComponent (socle, repli 2D, fondu)', () => {
  let loader: jasmine.Spy<() => Promise<JarvisEngineFactory>>;
  let created: FakeEngine[];
  let losses: ContextLossPolicy;

  function configure(probe: RenderProbe, banc: BancParams | null = null): void {
    created = [];
    losses = new ContextLossPolicy();
    loader = jasmine.createSpy('loader').and.callFake(() => Promise.resolve(fakeEngineFactory(created)));
    TestBed.configureTestingModule({
      imports: [HostComponent],
      providers: [
        { provide: JARVIS_RENDER_PROBE, useValue: () => probe },
        { provide: JARVIS_3D_LOADER, useValue: loader },
        { provide: JARVIS_FRAME_HOST, useValue: () => new FakeFrameHost() },
        { provide: JARVIS_CONTEXT_LOSS, useValue: losses },
        { provide: JARVIS_BANC, useValue: banc },
      ],
    });
  }

  function stageOf(fixture: ComponentFixture<HostComponent>): JarvisStage3dComponent {
    return fixture.debugElement.children[0].componentInstance as JarvisStage3dComponent;
  }

  beforeEach(() => {
    localStorage.removeItem(JARVIS_VIEW_KEY);
    FakeEngine.live = 0;
  });

  afterEach(() => localStorage.removeItem(JARVIS_VIEW_KEY));

  it('chaque cause de repli ⇒ 2D, le morceau 3D n’est JAMAIS demandé, aucun canevas', fakeAsync(() => {
    const causes: [string, RenderProbe, () => void][] = [
      ['no_webgl', NO_WEBGL_PROBE, () => undefined],
      ['perf_caveat', { ...PC_PROBE, majorPerformanceCaveat: true }, () => undefined],
      ['reduced_motion', { ...PC_PROBE, reducedMotion: true }, () => undefined],
      ['save_data', { ...PC_PROBE, saveData: true }, () => undefined],
      ['manual', PC_PROBE, () => localStorage.setItem(JARVIS_VIEW_KEY, '2d')],
    ];
    for (const [reason, probe, before] of causes) {
      TestBed.resetTestingModule();
      before();
      configure(probe);
      const fixture = TestBed.createComponent(HostComponent);
      fixture.detectChanges();
      flush();
      expect(loader).withContext(reason).not.toHaveBeenCalled();
      expect(stageOf(fixture).decision()).withContext(reason).toEqual(jasmine.objectContaining({ mode: '2d', reason }));
      expect(fixture.nativeElement.querySelector('canvas')).withContext(reason).toBeNull();
      expect(fixture.nativeElement.querySelector('app-jarvis-stage-3d').getAttribute('data-render')).toBe('2d');
      fixture.destroy();
      localStorage.removeItem(JARVIS_VIEW_KEY);
    }
  }));

  it('mode sobre ⇒ 2D, morceau 3D jamais demandé', fakeAsync(() => {
    configure(PC_PROBE);
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.sober.set(true);
    fixture.detectChanges();
    flush();
    expect(loader).not.toHaveBeenCalled();
    expect(stageOf(fixture).decision()).toEqual({ mode: '2d', reason: 'sober' });
  }));

  it('3D possible ⇒ chargement unique, canevas posé, fondu à la première image seulement', fakeAsync(() => {
    configure(PC_PROBE);
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    flushMicrotasks();
    expect(loader).toHaveBeenCalledTimes(1);
    expect(created.length).toBe(1);
    const engine = created[0];
    expect(engine.options.level).toBe('high');
    expect(engine.options.withCore).toBeFalse();
    expect(engine.started).toBe(1);
    expect(fixture.nativeElement.querySelector('canvas')).not.toBeNull();
    const stage = stageOf(fixture);
    expect(stage.shown()).toBeFalse();
    engine.firstFrame();
    fixture.detectChanges();
    expect(stage.shown()).toBeTrue();
    const host = fixture.nativeElement.querySelector('app-jarvis-stage-3d') as HTMLElement;
    expect(host.classList).toContain('jv3d--shown');
    expect(host.getAttribute('data-render')).toBe('3d');
    expect(host.getAttribute('aria-hidden')).toBe('true');
    fixture.destroy();
    expect(engine.disposed).toBeTrue();
  }));

  it('« Vue 2D » : fondu de sortie (400 ms) puis libération ; « Vue 3D » recharge ; choix retenu', fakeAsync(() => {
    configure(PC_PROBE);
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    flushMicrotasks();
    created[0].firstFrame();
    const stage = stageOf(fixture);
    stage.toggleView();
    fixture.detectChanges();
    expect(localStorage.getItem(JARVIS_VIEW_KEY)).toBe('2d');
    expect(stage.shown()).toBeFalse();
    expect(created[0].disposed).toBeFalse();
    tick(399);
    expect(created[0].disposed).toBeFalse();
    tick(1);
    expect(created[0].disposed).toBeTrue();
    expect(fixture.nativeElement.querySelector('canvas')).toBeNull();
    stage.toggleView();
    fixture.detectChanges();
    flushMicrotasks();
    expect(localStorage.getItem(JARVIS_VIEW_KEY)).toBeNull();
    expect(loader).toHaveBeenCalledTimes(2);
    expect(created.length).toBe(2);
    fixture.destroy();
  }));

  it('repasser en 3D pendant le fondu de sortie annule la libération (aucune double instance)', fakeAsync(() => {
    configure(PC_PROBE);
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    flushMicrotasks();
    created[0].firstFrame();
    fixture.componentInstance.sober.set(true);
    fixture.detectChanges();
    tick(200);
    fixture.componentInstance.sober.set(false);
    fixture.detectChanges();
    flush();
    expect(created.length).toBe(1);
    expect(created[0].disposed).toBeFalse();
    expect(stageOf(fixture).shown()).toBeTrue();
    fixture.destroy();
  }));

  it('10 ouvertures / fermetures ⇒ aucune instance de moteur restante', fakeAsync(() => {
    configure(PC_PROBE);
    for (let i = 0; i < 10; i++) {
      const fixture = TestBed.createComponent(HostComponent);
      fixture.detectChanges();
      flushMicrotasks();
      created[created.length - 1].firstFrame();
      fixture.destroy();
    }
    expect(created.length).toBe(10);
    expect(FakeEngine.live).toBe(0);
    expect(created.every((e) => e.disposed)).toBeTrue();
  }));

  it('fermé avant la fin du chargement ⇒ aucun moteur créé', fakeAsync(() => {
    configure(PC_PROBE);
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    fixture.destroy();
    flushMicrotasks();
    expect(created.length).toBe(0);
  }));

  it('échec du chargement du morceau 3D ⇒ le 2D reste, sans erreur', fakeAsync(() => {
    configure(PC_PROBE);
    loader.and.returnValue(Promise.reject(new Error('réseau')));
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    flushMicrotasks();
    expect(stageOf(fixture).shown()).toBeFalse();
    expect(fixture.nativeElement.querySelector('canvas')).toBeNull();
  }));

  it('T11 : perte ⇒ 2D immédiat ; restauration ⇒ 3D ; seconde perte ⇒ 2D pour la session, moteur libéré', fakeAsync(() => {
    configure(PC_PROBE);
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const engine = created[0];
    engine.firstFrame();
    const stage = stageOf(fixture);
    engine.options.onContextLost();
    fixture.detectChanges();
    expect(stage.shown()).toBeFalse();
    expect(stage.decision()).toEqual({ mode: '2d', reason: 'context_lost' });
    expect(engine.disposed).toBeFalse();
    engine.options.onContextRestored();
    fixture.detectChanges();
    expect(stage.decision().mode).toBe('3d');
    expect(stage.shown()).toBeTrue();
    expect(engine.started).toBe(2);
    engine.options.onContextLost();
    fixture.detectChanges();
    flush();
    expect(stage.shown()).toBeFalse();
    expect(engine.disposed).toBeTrue();
    expect(stage.offer3d()).toBeFalse();
    expect(stage.decision()).toEqual({ mode: '2d', reason: 'context_lost' });
    expect(created.length).toBe(1);
  }));

  it('banc : rendu logiciel admis, horloge figée à t', fakeAsync(() => {
    configure({ ...PC_PROBE, majorPerformanceCaveat: true }, { scene: 'coeur', t: 1200, level: 'medium' });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    flushMicrotasks();
    expect(created.length).toBe(1);
    expect(created[0].options.clock()).toBe(1200);
    expect(created[0].options.level).toBe('medium');
    fixture.destroy();
  }));
});
