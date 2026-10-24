import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTurnQuestions } from '../../core/models/atelier.models';
import { AtelierTerminalComponent } from './atelier-terminal.component';
import { MERGE_REQUEST } from './question-tracks';

/** F-210 / SF-210-03 — les pistes dans le terminal : pendant le tour, sur la réponse, et sans découpage. */
describe('AtelierTerminalComponent — deux questions, deux fils', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  const questions: AtelierTurnQuestions = {
    questions: [
      { id: 1, libelle: 'Le build', dependsOn: null, state: 'EN_COURS', toolUseIds: [] },
      { id: 2, libelle: 'Le README', dependsOn: 1, state: 'EN_ATTENTE', toolUseIds: [] },
    ],
    fusion: null,
  };

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

  it('montre une piste par question pendant le tour, avec les actions de chacune', () => {
    component.streaming = {
      tokens: null, status: 'running', text: '',
      blocks: [
        { tool: 'read', command: 'lecture a.ts', toolUseId: 'chat-0', threadId: null, output: '', hasOutput: false,
          error: false, expanded: false, question: 1 },
      ],
      questions,
    };
    component.submitting = true;
    fixture.detectChanges();

    const tracks = el().querySelectorAll('app-terminal-question-tracks .qt__track');
    expect(tracks.length).toBe(2);
    expect(tracks[0].textContent).toContain('lecture a.ts');
    expect(tracks[1].textContent).toContain('en attente de Q1');
  });

  it('garde le bandeau sur la dernière réponse, et « Fusionner » envoie une demande visible', () => {
    const drafts: string[] = [];
    let sent = 0;
    component.draftChange.subscribe((d: string) => drafts.push(d));
    component.send.subscribe(() => sent++);
    component.messages = [
      { id: 'u1', role: 'USER', content: 'deux choses', actions: [] },
      { id: 'a1', role: 'ASSISTANT', content: '**1.** Oui\n\n**2.** Non', actions: [],
        questions: { ...questions, questions: questions.questions.map((q) => ({ ...q, state: 'TERMINEE' as const })) } },
    ];
    fixture.detectChanges();

    expect(el().textContent).toContain('Ce message porte 2 questions');
    (el().querySelector('app-terminal-question-tracks .qt__action') as HTMLButtonElement).click();
    expect(drafts).toEqual([MERGE_REQUEST]);
    expect(sent).toBe(1);
  });

  it('sans découpage, aucune piste', () => {
    component.messages = [{ id: 'a1', role: 'ASSISTANT', content: 'Réponse.', actions: [] }];
    component.streaming = { tokens: null, status: 'running', text: 'x', blocks: [] };
    fixture.detectChanges();

    expect(el().querySelector('app-terminal-question-tracks')).toBeNull();
  });
});
