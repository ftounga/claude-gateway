import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { IDLE_SCREEN, ScreenSignals } from '../jarvis-stage-director';
import { COCKPIT_FOLDS_KEY } from './jarvis-cockpit-layout';
import { CockpitStage, JarvisCockpitComponent, universeItems } from './jarvis-cockpit.component';

/** Une scène 3D simulée : affichée ou non, téléphone ou PC, positions des nœuds à l'écran. */
export class FakeCockpitStage implements CockpitStage {
  readonly shown = signal(true);
  isNarrow = false;
  readonly positions = new Map<string, { x: number; y: number }>();
  project(key: string): { x: number; y: number } | null {
    return this.positions.get(key) ?? null;
  }
}

describe('F-236 / SF-236-15 — JarvisCockpitComponent (panneaux latéraux, repli, filaments)', () => {
  let fixture: ComponentFixture<JarvisCockpitComponent>;
  let cockpit: JarvisCockpitComponent;
  let stage: FakeCockpitStage;
  const screen: ScreenSignals = { ...IDLE_SCREEN, universe: testUniverse(), subjectId: 's-forge', state: 'reflexion',
    turnActive: true, pulses: 3, delegations: 1 };

  function mount(signals: ScreenSignals = screen, width = 1920): void {
    stage = new FakeCockpitStage();
    fixture = TestBed.createComponent(JarvisCockpitComponent);
    cockpit = fixture.componentInstance;
    fixture.componentRef.setInput('stage', stage);
    fixture.componentRef.setInput('signals', signals);
    cockpit.width.set(width);
    fixture.detectChanges();
  }

  beforeEach(() => {
    localStorage.removeItem(COCKPIT_FOLDS_KEY);
    TestBed.configureTestingModule({ imports: [JarvisCockpitComponent] });
  });

  afterEach(() => {
    fixture?.destroy();
    localStorage.removeItem(COCKPIT_FOLDS_KEY);
  });

  it('PC large en 3D : deux panneaux de verre ; liste univers = postes puis sujets (sans terminal du poste)', () => {
    mount();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelectorAll('aside.ck').length).toBe(2);
    const labels = [...el.querySelectorAll('.ck__item .ck__label')].map((n) => n.textContent?.trim());
    expect(labels).toEqual(['CAGIP', 'Migration Forge', 'Doc archi', 'web', 'Ingress', 'Edenred', 'Runner', 'Ingress']);
    expect(el.querySelector('.ck__item--current .ck__label')?.textContent?.trim()).toBe('Migration Forge');
    // La couleur ne porte jamais seule l'information : l'état est écrit.
    expect(el.textContent).toContain('connecté');
    expect(el.textContent).toContain('déconnecté');
    expect(el.textContent).toContain('Réflexion');
  });

  it('rien hors cockpit : 3D non affichée, mode sobre, PC étroit, téléphone', () => {
    mount(screen, 1100);
    expect(fixture.nativeElement.querySelector('aside')).toBeNull();
    expect(cockpit.insets()).toEqual({ left: 0, right: 0 });
    cockpit.width.set(1920);
    stage.shown.set(false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('aside')).toBeNull();
    stage.shown.set(true);
    fixture.componentRef.setInput('sober', true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('aside')).toBeNull();
  });

  it('T-P4 : replier les deux panneaux ⇒ univers pleine largeur ; le repli est retenu par appareil', () => {
    mount();
    expect(cockpit.insets().left).toBeGreaterThan(0);
    const folds = fixture.nativeElement.querySelectorAll('.ck__fold') as NodeListOf<HTMLButtonElement>;
    folds[0].click();
    folds[1].click();
    fixture.detectChanges();
    expect(cockpit.insets()).toEqual({ left: 0, right: 0 });
    expect(fixture.nativeElement.querySelectorAll('.ck--folded').length).toBe(2);
    expect(folds[0].getAttribute('aria-expanded')).toBe('false');
    expect(localStorage.getItem(COCKPIT_FOLDS_KEY)).toBe('gd');
    fixture.destroy();
    mount();
    expect(cockpit.insets()).toEqual({ left: 0, right: 0 });
  });

  it('filament : la ligne survolée est reliée à son nœud à l’écran ; nœud absent ⇒ pas de filament', () => {
    mount();
    stage.positions.set('h-cagip', { x: 900, y: 400 });
    const item = fixture.nativeElement.querySelector('.ck__item[data-key="h-cagip"]') as HTMLElement;
    cockpit.link('h-cagip', { currentTarget: item } as unknown as Event, 'left');
    cockpit.tick();
    const line = fixture.nativeElement.querySelector('.ck__filament') as SVGLineElement;
    expect(line.getAttribute('data-on')).toBe('1');
    expect(line.getAttribute('x2')).toBe('900.0');
    expect(line.getAttribute('y2')).toBe('400.0');
    expect(Number(line.getAttribute('x1'))).toBeCloseTo(item.getBoundingClientRect().right, 0);
    cockpit.link('s-runner', { currentTarget: item } as unknown as Event, 'left');
    cockpit.tick();
    expect(line.getAttribute('data-on')).toBe('0');
    cockpit.unlink();
    expect(line.getAttribute('data-on')).toBe('0');
  });

  it('toucher un sujet de la liste le choisit ; toucher un poste ne navigue pas', () => {
    mount();
    const chosen: string[] = [];
    cockpit.choose.subscribe((id) => chosen.push(id));
    (fixture.nativeElement.querySelector('.ck__item[data-key="s-doc"]') as HTMLButtonElement).click();
    (fixture.nativeElement.querySelector('.ck__item[data-key="h-cagip"]') as HTMLButtonElement).click();
    expect(chosen).toEqual(['s-doc']);
  });

  it('univers vide ⇒ liste vide, message écrit', () => {
    expect(universeItems(null, null)).toEqual([]);
    mount({ ...IDLE_SCREEN });
    expect(fixture.nativeElement.textContent).toContain('Aucun poste lu');
  });
});
