import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierNextStep, AtelierQuestionAnswer, AtelierTurnQuestions } from '../../core/models/atelier.models';
import { AtelierThreadItem } from '../atelier.types';
import { AtelierTerminalComponent } from './atelier-terminal.component';

/** F-210 / SF-210-04 — une carte de réponse par question. */
describe('AtelierTerminalComponent — une carte par question', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  const step = (title: string, extra: Partial<AtelierNextStep> = {}): AtelierNextStep =>
    ({ title, why: 'motif', label: 'VERIFIER', request: `Fais ${title}`, ...extra }) as AtelierNextStep;

  const answers: AtelierQuestionAnswer[] = [
    { id: 1, libelle: 'Le build', status: 'TERMINEE', essential: 'Le build casse sur un test.', body: 'Détail build.',
      nextSteps: [step('Relancer le build', { scope: 'CHANTIER', stepIndex: 3, column: 'CHANTIER' } as Partial<AtelierNextStep>)],
      chantier: { number: 4, done: 2, total: 14 } },
    { id: 2, libelle: 'Le README', status: 'NON_ABOUTIE', essential: null, body: '', nextSteps: [], chantier: null },
  ];
  const questions: AtelierTurnQuestions = {
    questions: [
      { id: 1, libelle: 'Le build', dependsOn: null, state: 'TERMINEE', toolUseIds: [] },
      { id: 2, libelle: 'Le README', dependsOn: null, state: 'NON_ABOUTIE', toolUseIds: [] },
    ],
    fusion: null,
    answers,
  };

  function assistant(extra: Partial<AtelierThreadItem>): AtelierThreadItem {
    return { id: 'a1', role: 'ASSISTANT', content: '**1. Le build**\n\nTexte lisible', actions: [], ...extra };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    component.projectName = 'mon-projet';
  });

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('une carte par réponse : essentiel, détail, badge, non aboutie, et ses suites', () => {
    component.messages = [{ id: 'u1', role: 'USER', content: 'deux choses', actions: [] },
      assistant({ questions, nextSteps: [step('Globale')] })];
    fixture.detectChanges();

    const cards = el().querySelectorAll('.terminal-answer-card');
    expect(cards.length).toBe(2);
    expect(cards[0].textContent).toContain('Le build casse sur un test.');
    expect(cards[0].textContent).toContain('Détail build.');
    expect(cards[0].querySelector('.terminal-answer-badge')?.textContent).toContain('Ch.4 · étape 3/14');
    expect(cards[0].querySelector('app-terminal-next-steps')?.textContent).toContain('Relancer le build');
    expect(cards[1].querySelector('.terminal-answer-badge')?.textContent).toContain('Hors chantier');
    expect(cards[1].textContent).toContain('Non aboutie');
    expect(cards[1].textContent).toContain("Cette question n'a pas abouti dans ce tour.");
    expect(cards[1].querySelector('app-terminal-next-steps')).toBeNull();
    // Le texte lisible n'est pas rendu en double, et le panneau global « Et maintenant ? » s'efface.
    expect(el().textContent).not.toContain('Texte lisible');
    expect(el().textContent).not.toContain('Globale');
  });

  it('une réponse ancienne garde ses cartes mais pas ses suites', () => {
    component.messages = [assistant({ questions }), { id: 'u2', role: 'USER', content: 'suite', actions: [] },
      { id: 'a2', role: 'ASSISTANT', content: 'Autre réponse.', actions: [] }];
    fixture.detectChanges();

    expect(el().querySelectorAll('.terminal-answer-card').length).toBe(2);
    expect(el().querySelector('.terminal-answer-card app-terminal-next-steps')).toBeNull();
  });

  it('fusion : une seule réponse, avec la mention de fusion', () => {
    component.messages = [assistant({ content: 'Réponse fusionnée.',
      questions: { questions: questions.questions, fusion: { raison: 'la seconde remplace la première' } } })];
    fixture.detectChanges();

    expect(el().querySelector('.terminal-answer-card')).toBeNull();
    expect(el().textContent).toContain('Fusionnées');
    expect(el().textContent).toContain('la seconde remplace la première');
    expect(el().textContent).toContain('Réponse fusionnée.');
  });

  it('sans découpage, rendu habituel et suites globales', () => {
    component.messages = [assistant({ content: 'Réponse simple.', nextSteps: [step('Globale')] })];
    fixture.detectChanges();

    expect(el().querySelector('.terminal-answer-card')).toBeNull();
    expect(el().textContent).toContain('Réponse simple.');
    expect(el().textContent).toContain('Globale');
  });
});

describe('toThreadItem — relecture des réponses par question (F-210 / SF-210-04)', () => {
  it('relit terminal_json.questions et terminal_json.answers', async () => {
    const { toThreadItem } = await import('../atelier.component');
    const item = toThreadItem({
      id: 'm1', role: 'ASSISTANT', content: 'x',
      terminal: {
        questions: { questions: [{ id: 1, libelle: 'A', dependsOn: null, state: 'TERMINEE', toolUseIds: [] },
          { id: 2, libelle: 'B', dependsOn: null, state: 'TERMINEE', toolUseIds: [] }], fusion: null },
        answers: [{ id: 1, libelle: 'A', status: 'TERMINEE', essential: 'Oui', body: '', nextSteps: [], chantier: null },
          { id: 2, libelle: 'B', status: 'TERMINEE', essential: 'Non', body: '', nextSteps: [], chantier: null }],
      },
    } as unknown as Parameters<typeof toThreadItem>[0]);

    expect(item.questions?.answers?.map((a) => a.essential)).toEqual(['Oui', 'Non']);
  });
});
