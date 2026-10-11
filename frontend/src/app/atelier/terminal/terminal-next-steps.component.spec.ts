import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AtelierNextStep } from '../../core/models/atelier.models';
import { TerminalNextStepsComponent, nextStepLabel, nextStepTone } from './terminal-next-steps.component';

/** F-192 / SF-192-02 — le panneau « Et maintenant ? ». */
describe('TerminalNextStepsComponent', () => {
  const steps: AtelierNextStep[] = [
    { title: "Vérifier l'export CSV", why: 'Non vérifié ce tour', label: 'VERIFIER', request: "Vérifie l'export CSV." },
    { title: 'Étape 4 du plan', why: 'Étapes 1 à 3 faites', label: 'CONTINUER', request: "Passe à l'étape 4." },
    { title: 'Ouvrir la PR', why: '214 tests verts', label: 'LIVRER', request: 'Ouvre la PR.' },
  ];

  let fixture: ComponentFixture<TerminalNextStepsComponent>;
  let component: TerminalNextStepsComponent;
  let picked: string[];

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TerminalNextStepsComponent] });
    fixture = TestBed.createComponent(TerminalNextStepsComponent);
    component = fixture.componentInstance;
    picked = [];
    component.pick.subscribe((request) => picked.push(request));
    fixture.componentRef.setInput('steps', steps);
    fixture.componentRef.setInput('turnKey', 'm1');
    fixture.detectChanges();
  });

  const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const key = (k: string): void => {
    document.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true }));
    fixture.detectChanges();
  };

  it('montre les options numérotées, leur motif et leur étiquette', () => {
    const options = root().querySelectorAll('.opt:not(.opt--other)');
    expect(options.length).toBe(3);
    expect(root().textContent).toContain('Et maintenant ?');
    expect(options[0].textContent).toContain("Vérifier l'export CSV");
    expect(options[0].textContent).toContain('Non vérifié ce tour');
    expect(options[2].querySelector('.tag')?.getAttribute('data-tone')).toBe('livrer');
    expect(root().textContent).toContain('Autre chose : tapez votre suite');
    expect(root().textContent).toContain("Rien n'est envoyé sans vous");
  });

  it('un clic émet la demande de l’option et la marque', () => {
    (root().querySelectorAll('.opt')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(picked).toEqual(["Passe à l'étape 4."]);
    expect(root().querySelectorAll('.opt')[1].classList).toContain('opt--sel');
  });

  it('les touches 1 à N choisissent, hors champ de saisie', () => {
    key('3');
    expect(picked).toEqual(['Ouvre la PR.']);
    key('4'); // au-delà du nombre d'options : ignorée
    expect(picked.length).toBe(1);
  });

  it('une touche tapée dans un champ lui appartient', () => {
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: '1', bubbles: true }));
    expect(picked).toEqual([]);
    input.remove();
  });

  it('Échap et « Masquer » replient sur une ligne ; « Afficher » rouvre', () => {
    key('Escape');
    expect(root().textContent).toContain('3 suites proposées');
    (root().querySelector('.cap__toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(root().querySelectorAll('.opt').length).toBeGreaterThan(0);
    (root().querySelector('.cap__toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(root().querySelector('.cap--folded')).not.toBeNull();
  });

  it('se replie dès que la saisie n’est pas vide', () => {
    fixture.componentRef.setInput('draft', 'je tape');
    fixture.detectChanges();
    expect(root().querySelector('.cap--folded')?.textContent).toContain('3 suites proposées');
    key('1');
    expect(picked).toEqual([]);
  });

  it('un nouveau tour rouvre le panneau', () => {
    component.hide();
    fixture.componentRef.setInput('turnKey', 'm2');
    fixture.detectChanges();
    expect(root().querySelector('.cap--folded')).toBeNull();
  });

  it('sans suite, rien n’est rendu', () => {
    fixture.componentRef.setInput('steps', []);
    fixture.detectChanges();
    expect(root().querySelector('.cap')).toBeNull();
  });

  it('« Autre chose » donne la main à la saisie', () => {
    let other = 0;
    component.other.subscribe(() => other++);
    (root().querySelector('.opt--other') as HTMLButtonElement).click();
    expect(other).toBe(1);
  });

  it('dit les étiquettes en français, inconnue ⇒ Continuer', () => {
    expect(nextStepLabel('RISQUE')).toBe('Risque');
    expect(nextStepLabel('AUTRE')).toBe('Continuer');
    expect(nextStepTone('AUTRE')).toBe('continuer');
    expect(nextStepTone('VERIFIER')).toBe('verifier');
  });
});
