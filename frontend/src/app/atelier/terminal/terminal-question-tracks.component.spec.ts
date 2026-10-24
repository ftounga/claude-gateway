import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AtelierTurnQuestions } from '../../core/models/atelier.models';
import { MERGE_REQUEST, SPLIT_REQUEST } from './question-tracks';
import { TerminalQuestionTracksComponent } from './terminal-question-tracks.component';

/** F-210 / SF-210-03 — le bandeau de découpage et les pistes. */
describe('TerminalQuestionTracksComponent', () => {
  let fixture: ComponentFixture<TerminalQuestionTracksComponent>;
  let emitted: string[];

  const split: AtelierTurnQuestions = {
    questions: [
      { id: 1, libelle: 'Le build', dependsOn: null, state: 'EN_COURS', toolUseIds: [] },
      { id: 2, libelle: 'Le README', dependsOn: null, state: 'EN_ATTENTE', toolUseIds: [] },
    ],
    fusion: null,
  };

  function render(questions: AtelierTurnQuestions | null, options: { readOnly?: boolean; live?: boolean } = {}): HTMLElement {
    fixture.componentRef.setInput('questions', questions);
    fixture.componentRef.setInput('readOnly', options.readOnly ?? false);
    fixture.componentRef.setInput('live', options.live ?? true);
    fixture.componentRef.setInput('blocks', [
      { tool: 'read', command: 'lecture a.ts', toolUseId: 'chat-0', threadId: null, output: '', hasOutput: false,
        error: false, expanded: false, question: 2 },
    ]);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TerminalQuestionTracksComponent] });
    fixture = TestBed.createComponent(TerminalQuestionTracksComponent);
    emitted = [];
    fixture.componentInstance.request.subscribe((r) => emitted.push(r));
  });

  it('montre une piste par question, son état et ses actions', () => {
    const el = render(split);
    expect(el.textContent).toContain('Ce message porte 2 questions');
    const tracks = el.querySelectorAll('.qt__track');
    expect(tracks.length).toBe(2);
    expect(tracks[0].textContent).toContain('Le build');
    expect(tracks[0].textContent).toContain('En cours');
    expect(tracks[1].textContent).toContain('lecture a.ts');
  });

  it('« Fusionner » émet la demande de fusion', () => {
    const el = render(split);
    (el.querySelector('.qt__action') as HTMLButtonElement).click();
    expect(emitted).toEqual([MERGE_REQUEST]);
  });

  it('une fusion dit sa raison et propose « Séparer »', () => {
    const el = render({ ...split, fusion: { raison: 'la seconde remplace la première' } });
    expect(el.textContent).toContain('Fusionnées');
    expect(el.textContent).toContain('la seconde remplace la première');
    const button = el.querySelector('.qt__action') as HTMLButtonElement;
    expect(button.textContent).toContain('Séparer');
    button.click();
    expect(emitted).toEqual([SPLIT_REQUEST]);
  });

  it('en lecture seule, aucun bouton ; hors tour, pas d’actions détaillées ; sans questions, rien', () => {
    let el = render(split, { readOnly: true, live: false });
    expect(el.querySelector('.qt__action')).toBeNull();
    expect(el.querySelector('.qt__step')).toBeNull();
    el = render(null);
    expect(el.querySelector('.qt')).toBeNull();
  });
});
