import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SimpleChange } from '@angular/core';

import { AtelierTerminalDemandeComponent } from './atelier-terminal-demande.component';
import { AtelierAnswerRequest } from '../../core/models/atelier.models';
import { AtelierPendingQuestion } from '../atelier.types';

/**
 * Rendu terminal des questions structurées (F-164 / SF-164-02) : la carte de l'outil `demander`.
 * On vérifie le rendu (radio vs case, option recommandée, champ libre systématique), la composition
 * de la réponse émise, l'activation du bouton, les états verrouillés et le signalement en lecture seule.
 */
describe('AtelierTerminalDemandeComponent (F-164 / SF-164-02)', () => {
  let fixture: ComponentFixture<AtelierTerminalDemandeComponent>;
  let component: AtelierTerminalDemandeComponent;

  function setPending(pending: AtelierPendingQuestion | null): void {
    const previous = component.pending;
    component.pending = pending;
    component.ngOnChanges({ pending: new SimpleChange(previous, pending, previous === undefined) });
    fixture.detectChanges();
  }

  function dom(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function pendingOf(overrides: Partial<AtelierPendingQuestion> = {}): AtelierPendingQuestion {
    return {
      callId: 'call-1',
      questions: [
        {
          header: 'Périmètre',
          question: 'Combien de subfeatures ?',
          multiSelect: false,
          options: [
            { label: 'Une', description: 'Tout en une', recommended: false },
            { label: 'Deux', description: 'Découper', recommended: true },
          ],
        },
      ],
      status: 'awaiting',
      answering: false,
      answeredHere: false,
      chosenSummary: '',
      deadline: null,
      timeoutMs: null,
      ...overrides,
    };
  }

  /** Une question SANS option recommandée : rien n'est pré-coché (SF-164-07). */
  function withoutRecommended(overrides: Partial<AtelierPendingQuestion> = {}): AtelierPendingQuestion {
    return pendingOf({
      questions: [
        {
          header: 'Périmètre',
          question: 'Combien de subfeatures ?',
          multiSelect: false,
          options: [
            { label: 'Une', description: 'Tout en une', recommended: false },
            { label: 'Deux', description: 'Découper', recommended: false },
          ],
        },
      ],
      ...overrides,
    });
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalDemandeComponent],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalDemandeComponent);
    component = fixture.componentInstance;
  });

  it('rend l’intitulé et le texte de chaque question (CA1)', () => {
    setPending(pendingOf());
    const text = dom().textContent ?? '';
    expect(text).toContain('Périmètre');
    expect(text).toContain('Combien de subfeatures ?');
    expect(dom().querySelectorAll('fieldset.terminal-demande-question').length).toBe(1);
  });

  it('choix simple → radios ; choix multiple → cases (CA2)', () => {
    setPending(pendingOf());
    expect(dom().querySelectorAll('input[type="radio"]').length).toBe(2);
    expect(dom().querySelectorAll('input[type="checkbox"]').length).toBe(0);

    setPending(pendingOf({
      callId: 'call-multi',
      questions: [
        {
          header: 'Cibles',
          question: 'Quels environnements ?',
          multiSelect: true,
          options: [
            { label: 'Dev', description: '', recommended: false },
            { label: 'Prod', description: '', recommended: false },
          ],
        },
      ],
    }));
    expect(dom().querySelectorAll('input[type="checkbox"]').length).toBe(2);
    expect(dom().querySelectorAll('input[type="radio"]').length).toBe(0);
  });

  it('repère l’option recommandée (CA3)', () => {
    setPending(pendingOf());
    const reco = dom().querySelector('.terminal-demande-reco');
    expect(reco).toBeTruthy();
    expect(reco?.textContent?.trim()).toBe('Recommandé');
  });

  it('rend toujours un champ libre « Autre / tape ta réponse » (CA4)', () => {
    setPending(pendingOf());
    expect(dom().querySelector('textarea.terminal-demande-other-input')).toBeTruthy();
    expect(dom().textContent).toContain('Autre / tape ta réponse');
  });

  it('Envoyer est désactivé tant qu’aucune réponse, actif dès un choix (CA5)', () => {
    setPending(withoutRecommended());
    const send = dom().querySelector('button.terminal-demande-send') as HTMLButtonElement;
    expect(send.disabled).toBeTrue();

    component.setSingle(0, 'Deux');
    fixture.detectChanges();
    expect(send.disabled).toBeFalse();
  });

  it('un texte libre seul suffit à activer Envoyer et à composer la réponse (CA4/CA5)', () => {
    setPending(withoutRecommended());
    let emitted: AtelierAnswerRequest | undefined;
    component.answer.subscribe((a) => (emitted = a));

    component.setFree(0, '  autre chose  ');
    fixture.detectChanges();
    expect(component.canSend).toBeTrue();

    component.send();
    expect(emitted).toEqual({
      callId: 'call-1',
      answers: [{ header: 'Périmètre', selected: [], other: 'autre chose' }],
    });
  });

  it('compose les choix multiples cochés (CA5)', () => {
    setPending(pendingOf({
      callId: 'call-multi',
      questions: [
        {
          header: 'Cibles',
          question: 'Quels environnements ?',
          multiSelect: true,
          options: [
            { label: 'Dev', description: '', recommended: false },
            { label: 'Prod', description: '', recommended: false },
          ],
        },
      ],
    }));
    let emitted: AtelierAnswerRequest | undefined;
    component.answer.subscribe((a) => (emitted = a));

    component.toggleMulti(0, 'Dev', true);
    component.toggleMulti(0, 'Prod', true);
    component.toggleMulti(0, 'Dev', false);
    fixture.detectChanges();

    component.send();
    expect(emitted?.answers[0].selected).toEqual(['Prod']);
  });

  it('exige une réponse pour CHAQUE question du lot avant d’envoyer (CA5)', () => {
    setPending(pendingOf({
      callId: 'lot',
      questions: [
        {
          header: 'Q1', question: 'A ?', multiSelect: false,
          options: [{ label: 'a1', description: '', recommended: false }],
        },
        {
          header: 'Q2', question: 'B ?', multiSelect: false,
          options: [{ label: 'b1', description: '', recommended: false }],
        },
      ],
    }));
    component.setSingle(0, 'a1');
    expect(component.canSend).toBeFalse();
    component.setSingle(1, 'b1');
    expect(component.canSend).toBeTrue();
  });

  it('réarme les choix quand une NOUVELLE question arrive (pauses répétées)', () => {
    setPending(withoutRecommended());
    component.setSingle(0, 'Deux');
    expect(component.single[0]).toBe('Deux');

    setPending(withoutRecommended({ callId: 'call-2' }));
    expect(component.single[0]).toBe('');
  });

  it('à l’état « répondu » (verrouillé) montre le choix fait, sans contrôles (CA6)', () => {
    setPending(pendingOf({
      status: 'answered',
      answeredHere: true,
      chosenSummary: 'Périmètre : Deux',
    }));
    expect(dom().querySelector('input')).toBeNull();
    expect(dom().querySelector('button.terminal-demande-send')).toBeNull();
    expect(dom().textContent).toContain('Répondu');
    expect(dom().textContent).toContain('Périmètre : Deux');
  });

  it('répondu ailleurs : le signale sans prétendre connaître le choix', () => {
    setPending(pendingOf({ status: 'answered', answeredHere: false, chosenSummary: '' }));
    expect(dom().textContent).toContain('Répondu sur un autre appareil');
  });

  it('délai écoulé : état terminal explicite', () => {
    setPending(pendingOf({ status: 'expired' }));
    expect(dom().textContent).toContain('Délai écoulé');
    expect(dom().textContent).not.toContain('décidé par défaut');
    expect(dom().querySelector('.terminal-demande-defaults')).toBeNull();
  });

  it('délai écoulé avec défauts : montre les choix décidés par défaut (SF-164-06)', () => {
    setPending(pendingOf({ status: 'expired', defaults: ['Périmètre : Deux', 'Nom : sans réponse'] }));
    expect(dom().textContent).toContain('Délai écoulé — décidé par défaut');
    const items = Array.from(dom().querySelectorAll('.terminal-demande-defaults li'))
      .map((li) => li.textContent?.trim());
    expect(items).toEqual(['Périmètre : Deux', 'Nom : sans réponse']);
    expect(dom().querySelector('input')).toBeNull();
  });

  it('en lecture seule : signale la question sans aucun contrôle (F-83)', () => {
    component.readOnly = true;
    setPending(pendingOf());
    expect(dom().querySelector('input')).toBeNull();
    expect(dom().querySelector('button')).toBeNull();
    expect(dom().textContent).toContain('Une question vous attend');
    expect(dom().textContent).toContain('Combien de subfeatures ?');
  });

  it('affiche le compte à rebours quand il est fourni (CA7)', () => {
    component.countdown = 'Il reste 1 min pour répondre';
    setPending(pendingOf({ timeoutMs: 60000 }));
    expect(dom().querySelector('.terminal-demande-countdown')?.textContent)
      .toContain('Il reste 1 min pour répondre');
  });

  it('n’émet rien tant que la réponse est incomplète', () => {
    setPending(withoutRecommended());
    const spy = jasmine.createSpy('answer');
    component.answer.subscribe(spy);
    component.send();
    expect(spy).not.toHaveBeenCalled();
  });

  it('accessibilité : fieldset/legend par question et champ libre étiqueté (CA10)', () => {
    setPending(pendingOf());
    expect(dom().querySelector('fieldset.terminal-demande-question legend')).toBeTruthy();
    const textarea = dom().querySelector('textarea.terminal-demande-other-input');
    expect(textarea?.getAttribute('aria-label')).toBe('Autre réponse à la question');
  });

  describe('toujours envoyable (SF-164-07)', () => {
    function lot(): AtelierPendingQuestion {
      return pendingOf({
        callId: 'lot-reco',
        questions: [
          {
            header: 'Q1', question: 'A ?', multiSelect: false,
            options: [
              { label: 'a1', description: '', recommended: false },
              { label: 'a2', description: '', recommended: true },
            ],
          },
          {
            header: 'Q2', question: 'B ?', multiSelect: true,
            options: [
              { label: 'b1', description: '', recommended: true },
              { label: 'b2', description: '', recommended: false },
              { label: 'b3', description: '', recommended: true },
            ],
          },
          {
            header: 'Q3', question: 'C ?', multiSelect: false,
            options: [{ label: 'c1', description: '', recommended: false }],
          },
        ],
      });
    }

    it('coche d’avance l’option recommandée (radio) et les recommandées (cases) — CA1', () => {
      setPending(lot());
      expect(component.single[0]).toBe('a2');
      expect(Array.from(component.multi[1])).toEqual(['b1', 'b3']);
      expect(component.single[2]).toBe('');
      const radio = dom().querySelector('input[type="radio"][value="a2"]') as HTMLInputElement;
      expect(radio.checked).toBeTrue();
    });

    it('Envoyer est actif d’emblée quand chaque question a une recommandée — CA1', () => {
      setPending(pendingOf());
      const send = dom().querySelector('button.terminal-demande-send') as HTMLButtonElement;
      expect(send.disabled).toBeFalse();
      let emitted: AtelierAnswerRequest | undefined;
      component.answer.subscribe((a) => (emitted = a));
      component.send();
      expect(emitted).toEqual({ callId: 'call-1', answers: [{ header: 'Périmètre', selected: ['Deux'] }] });
    });

    it('la même question rejouée garde les choix en cours — CA2', () => {
      setPending(lot());
      component.setSingle(0, 'a1');
      setPending({ ...lot() });
      expect(component.single[0]).toBe('a1');
    });

    it('dit combien de questions restent sans réponse, bouton grisé — CA3', () => {
      setPending(lot());
      const missing = dom().querySelector('.terminal-demande-missing');
      expect(missing?.textContent?.replace(/\s+/g, ' ')).toContain('1 question sur 3 sans réponse');
      expect((dom().querySelector('button.terminal-demande-send') as HTMLButtonElement).disabled).toBeTrue();

      component.setSingle(2, 'c1');
      fixture.detectChanges();
      expect(dom().querySelector('.terminal-demande-missing')).toBeNull();
      expect((dom().querySelector('button.terminal-demande-send') as HTMLButtonElement).disabled).toBeFalse();
    });

    it('« Aller à la première » fait défiler jusqu’à la question manquante et y met le focus — CA4', () => {
      setPending(lot());
      const fieldsets = dom().querySelectorAll<HTMLElement>('fieldset.terminal-demande-question');
      const scroll = spyOn(fieldsets[2], 'scrollIntoView');
      const input = fieldsets[2].querySelector('input') as HTMLInputElement;
      const focus = spyOn(input, 'focus');

      (dom().querySelector('button.terminal-demande-goto') as HTMLButtonElement).click();

      expect(scroll).toHaveBeenCalled();
      expect(focus).toHaveBeenCalled();
    });

    it('la barre d’action porte le compte à rebours et le bouton — CA5', () => {
      component.countdown = 'Il reste 9 min pour répondre';
      setPending(lot());
      const bar = dom().querySelector('.terminal-demande-bar');
      expect(bar?.querySelector('.terminal-demande-countdown')).toBeTruthy();
      expect(bar?.querySelector('button.terminal-demande-send')).toBeTruthy();
    });
  });
});
