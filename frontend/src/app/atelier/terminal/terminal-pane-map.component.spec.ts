import { ComponentFixture, TestBed } from '@angular/core/testing';

import { OpenChantier } from '../../core/models/journey.models';
import { TerminalPaneMapComponent, paneMapEntries } from './terminal-pane-map.component';

/** F-246 / SF-246-01 — la carte des volets : qui est où, toujours visible, et le geste qui rouvre. */
describe('TerminalPaneMapComponent (F-246)', () => {
  const chantier = (number: number, title: string | null = null): OpenChantier => ({
    number, title, openedAt: null, phase: 'EXECUTION', phaseLabel: 'Exécution', focus: false, planVersion: 1,
    validatedVersion: 1, awaitingValidation: false, diagnosisPending: false, closeProposed: false, executing: true,
    done: 0, total: 3,
  });

  let fixture: ComponentFixture<TerminalPaneMapComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TerminalPaneMapComponent] });
    fixture = TestBed.createComponent(TerminalPaneMapComponent);
    fixture.componentRef.setInput('chantiers', [chantier(11, 'CFT prod'), chantier(9, 'Runner'), chantier(10)]);
    fixture.componentRef.setInput('layout', { left: 9, right: 11 });
    fixture.componentRef.setInput('activeLanes', [11]);
    fixture.detectChanges();
  });

  const items = () => [...(fixture.nativeElement as HTMLElement).querySelectorAll('.pane-map__item')] as HTMLElement[];

  it('chaque chantier ouvert, par numéro, dit son volet et son état', () => {
    const texts = items().map((li) => li.textContent!.replace(/\s+/g, ' ').trim());
    expect(texts[0]).toContain('Gauche');
    expect(texts[0]).toContain('Ch.9');
    expect(texts[1]).toContain('Ch.10');
    expect(texts[1]).not.toMatch(/Gauche|Droite/);
    expect(texts[2]).toContain('Droite');
    expect(texts[2]).toContain('tourne');
  });

  it('les boutons disent le volet occupé (aria-pressed) et nomment le chantier', () => {
    const left9 = fixture.nativeElement.querySelector('button[data-chantier="9"][data-side="left"]') as HTMLButtonElement;
    const right9 = fixture.nativeElement.querySelector('button[data-chantier="9"][data-side="right"]') as HTMLButtonElement;
    expect(left9.getAttribute('aria-pressed')).toBe('true');
    expect(right9.getAttribute('aria-pressed')).toBe('false');
    expect(right9.getAttribute('aria-label')).toBe('Afficher le chantier 9 à droite');
  });

  it('rouvrir un chantier non affiché : le geste part avec son numéro et son volet', () => {
    const placed: { number: number; side: string }[] = [];
    fixture.componentInstance.placed.subscribe((e) => placed.push(e));
    (fixture.nativeElement.querySelector('button[data-chantier="10"][data-side="right"]') as HTMLButtonElement).click();
    expect(placed).toEqual([{ number: 10, side: 'right' }]);
  });

  it('paneMapEntries : fonction pure, sans volet ni état pour un chantier rangé', () => {
    expect(paneMapEntries([chantier(10)], { left: 9, right: null }, [])).toEqual(
      [{ number: 10, title: null, side: null, state: null }]);
  });
});
