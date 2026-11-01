import { TestBed } from '@angular/core/testing';

import { NameIndex } from './jarvis-name-matcher';
import { JarvisSceneBus } from './jarvis-scene-bus.service';
import { JarvisSceneComponent } from './jarvis-scene.component';
import { JarvisSceneReader } from './jarvis-scene-reader';
import { resolveScene } from './jarvis-scene-registry';
import { testUniverse } from './jarvis-universe.fixture';

describe('F-234 / SF-234-09 — S7 atelier de création, S5b regarder une vidéo', () => {
  let reader: JarvisSceneReader;

  beforeEach(() => {
    reader = new JarvisSceneReader(new NameIndex(testUniverse()), 's-forge');
    reader.startTurn();
  });

  it('le registre donne S7 et S5b', () => {
    expect(resolveScene('page_publish').scene).toBe('S7');
    expect(resolveScene('render_diagram').scene).toBe('S7');
    expect(resolveScene('video_render').scene).toBe('S7');
    expect(resolveScene('video_images').scene).toBe('S5b');
  });

  it('page_publish ⇒ le titre de la page qui se compose ; l\'événement page le confirme', () => {
    reader.action({ type: 'page_publish', path: 'Bilan de la migration' }, 0);
    expect(reader.state().scene?.data).toEqual(jasmine.objectContaining({ creation: 'page', title: 'Bilan de la migration' }));
    reader.created('page', 'Bilan de la migration v2', 100);
    expect(reader.state().scene?.libelle).toBe('Page publiée');
    expect(reader.state().scene?.data?.title).toBe('Bilan de la migration v2');
  });

  it('video_render_status répété ⇒ une seule salle de montage, avancement compté, même espacé', () => {
    reader.action({ type: 'video', path: 'rendu vidéo : cours-dns' }, 0);
    reader.action({ type: 'video', path: 'suivi du rendu' }, 1_000);
    reader.tick(20_000);
    reader.action({ type: 'video', path: 'suivi du rendu' }, 30_000);
    const scene = reader.state().scene!;
    expect(scene.id).toBe('S7');
    expect(scene.libelle).toBe('Salle de montage');
    expect(scene.data).toEqual(jasmine.objectContaining({ creation: 'video', title: 'cours-dns', checks: 2 }));
  });

  it('video_images ⇒ la pellicule et le nom du fichier', () => {
    reader.action({ type: 'video', path: 'extraction des plans + son · demo.mp4' }, 0);
    const scene = reader.state().scene!;
    expect(scene.id).toBe('S5b');
    expect(scene.libelle).toBe('Regarder une vidéo');
    expect(scene.data).toEqual(jasmine.objectContaining({ film: 'demo.mp4', extract: 'extraction des plans + son' }));
  });
});

describe('F-234 / SF-234-09 — rendu', () => {
  let bus: JarvisSceneBus;
  let now = 0;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [JarvisSceneComponent] });
    bus = TestBed.inject(JarvisSceneBus);
    bus.setUniverse(testUniverse());
    bus.useClock(() => now);
    now = 0;
  });

  afterEach(() => bus.ngOnDestroy());

  it('la page sous son titre ; la pellicule et le fichier', () => {
    const fixture = TestBed.createComponent(JarvisSceneComponent);
    bus.turnStarted();
    bus.action({ type: 'page_publish', path: 'Bilan' });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-scene="S7"] .craft[data-creation="page"] .craft__title')?.textContent).toContain('Bilan');
    now = 5000;
    bus.action({ type: 'video', path: 'extraction des plans · demo.mp4' });
    bus.tick();
    fixture.detectChanges();
    expect(el.querySelector('[data-scene="S5b"] .reel__film')?.textContent).toBe('demo.mp4');
    bus.aborted();
  });
});
