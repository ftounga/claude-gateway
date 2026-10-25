import { ComponentFixture, TestBed } from '@angular/core/testing';

import { PLUME_VIDEO_STEP, PlumeView, TerminalPlumeComponent, looksLikeVideoRequest } from './terminal-plume.component';

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

  it('F-215 / SF-215-05 : une demande de vidéo — étape « Pages et vidéos du sujet », badge « cahier de réalisation »', () => {
    let el = render({ phase: 'running', video: true });
    const steps = Array.from(el.querySelectorAll('.plume__step')).map((s) => s.textContent?.trim());
    expect(steps.length).toBe(8);
    expect(steps[6]).toBe(PLUME_VIDEO_STEP);
    expect(steps[7]).toBe('Rédaction de la demande');

    el = render({ phase: 'enriched', original: 'une vidéo sur EKS', toConfirm: ['Public'], sources: ['Pages publiées'], video: true });
    expect(el.querySelector('.plume__badge')?.textContent).toContain('cahier de réalisation');
    expect(el.querySelectorAll('.plume__chip').length).toBe(1);
  });

  it('F-215 / SF-215-05 : indice d’affichage d’une demande de vidéo (accents, casse)', () => {
    expect(looksLikeVideoRequest('Fais-moi une VIDÉO sur le réseau')).toBeTrue();
    expect(looksLikeVideoRequest('un cours animé sur Azure')).toBeTrue();
    expect(looksLikeVideoRequest('ajoute la pagination')).toBeFalse();
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
