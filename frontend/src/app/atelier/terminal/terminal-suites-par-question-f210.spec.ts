import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierNextStep, AtelierQuestionAnswer, AtelierTurnQuestions } from '../../core/models/atelier.models';
import { AtelierThreadItem } from '../atelier.types';
import { AtelierTerminalComponent } from './atelier-terminal.component';

/**
 * F-210 / SF-210-05 — chaque question garde ses suites. Parcours du retour PO : trois questions, un clic sur
 * une suite de la question 2 ⇒ les questions 1 et 3 gardent les leurs (au clic, pendant le tour qui démarre,
 * après rechargement du fil).
 */
describe('AtelierTerminalComponent — chaque question garde ses suites (SF-210-05)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  const step = (title: string): AtelierNextStep =>
    ({ title, why: 'motif', label: 'CONTINUER', request: `Fais ${title}` }) as AtelierNextStep;

  const answer = (id: number, titles: string[]): AtelierQuestionAnswer => ({
    id, libelle: `Question ${id}`, status: 'TERMINEE', essential: `Essentiel ${id}.`, body: '',
    nextSteps: titles.map(step), chantier: null,
  });

  function questions(): AtelierTurnQuestions {
    return {
      questions: [1, 2, 3].map((id) => ({ id, libelle: `Question ${id}`, dependsOn: null, state: 'TERMINEE', toolUseIds: [] })),
      fusion: null,
      answers: [answer(1, ['Q1 A', 'Q1 B']), answer(2, ['Q2 A', 'Q2 B']), answer(3, ['Q3 A'])],
    } as AtelierTurnQuestions;
  }

  function reply(): AtelierThreadItem {
    return { id: 'a1', role: 'ASSISTANT', content: 'trois réponses', actions: [], questions: questions() };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    component.projectName = 'mon-projet';
    // Le parent relaie la saisie : ce que le terminal émet lui revient en entrée.
    component.draftChange.subscribe((value: string) => { component.draft = value; });
  });

  function cards(): HTMLElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.terminal-answer-card'));
  }

  /** Le panneau d'une carte est-il déplié et montre-t-il ses suites ? */
  function open(card: HTMLElement): boolean {
    return !!card.querySelector('app-terminal-next-steps .cap:not(.cap--folded) .opt');
  }

  function hasSteps(card: HTMLElement): boolean {
    return !!card.querySelector('app-terminal-next-steps .opt, app-terminal-next-steps .cap--folded');
  }

  function clickOption(card: HTMLElement, title: string): void {
    const button = Array.from(card.querySelectorAll<HTMLButtonElement>('.opt'))
      .find((b) => b.textContent?.includes(title));
    expect(button).withContext(title).toBeTruthy();
    button!.click();
    fixture.detectChanges();
  }

  it('un clic sur une suite de Q2 ne replie ni Q1 ni Q3', () => {
    component.messages = [{ id: 'u1', role: 'USER', content: 'trois choses', actions: [] }, reply()];
    fixture.detectChanges();
    expect(cards().map(open)).toEqual([true, true, true]);

    clickOption(cards()[1], 'Q2 A');

    expect(component.draft).toBe('Fais Q2 A');
    expect(cards().map(open)).toEqual([true, true, true]);
  });

  it('cocher Q2 puis Q1 : les deux demandes partent ensemble dans la saisie', () => {
    component.messages = [reply()];
    fixture.detectChanges();

    clickOption(cards()[1], 'Q2 B');
    clickOption(cards()[0], 'Q1 A');

    expect(component.draft).toContain('Fais Q2 B');
    expect(component.draft).toContain('Fais Q1 A');
    expect(cards().map(open)).toEqual([true, true, true]);
  });

  it('une frappe libre replie tous les panneaux (F-192 inchangé)', () => {
    component.messages = [reply()];
    fixture.detectChanges();
    clickOption(cards()[1], 'Q2 A');

    component.draft = 'Fais Q2 A, et autre chose';
    fixture.detectChanges();

    expect(cards().map(open)).toEqual([false, false, false]);
  });

  it('le tour lancé par la suite de Q2 démarre : Q1 et Q3 gardent leurs suites, pas Q2', () => {
    component.messages = [reply(), { id: 'local-user-1', role: 'USER', content: 'Fais Q2 A', actions: [] }];
    component.submitting = true;
    fixture.detectChanges();

    expect(cards().map(hasSteps)).toEqual([true, false, true]);
    expect(cards().map(open)).toEqual([true, false, true]);
  });

  it('après rechargement du fil (nouveaux objets, tour fini ou non) : même résultat', () => {
    component.messages = [reply(), { id: 'u2', role: 'USER', content: 'Fais Q2 A', actions: [] }];
    fixture.detectChanges();
    // Le fil relu : d'autres objets, mêmes données.
    component.messages = [reply(), { id: 'u2', role: 'USER', content: 'Fais Q2 A', actions: [] }];
    fixture.detectChanges();

    expect(cards().map(hasSteps)).toEqual([true, false, true]);
  });

  it('« Lancer » une suite composée avec ses prérequis compte aussi comme réponse à la question', () => {
    component.messages = [reply(), { id: 'u2', role: 'USER',
      content: 'd’abord les prérequis, dans cet ordre — 1) étape 2 « X ». Puis : Fais Q3 A', actions: [] }];
    fixture.detectChanges();

    expect(cards().map(hasSteps)).toEqual([true, true, false]);
  });

  it('une nouvelle réponse de l’agent rend caduques les suites de l’ancienne', () => {
    component.messages = [reply(), { id: 'u2', role: 'USER', content: 'Fais Q2 A', actions: [] },
      { id: 'a2', role: 'ASSISTANT', content: 'Q2 faite.', actions: [] }];
    fixture.detectChanges();

    expect(cards().map(hasSteps)).toEqual([false, false, false]);
  });

  it('un message libre du PO ne consomme aucune question', () => {
    component.messages = [reply(), { id: 'u2', role: 'USER', content: 'merci, je regarde', actions: [] }];
    component.submitting = true;
    fixture.detectChanges();

    expect(cards().map(hasSteps)).toEqual([true, true, true]);
  });
});
