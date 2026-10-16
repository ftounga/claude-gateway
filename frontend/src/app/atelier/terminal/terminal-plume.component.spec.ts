import { ComponentFixture, TestBed } from '@angular/core/testing';

import { PlumeView, TerminalPlumeComponent } from './terminal-plume.component';

/** Le panneau de Plume (F-193 / SF-193-02) : trois phases, deux gestes, aucune requête. */
describe('TerminalPlumeComponent (F-193 / SF-193-02)', () => {
  let fixture: ComponentFixture<TerminalPlumeComponent>;

  function render(view: PlumeView): HTMLElement {
    fixture.componentRef.setInput('view', view);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TerminalPlumeComponent] });
    fixture = TestBed.createComponent(TerminalPlumeComponent);
  });

  it('pendant l’analyse : les sources lues, en statut', () => {
    const el = render({ phase: 'running' });
    expect(el.querySelector('[role="status"]')?.textContent).toContain('Plume lit le contexte');
    expect(el.querySelectorAll('.plume__step').length).toBe(7); // F-202 : passation, plan, fichiers touchés en plus
    expect(el.textContent).toContain('STATE.md du sujet');
  });

  it('enrichi : original, badge, sources, « à confirmer » surlignés, et « Revenir à mon texte »', () => {
    let restored = 0;
    fixture.componentInstance.restore.subscribe(() => restored++);
    const el = render({ phase: 'enriched', original: 'ma phrase', toConfirm: ['Taille 50', 'CSV inclus ?'], sources: ['Carte du poste'] });

    expect(el.querySelector('.plume__orig')?.textContent).toContain('Votre texte : « ma phrase »');
    expect(el.querySelector('.plume__badge')?.textContent).toContain('enrichi par Plume');
    expect(el.querySelector('.plume__src')?.textContent).toContain('Carte du poste');
    expect(el.querySelectorAll('.plume__chip').length).toBe(2);

    (el.querySelector('.plume__back') as HTMLButtonElement).click();
    expect(restored).toBe(1);
  });

  it('enrichi sans rien à confirmer ni source : aucune ligne vide', () => {
    const el = render({ phase: 'enriched', original: 'x', toConfirm: [], sources: [] });
    expect(el.querySelector('.plume__ask')).toBeNull();
    expect(el.querySelector('.plume__src')).toBeNull();
  });

  it('erreur : le message, en alerte, et « Fermer »', () => {
    let dismissed = 0;
    fixture.componentInstance.dismiss.subscribe(() => dismissed++);
    const el = render({ phase: 'error', message: 'Échec.' });
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Échec.');
    (el.querySelector('.plume__back') as HTMLButtonElement).click();
    expect(dismissed).toBe(1);
  });
});
