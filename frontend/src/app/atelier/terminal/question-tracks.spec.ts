import { AtelierTerminalBlock, AtelierTurnQuestions } from '../../core/models/atelier.models';
import { normalizeTurnQuestions } from '../../core/services/turn-questions';
import { chatStepsToBlocks } from './chat-steps';
import { questionStateLabel, questionTracks, splitBannerText } from './question-tracks';

/** F-210 / SF-210-03 — les pistes par question. */
describe('question-tracks', () => {
  const questions: AtelierTurnQuestions = {
    questions: [
      { id: 1, libelle: 'Pourquoi le build échoue', dependsOn: null, state: 'EN_COURS', toolUseIds: ['t1'] },
      { id: 2, libelle: 'Mettre à jour le README', dependsOn: 1, state: 'EN_ATTENTE', toolUseIds: [] },
    ],
    fusion: null,
  };

  it('rattache les blocs vivants par question, et les blocs relus par identifiant', () => {
    const live = chatStepsToBlocks([
      { type: 'read', path: 'a.ts', question: 1 },
      { type: 'bash', path: 'npm test', question: 1 },
      { type: 'read', path: 'README.md', question: 2 },
    ]);
    const tracks = questionTracks(questions, live);
    expect(tracks[0].actionCount).toBe(2);
    expect(tracks[0].recent).toEqual(['lecture a.ts', 'npm test']);
    expect(tracks[1].recent).toEqual(['lecture README.md']);

    const stored: AtelierTerminalBlock[] = [
      { tool: 'read_file', command: 'read_file a.ts', toolUseId: 't1', threadId: null, output: '', hasOutput: false, error: false, expanded: false },
    ];
    expect(questionTracks(questions, stored)[0].recent).toEqual(['read_file a.ts']);
  });

  it('dit les états en français et la dépendance', () => {
    const tracks = questionTracks(questions);
    expect(tracks[0].stateLabel).toBe('En cours');
    expect(tracks[1].waitingOn).toBe('en attente de Q1');
    expect(tracks[0].waitingOn).toBeNull();
    expect(questionStateLabel('INCONNU')).toBe('En attente');
    expect(questionStateLabel('NON_ABOUTIE')).toBe('Non aboutie');
    expect(splitBannerText(questions)).toBe('Ce message porte 2 questions');
    expect(questionTracks(null)).toEqual([]);
  });

  it('normalise un instantané, et ignore un instantané illisible', () => {
    const parsed = normalizeTurnQuestions({
      questions: [{ id: 1, libelle: 'A', state: 'BIZARRE' }, { id: 'x' }],
      fusion: { raison: 'remplace' },
      answers: [{ id: 1, libelle: 'A', status: 'TERMINEE', essential: 'Oui', body: 'b', nextSteps: [] }],
    });
    expect(parsed?.questions.length).toBe(1);
    expect(parsed?.questions[0].state).toBe('EN_ATTENTE');
    expect(parsed?.questions[0].dependsOn).toBeNull();
    expect(parsed?.fusion?.raison).toBe('remplace');
    expect(parsed?.answers?.[0].essential).toBe('Oui');
    expect(normalizeTurnQuestions(null)).toBeNull();
    expect(normalizeTurnQuestions({ questions: [] })).toBeNull();
    expect(normalizeTurnQuestions({ questions: [{ id: 1, libelle: 'A' }] }, [{ id: 1, body: 'x' }])?.answers?.length)
      .toBe(1);
  });
});
