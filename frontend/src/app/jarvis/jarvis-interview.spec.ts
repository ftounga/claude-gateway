import {
  INTERVIEW_QUESTIONS, JarvisInterview, UPDATE_QUESTIONS, isContinue, isInterviewCommand, isResumeYes, isSkip, isStop,
  isWakeOnly, resumePrompt, updateQuestions,
} from './jarvis-interview';
import { Portrait } from './jarvis-portrait';

/** F-241 / SF-241-02 — le parcours de l'entretien, sur l'appareil. */
describe('jarvis-interview', () => {
  it('pose les dix questions de D14 ; « je passe » laisse la réponse vide', () => {
    expect(INTERVIEW_QUESTIONS.length).toBe(10);
    const interview = new JarvisInterview('INITIAL', INTERVIEW_QUESTIONS);
    expect(interview.current()).toBe('Comment souhaitez-vous que je vous appelle ?');
    expect(interview.hear('Franck')).toBe('next');
    expect(interview.hear('Je passe.')).toBe('next');
    expect(interview.answers()).toEqual([
      { question: INTERVIEW_QUESTIONS[0], answer: 'Franck' },
      { question: INTERVIEW_QUESTIONS[1], answer: '' },
    ]);
    for (let i = 2; i < 9; i++) {
      expect(interview.hear('réponse')).toBe('next');
    }
    expect(interview.hear('le divorce')).toBe('done');
    expect(interview.current()).toBeNull();
    expect(interview.answers().length).toBe(10);
  });

  it('SF-241-06 (E2) — une réponse longue en plusieurs segments est concaténée, jamais perdue', () => {
    const interview = new JarvisInterview('INITIAL', INTERVIEW_QUESTIONS);
    interview.hear('Franck');
    expect(interview.hear('Je suis chef de projet chez Orion, depuis six ans,', true)).toBe('more');
    expect(interview.current()).toBe(INTERVIEW_QUESTIONS[1]);
    expect(interview.hear('et avant cela développeur', true)).toBe('more');
    expect(interview.hear('pendant dix ans.')).toBe('next');
    expect(interview.answers()[1]).toEqual({
      question: INTERVIEW_QUESTIONS[1],
      answer: 'Je suis chef de projet chez Orion, depuis six ans, et avant cela développeur pendant dix ans.',
    });
    // « je passe » après un début de réponse clôt la réponse commencée, sans rien effacer.
    interview.hear('J’ai commencé dans la banque', true);
    expect(interview.hear('je passe')).toBe('next');
    expect(interview.answers()[2].answer).toBe('J’ai commencé dans la banque');
  });

  it('SF-241-06 (E2) — « je n’avais pas fini » rouvre la réponse précédente, puis la question en cours revient', () => {
    const interview = new JarvisInterview('INITIAL', INTERVIEW_QUESTIONS);
    interview.hear('Franck');
    interview.hear('Chef de projet.');
    expect(interview.hear('Attendez, je n’avais pas fini.')).toBe('reopened');
    expect(interview.current()).toBe(INTERVIEW_QUESTIONS[2]);
    expect(interview.hear('Et je forme aussi les nouveaux.')).toBe('next');
    expect(interview.answers()[1].answer).toBe('Chef de projet. Et je forme aussi les nouveaux.');
    expect(interview.answers().length).toBe(2);
    expect(interview.current()).toBe(INTERVIEW_QUESTIONS[2]);
    // Sans réponse précédente, « attendez » est une réponse ordinaire… jamais une réouverture.
    const fresh = new JarvisInterview('INITIAL', INTERVIEW_QUESTIONS);
    expect(fresh.hear('Attendez')).toBe('next');
  });

  it('SF-241-06 (E3) — « Bonjour Jarvis », « Jarvis », « tu es là ? » ne sont jamais une réponse', () => {
    const interview = new JarvisInterview('INITIAL', INTERVIEW_QUESTIONS);
    interview.hear('Franck');
    expect(interview.hear('Bonjour Jarvis.')).toBe('ignored');
    expect(interview.hear('Jarvis ?')).toBe('ignored');
    expect(interview.hear('Jarvis, tu es là ?')).toBe('ignored');
    expect(interview.hear('')).toBe('ignored');
    expect(interview.answers().length).toBe(1);
    expect(interview.current()).toBe(INTERVIEW_QUESTIONS[1]);
    expect(isWakeOnly('Bonjour Jarvis !')).toBeTrue();
    expect(isWakeOnly('Bonjour Jarvis, quel temps fait-il ?')).toBeFalse();
  });

  it('SF-241-06 (E4/E5) — suspendu, le segment en cours est gardé ; la reprise reconnaît « oui » / « on reprend »', () => {
    const interview = new JarvisInterview('INITIAL', INTERVIEW_QUESTIONS);
    interview.hear('Franck');
    interview.hear('Chef de projet', true);
    interview.suspend();
    expect(interview.suspended).toBeTrue();
    expect(interview.answers()[1]).toEqual({ question: INTERVIEW_QUESTIONS[1], answer: 'Chef de projet' });
    expect(interview.hasAnswers()).toBeTrue();
    interview.resume();
    expect(interview.suspended).toBeFalse();
    expect(resumePrompt(interview.position, interview.total))
      .toBe('Nous en étions à la question 2 sur 10 de l’entretien. On reprend l’entretien ?');
    expect(isResumeYes('Oui.')).toBeTrue();
    expect(isResumeYes('Jarvis, on reprend')).toBeTrue();
    expect(isResumeYes('Quel temps fait-il ?')).toBeFalse();
    expect(isContinue('Attends, j’ai pas fini')).toBeTrue();
  });

  it('« on arrête là » garde les réponses déjà données (et la réponse commencée)', () => {
    const started = new JarvisInterview('INITIAL', INTERVIEW_QUESTIONS);
    started.hear('Franck');
    started.hear('Chef de projet', true);
    expect(started.hear('on arrête là')).toBe('done');
    expect(started.answers().map((a) => a.answer)).toEqual(['Franck', 'Chef de projet']);

    const interview = new JarvisInterview('INITIAL', INTERVIEW_QUESTIONS);
    interview.hear('Franck');
    expect(interview.hear('On arrête là.')).toBe('done');
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
