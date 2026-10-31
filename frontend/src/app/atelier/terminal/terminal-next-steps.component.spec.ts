import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AtelierNextStep, AtelierNextStepsChantier } from '../../core/models/atelier.models';
import {
  TerminalNextStepsComponent, composeNextSteps, nextStepLabel, nextStepScopeLabel, nextStepTone, remainingStepsText,
} from './terminal-next-steps.component';

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

  it('un clic coche l’option et émet sa demande telle quelle', () => {
    (root().querySelectorAll('.opt')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(picked).toEqual(["Passe à l'étape 4."]);
    expect(root().querySelectorAll('.opt')[1].classList).toContain('opt--sel');
    expect(root().querySelectorAll('.opt')[1].getAttribute('aria-checked')).toBe('true');
  });

  it('plusieurs coches enchaînent les demandes dans l’ordre d’affichage, panneau ouvert', () => {
    key('3');
    fixture.componentRef.setInput('draft', picked[picked.length - 1]);
    fixture.detectChanges();
    key('1');
    const text = picked[picked.length - 1];
    expect(text).toBe("Traite ces suites — en parallèle celles qui sont indépendantes : 1) Vérifie l'export CSV. ; 2) Ouvre la PR.");
    fixture.componentRef.setInput('draft', text);
    fixture.detectChanges();
    expect(root().querySelector('.cap--folded')).toBeNull();
    expect(root().querySelector('.cap__checked')?.textContent).toContain('2 cochées');
    expect(root().querySelectorAll('[aria-checked="true"]').length).toBe(2);
  });

  it('décocher la dernière vide la saisie', () => {
    key('2');
    key('2');
    expect(picked).toEqual(["Passe à l'étape 4.", '']);
    expect(component.checked()).toEqual([]);
  });

  it('les touches 1 à N cochent, hors champ de saisie', () => {
    key('3');
    expect(picked).toEqual(['Ouvre la PR.']);
    key('4'); // au-delà du nombre d'options : ignorée
    expect(picked.length).toBe(1);
  });

  it('Entrée après une coche donne la main à la saisie', () => {
    let other = 0;
    component.other.subscribe(() => other++);
    key('Enter');
    expect(other).toBe(0);
    key('1');
    key('Enter');
    expect(other).toBe(1);
    expect(component.checked()).toEqual([0]);
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

  it('un texte tapé par l’utilisateur replie le panneau, même après une coche', () => {
    key('1');
    fixture.componentRef.setInput('draft', "Vérifie l'export CSV. et le PDF");
    fixture.detectChanges();
    expect(root().querySelector('.cap--folded')).not.toBeNull();
  });

  it('un nouveau tour rouvre le panneau et remet les coches à zéro', () => {
    key('1');
    component.hide();
    fixture.componentRef.setInput('turnKey', 'm2');
    fixture.detectChanges();
    expect(root().querySelector('.cap--folded')).toBeNull();
    expect(component.checked()).toEqual([]);
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

  it('compose : rien ⇒ vide, une ⇒ telle quelle', () => {
    expect(composeNextSteps([])).toBe('');
    expect(composeNextSteps(['Ouvre la PR.'])).toBe('Ouvre la PR.');
  });
});

/** F-196 / SF-196-02 — le panneau au service du chantier. */
describe('TerminalNextStepsComponent — chantier guidé', () => {
  const anchored: AtelierNextStep[] = [
    {
      title: 'Policy NAP', why: 'Étape 2 à faire', label: 'CONTINUER', request: 'Applique la policy NAP.',
      scope: 'CHANTIER', stepIndex: 2, stepTitle: 'Appliquer la policy NAP V12',
    },
    { title: 'Rétention KMS', why: 'Vu dans les logs', label: 'RISQUE', request: 'Regarde KMS.', scope: 'APPROFONDISSEMENT' },
  ];

  let fixture: ComponentFixture<TerminalNextStepsComponent>;
  let picked: string[];

  const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const options = (): NodeListOf<HTMLButtonElement> => root().querySelectorAll('.opt:not(.opt--other)');

  function render(chantier: AtelierNextStepsChantier | null, steps: AtelierNextStep[] = anchored): void {
    TestBed.configureTestingModule({ imports: [TerminalNextStepsComponent] });
    fixture = TestBed.createComponent(TerminalNextStepsComponent);
    picked = [];
    fixture.componentInstance.pick.subscribe((request) => picked.push(request));
    fixture.componentRef.setInput('steps', steps);
    fixture.componentRef.setInput('chantier', chantier);
    fixture.componentRef.setInput('turnKey', 'm1');
    fixture.detectChanges();
  }

  it('montre l’avancement et l’étape de chaque suite', () => {
    render({ number: 1, done: 3, total: 10 });
    expect(root().querySelector('.cap__chantier')?.textContent).toContain('Chantier : 3/10 étapes');
    expect(options()[0].querySelector('.scope')?.textContent).toContain('Étape 2 · Appliquer la policy NAP V12');
    const deep = options()[1].querySelector('.scope');
    expect(deep?.textContent).toContain('Hors plan · approfondissement');
    expect(deep?.getAttribute('data-scope')).toBe('APPROFONDISSEMENT');
  });

  it('cocher un approfondissement prévient sans rien empêcher', () => {
    render({ number: 1, done: 3, total: 10 });
    expect(root().querySelector('.cap__warn')).toBeNull();
    options()[1].click();
    fixture.detectChanges();
    expect(picked).toEqual(['Regarde KMS.']);
    const warn = root().querySelector('.cap__warn');
    expect(warn?.textContent).toContain("Le chantier n'est pas terminé : il reste 7 étapes");
    expect(warn?.getAttribute('role')).toBe('status');
  });

  it('cocher une étape du plan ne prévient pas', () => {
    render({ number: 1, done: 3, total: 10 });
    options()[0].click();
    fixture.detectChanges();
    expect(root().querySelector('.cap__warn')).toBeNull();
  });

  it('sans avancement (Libre, clos) : aucune étiquette, aucun en-tête, aucun avertissement', () => {
    render(null);
    expect(root().querySelector('.cap__chantier')).toBeNull();
    expect(root().querySelector('.scope')).toBeNull();
    options()[1].click();
    fixture.detectChanges();
    expect(root().querySelector('.cap__warn')).toBeNull();
  });

  it('dit l’étape sans titre et accorde le singulier', () => {
    expect(nextStepScopeLabel({ ...anchored[0], stepTitle: undefined }, true)).toBe('Étape 2');
    expect(nextStepScopeLabel(anchored[0], false)).toBeNull();
    expect(remainingStepsText(1)).toBe("Le chantier n'est pas terminé : il reste 1 étape");
  });
});

/** F-206 / SF-206-04 — une suite d'un autre chantier ouvert : son libellé, et « Basculer ». */
describe('TerminalNextStepsComponent — plusieurs chantiers ouverts', () => {
  const steps: AtelierNextStep[] = [
    {
      title: 'Étape 4', why: 'à faire', label: 'CONTINUER', request: 'Fais 4.',
      scope: 'CHANTIER', stepIndex: 4, stepTitle: 'Déléguer la zone', chantier: 2,
    },
    {
      title: 'Ingress', why: 'cert expiré', label: 'VERIFIER', request: 'Vérifie l’ingress.',
      scope: 'CHANTIER', stepIndex: 2, stepTitle: 'Vérifier l’ingress', chantier: 1,
    },
    { title: 'Certificat', why: 'lié', label: 'CONTINUER', request: 'Cert.', scope: 'CHANTIER', chantier: 1 },
  ];
  const progress: AtelierNextStepsChantier = { number: 2, done: 3, total: 10, others: [{ number: 1, done: 1, total: 2 }] };

  let fixture: ComponentFixture<TerminalNextStepsComponent>;
  const root = (): HTMLElement => fixture.nativeElement as HTMLElement;

  function render(focus: number | null): number[] {
    TestBed.configureTestingModule({ imports: [TerminalNextStepsComponent] });
    fixture = TestBed.createComponent(TerminalNextStepsComponent);
    const switched: number[] = [];
    fixture.componentInstance.switchChantier.subscribe((n) => switched.push(n));
    fixture.componentRef.setInput('steps', steps);
    fixture.componentRef.setInput('chantier', progress);
    fixture.componentRef.setInput('focus', focus);
    fixture.componentRef.setInput('turnKey', 'm1');
    fixture.detectChanges();
    return switched;
  }

  it('dit « Chantier N · Étape M » et propose de basculer, pas pour le focus', () => {
    const switched = render(null);
    const options = root().querySelectorAll('.opt:not(.opt--other)');
    expect(options.length).toBe(3);
    expect(options[0].querySelector('.scope')?.textContent?.trim()).toBe('Étape 4 · Déléguer la zone');
    expect(options[1].querySelector('.scope')?.textContent?.trim()).toBe('Chantier 1 · Étape 2 · Vérifier l’ingress');
    expect(options[2].querySelector('.scope')?.textContent?.trim()).toBe('Chantier 1');
    const buttons = root().querySelectorAll('.switch') as NodeListOf<HTMLButtonElement>;
    expect(buttons.length).toBe(2);
    expect(buttons[0].textContent).toContain('Afficher le chantier 1'); // F-226 : « Afficher »
    buttons[0].click();
    expect(switched).toEqual([1]);
    expect(root().querySelector('.cap__chantier')?.textContent).toContain('Chantier 2 : 3/10 · Chantier 1 : 1/2');
  });

  it('après une bascule, le focus en direct inverse les libellés', () => {
    render(1);
    const options = root().querySelectorAll('.opt:not(.opt--other)');
    expect(options[0].querySelector('.scope')?.textContent?.trim()).toBe('Chantier 2 · Étape 4 · Déléguer la zone');
    expect(options[1].querySelector('.scope')?.textContent?.trim()).toBe('Étape 2 · Vérifier l’ingress');
    expect(root().querySelectorAll('.switch').length).toBe(1);
    expect(root().querySelector('.cap__chantier')?.textContent).toContain('Chantier 1 : 1/2 · Chantier 2 : 3/10');
  });
});
