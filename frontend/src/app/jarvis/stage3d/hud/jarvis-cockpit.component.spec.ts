import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { IDLE_SCREEN, ScreenSignals } from '../jarvis-stage-director';
import { COCKPIT_FOLDS_KEY } from './jarvis-cockpit-layout';
import { SessionSnapshot } from '../scenes3d/jarvis-session-memory';
import { of, throwError } from 'rxjs';

import {
  CockpitStage, JARVIS_DAY_HISTORY, JARVIS_WATCHES, JarvisCockpitComponent, universeItems,
} from './jarvis-cockpit.component';

/** Une scène 3D simulée : affichée ou non, téléphone ou PC, positions des nœuds à l'écran. */
export class FakeCockpitStage implements CockpitStage {
  readonly shown = signal(true);
  isNarrow = false;
  readonly positions = new Map<string, { x: number; y: number }>();
  session?: () => SessionSnapshot;
  look?: (key: string) => void;
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
    const labels = [...el.querySelectorAll('section[aria-labelledby="ck-univers"] .ck__item .ck__label')].map((n) => n.textContent?.trim());
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

  it('SF-236-16 — « Aujourd’hui » : chiffres de l’univers ; toucher un chiffre émet son filtre (aucun tour) ; enfoncé', () => {
    mount();
    const shown: string[] = [];
    cockpit.show.subscribe((f) => shown.push(f));
    const figure = (id: string) => fixture.nativeElement.querySelector(`.ck__figure[data-figure="${id}"]`) as HTMLButtonElement;
    expect(figure('postes').textContent).toContain('1/2');
    expect(figure('attentes').textContent).toContain('1');
    figure('attentes').click();
    expect(shown).toEqual(['chantiers-bloques']);
    expect(figure('alertes').disabled).toBeTrue();
    fixture.componentRef.setInput('shownFilter', 'chantiers-bloques');
    fixture.detectChanges();
    expect(figure('attentes').getAttribute('aria-pressed')).toBe('true');
    expect(figure('postes').getAttribute('aria-pressed')).toBe('false');
  });

  it('SF-236-16 — météo : une ligne par chantier, ciel nommé et mot écrit ; filament vers le losange', () => {
    mount();
    const row = fixture.nativeElement.querySelector('.ck__weather') as HTMLElement;
    expect(row.getAttribute('data-weather')).toBe('nuage');
    expect(row.querySelector('.ck__sky')?.getAttribute('aria-label')).toBe('Nuageux');
    expect(row.textContent).toContain('attend Zahi');
    expect(row.textContent).toContain('CAGIP · Migration Forge');
    stage.positions.set('s-forge#3', { x: 700, y: 300 });
    cockpit.link('s-forge#3', { currentTarget: row } as unknown as Event, 'left');
    cockpit.tick();
    expect((fixture.nativeElement.querySelector('.ck__filament') as SVGLineElement).getAttribute('x2')).toBe('700.0');
    cockpit.unlink();
  });

  it('SF-236-17 — arc de perles du tour : une perle par appel, teinte de la famille', () => {
    mount({ ...screen, pulses: 0, scene: null });
    fixture.componentRef.setInput('signals', { ...screen, pulses: 2, scene: { id: 'S3', key: 'k', libelle: 'Commande',
      count: 1, detail: null, entity: null, pinned: false, since: 0, updatedAt: 0 } });
    fixture.detectChanges();
    const pearls = fixture.nativeElement.querySelectorAll('.ck__pearl');
    expect(pearls.length).toBe(2);
    expect(pearls[0].getAttribute('data-tone')).toBe('normal');
  });

  it('SF-236-17 — la journée : jamais pendant un tour ; à la demande hors tour ; résumé d’un tour touché', () => {
    const history = jasmine.createSpy('history').and.returnValue(of([
      { id: 'u1', role: 'USER', content: 'Vérifie les pods', createdAt: new Date().toISOString() },
      { id: 'a1', role: 'ASSISTANT', content: 'Tout va bien. Trois pods.', createdAt: new Date().toISOString(),
        terminal: { blocks: [{ tool: 'bash' }], omittedBlocks: 0 } },
    ]));
    TestBed.overrideProvider(JARVIS_DAY_HISTORY, { useValue: history });
    mount();
    const load = fixture.nativeElement.querySelector('.ck__load') as HTMLButtonElement;
    expect(load.disabled).toBeTrue();
    cockpit.loadDay();
    expect(history).not.toHaveBeenCalled();
    fixture.componentRef.setInput('signals', { ...screen, turnActive: false });
    fixture.detectChanges();
    expect(load.disabled).toBeFalse();
    load.click();
    fixture.detectChanges();
    expect(history).toHaveBeenCalledOnceWith('s-forge');
    const turn = fixture.nativeElement.querySelector('.ck__turn') as HTMLButtonElement;
    expect(turn.textContent).toContain('Vérifie les pods');
    turn.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.ck__summary')?.textContent).toContain('Tout va bien.');
  });

  it('SF-236-22 — « Rejouer ce tour » : le tour déjà lu est émis (aucun nouvel appel), refusé pendant un tour', () => {
    const history = jasmine.createSpy('history').and.returnValue(of([
      { id: 'u1', role: 'USER', content: 'Vérifie les pods', createdAt: new Date().toISOString() },
      { id: 'a1', role: 'ASSISTANT', content: 'Tout va bien.', createdAt: new Date().toISOString(),
        terminal: { blocks: [{ tool: 'bash', command: 'kubectl get pods', output: '', hasOutput: false }],
          omittedBlocks: 0 } },
    ]));
    TestBed.overrideProvider(JARVIS_DAY_HISTORY, { useValue: history });
    mount({ ...screen, turnActive: false });
    const emitted: unknown[] = [];
    cockpit.replay.subscribe((t) => emitted.push(t));
    cockpit.loadDay();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.ck__turn') as HTMLButtonElement).click();
    fixture.detectChanges();
    const button = fixture.nativeElement.querySelector('.ck__replay') as HTMLButtonElement;
    expect(button.textContent).toContain('Rejouer ce tour');
    button.click();
    expect(emitted.length).toBe(1);
    expect((emitted[0] as { id: string }).id).toBe('u1');
    expect(history).toHaveBeenCalledTimes(1);
    fixture.componentRef.setInput('signals', { ...screen, turnActive: true });
    cockpit.replayTurn(cockpit.day()!.turns[0]);
    expect(emitted.length).toBe(1);
  });

  it('SF-236-25 — horloge des veilles : lue une fois hors tour, la prochaine en surbrillance ; illisible ⇒ rien', () => {
    const watches = jasmine.createSpy('watches').and.returnValue(of([{ workspaceName: 'Migration Forge',
      watch: { id: 'w1', name: 'Tickets FORGE', active: true, proposed: false, runTimes: ['07:30', '13:30'] } }]));
    TestBed.overrideProvider(JARVIS_WATCHES, { useValue: watches });
    mount(); // un tour tourne : rien n'est lu
    expect(watches).not.toHaveBeenCalled();
    fixture.componentRef.setInput('signals', { ...screen, turnActive: false });
    fixture.detectChanges();
    expect(watches).toHaveBeenCalledTimes(1);
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.ck__clock .ck__title')?.textContent).toContain('Horloge des veilles');
    expect(el.querySelectorAll('.ck__clock-day').length).toBe(5);
    expect(el.querySelectorAll('.ck__clock-mark--next').length).toBe(1);
    expect(el.querySelector('.ck__clock-next')?.textContent).toContain('Tickets FORGE');
    fixture.componentRef.setInput('signals', { ...screen, turnActive: false, pulses: 9 });
    fixture.detectChanges();
    expect(watches).toHaveBeenCalledTimes(1);
  });

  it('SF-236-25 — veilles illisibles ⇒ aucune section, aucune erreur', () => {
    TestBed.overrideProvider(JARVIS_WATCHES, { useValue: () => of({ pas: 'une liste' }) });
    mount({ ...screen, turnActive: false });
    expect(fixture.nativeElement.querySelector('.ck__clock')).toBeNull();
  });

  it('SF-236-17 — journée illisible ⇒ message, aucune erreur', () => {
    TestBed.overrideProvider(JARVIS_DAY_HISTORY, { useValue: () => throwError(() => new Error('500')) });
    mount({ ...screen, turnActive: false });
    cockpit.loadDay();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('La journée n\'a pas pu être lue.');
  });

  it('SF-236-18 — personnes, étagère, mur de tickets (session de la scène) ; détacher ⇒ tableau ancré au client', () => {
    mount();
    stage.session = () => ({ tickets: [{ key: 'FORGE-42', source: 'Jira', clientKey: 'h-cagip', seenAt: 1 }],
      files: [] });
    fixture.componentRef.setInput('signals', { ...screen, pulses: 4 });
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.ck__item[data-key="h-cagip:zahi"]')?.textContent).toContain('attendue · message prêt');
    expect(el.textContent).toContain('pour Zahi');
    expect(el.querySelector('.ck__ticket')?.textContent?.trim()).toBe('FORGE-42');
    cockpit.detach('personnes');
    fixture.detectChanges();
    const card = el.querySelector('.ck__float[data-float="personnes"]') as HTMLElement;
    expect(card).not.toBeNull();
    expect(el.textContent).toContain('Posé dans l\'univers.');
    stage.positions.set('h-cagip', { x: 700, y: 500 });
    cockpit.floatTick();
    expect(card.style.transform).toContain('translate(');
    expect(card.style.transform).toContain('740px');
    (card.querySelector('.ck__detach') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.ck__float')).toBeNull();
  });

  it('SF-236-19 — inspecteur : une ligne touchée ouvre la fiche ; un lien ouvre la sienne et la caméra le regarde', () => {
    mount();
    const looked: string[] = [];
    stage.look = (key) => looked.push(key);
    (fixture.nativeElement.querySelector('.ck__item[data-key="h-cagip"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.ck__card-title')?.textContent).toBe('CAGIP');
    (el.querySelector('.ck__inspector .ck__item[data-key="h-cagip:zahi"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.ck__card-title')?.textContent).toBe('Zahi Ben');
    expect(looked).toEqual(['h-cagip:zahi']);
    (el.querySelector('.ck__inspector .ck__detach') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.ck__inspector')).toBeNull();
  });

  it('SF-236-19 — recherche au clavier : « / » donne le focus, ↓ Entrée applique, Échap efface ; aucun tour', () => {
    mount();
    const shows: unknown[] = [];
    const looked: string[] = [];
    cockpit.showSearch.subscribe((s) => shows.push(s));
    stage.look = (key) => looked.push(key);
    const input = fixture.nativeElement.querySelector('.ck__search-input') as HTMLInputElement;
    window.dispatchEvent(new KeyboardEvent('keydown', { key: '/' }));
    expect(document.activeElement).toBe(input);
    input.value = 'bloqué';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(shows).toEqual([{ filter: 'chantiers-bloques', client: null }]);
    cockpit.setQuery('ingress');
    fixture.detectChanges();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(looked).toEqual(['s-dup2']);
    expect(cockpit.inspected()?.key).toBe('s-dup2');
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    expect(cockpit.query()).toBe('');
  });

  it('SF-236-20 — téléphone : aucun panneau dans la scène ; « ☰ Vues » ouvre un volet, une vue à la fois', () => {
    mount(screen, 390);
    stage.isNarrow = true;
    cockpit.width.set(391);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('aside')).toBeNull();
    expect(el.querySelector('.ck__float')).toBeNull();
    expect(cockpit.insets()).toEqual({ left: 0, right: 0 });
    (el.querySelector('.ck__views') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.ck__volet')).not.toBeNull();
    expect(el.querySelector('.ck__volet .ck__figure')).not.toBeNull();
    expect(el.querySelector('.ck__detach:not([aria-label="Fermer les vues"])')).toBeNull();
    const tabs = el.querySelectorAll('.ck__tab') as NodeListOf<HTMLButtonElement>;
    tabs[1].click();
    fixture.detectChanges();
    expect(el.querySelector('.ck__volet .ck__weather')).not.toBeNull();
    expect(el.querySelector('.ck__volet .ck__figure')).toBeNull();
    expect(tabs[1].getAttribute('aria-current')).toBe('page');
    (el.querySelector('[aria-label="Fermer les vues"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('.ck__volet')).toBeNull();
  });

  it('univers vide ⇒ liste vide, message écrit', () => {
    expect(universeItems(null, null)).toEqual([]);
    mount({ ...IDLE_SCREEN });
    expect(fixture.nativeElement.textContent).toContain('Aucun poste lu');
  });
});
