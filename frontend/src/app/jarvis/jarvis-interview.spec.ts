import {
  INTERVIEW_QUESTIONS, JarvisInterview, UPDATE_QUESTIONS, isInterviewCommand, isSkip, isStop, updateQuestions,
} from './jarvis-interview';
import { Portrait } from './jarvis-portrait';

/** F-241 / SF-241-02 — le parcours de l'entretien, sur l'appareil. */
describe('jarvis-interview', () => {
  it('pose les dix questions de D14 ; « je passe » laisse la réponse vide', () => {
    expect(INTERVIEW_QUESTIONS.length).toBe(10);
    const interview = new JarvisInterview('INITIAL', INTERVIEW_QUESTIONS);
    expect(interview.current()).toBe('Comment souhaitez-vous que je vous appelle ?');
    expect(interview.hear('Franck')).toBeFalse();
    expect(interview.hear('Je passe.')).toBeFalse();
    expect(interview.answers()).toEqual([
      { question: INTERVIEW_QUESTIONS[0], answer: 'Franck' },
      { question: INTERVIEW_QUESTIONS[1], answer: '' },
    ]);
    for (let i = 2; i < 9; i++) {
      expect(interview.hear('réponse')).toBeFalse();
    }
    expect(interview.hear('le divorce')).toBeTrue();
    expect(interview.current()).toBeNull();
    expect(interview.answers().length).toBe(10);
  });

  it('« on arrête là » garde les réponses déjà données', () => {
    const interview = new JarvisInterview('INITIAL', INTERVIEW_QUESTIONS);
    interview.hear('Franck');
    expect(interview.hear('On arrête là.')).toBeTrue();
    expect(interview.answers().length).toBe(1);
    expect(interview.hasAnswers()).toBeTrue();
    const empty = new JarvisInterview('INITIAL', INTERVIEW_QUESTIONS);
    empty.hear('je passe');
    empty.hear('stop');
    expect(empty.hasAnswers()).toBeFalse();
  });

  it('reconnaît les listes fermées (passer, arrêter, reprise à la demande)', () => {
    expect(isSkip('Jarvis, je passe')).toBeTrue();
    expect(isSkip('Je passe chez ma mère')).toBeFalse();
    expect(isStop('On arrête là')).toBeTrue();
    expect(isInterviewCommand('Jarvis, mettons à jour mon portrait')).toBeTrue();
    expect(isInterviewCommand('Mets à jour mon portrait.')).toBeTrue();
    expect(isInterviewCommand('Refaisons l’entretien')).toBeTrue();
    expect(isInterviewCommand('mets à jour le ticket')).toBeFalse();
  });

  it('la mise à jour commence par les éléments « à revoir »', () => {
    const portrait = {
      sections: [
        { key: 'QUI', title: 'Qui vous êtes', sensitive: false, text: 'Chef de projet chez Orion', updatedAt: '2024-01-01', stale: true },
        { key: 'GOUTS', title: 'Vos goûts', sensitive: false, text: 'Jazz', updatedAt: '2026-10-01', stale: false },
      ],
      souvenirs: [{ id: 's', number: 1, text: 'Prépare un marathon', sensitive: false, notedOn: '2024-05-01', source: 'VOIX', stale: true }],
    } as unknown as Portrait;
    const questions = updateQuestions(portrait);
    expect(questions[0]).toContain('Qui vous êtes');
    expect(questions[0]).toContain('Chef de projet chez Orion');
    expect(questions[1]).toContain('Prépare un marathon');
    expect(questions.slice(2)).toEqual([...UPDATE_QUESTIONS]);
    expect(updateQuestions(null)).toEqual([...UPDATE_QUESTIONS]);
  });
});
