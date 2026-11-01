import { TestBed } from '@angular/core/testing';

import { JarvisSceneBus } from './jarvis-scene-bus.service';
import { JarvisSceneComponent } from './jarvis-scene.component';
import { testUniverse } from './jarvis-universe.fixture';

describe('F-234 / SF-234-02 — scène générique à l\'écran', () => {
  let bus: JarvisSceneBus;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [JarvisSceneComponent] });
    bus = TestBed.inject(JarvisSceneBus);
    bus.setUniverse(testUniverse());
    bus.setSubject('s-forge');
  });

  afterEach(() => bus.ngOnDestroy());

  it('rend le libellé, la rafale, l\'entité en surbrillance et le détail masqué ; rien hors tour', () => {
    const fixture = TestBed.createComponent(JarvisSceneComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.scene')).toBeNull();

    bus.turnStarted();
    bus.action({ type: 'teams_read_conversation', path: 'token=abcdef123' });
    bus.action({ type: 'teams_read_conversation', path: 'fil 2' });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.scene__title')?.textContent).toContain('Teams · read conversation');
    expect(el.querySelector('.scene__count')?.textContent).toContain('× 2');
    expect(el.querySelector('.scene__entity')?.textContent).toContain('Migration Forge');
    expect(el.querySelector('.scene')?.getAttribute('aria-hidden')).toBe('true');
    expect(el.textContent).not.toContain('abcdef123');
    bus.aborted();
  });
});
