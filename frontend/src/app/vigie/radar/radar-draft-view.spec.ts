import { HttpErrorResponse } from '@angular/common/http';

import { BoardCommitment, RadarCommitmentView } from '../../core/models/radar.models';
import { draftButtonLabel, draftErrorOf, draftKindOf } from './radar-draft-view';

/** Relances et présentations — fonctions pures (F-104 / SF-104-05). */
describe('radar-draft-view', () => {
  const item = (c: Partial<RadarCommitmentView>, question = false): BoardCommitment => ({
    commitment: {
      id: 'c1', subjectId: 's1', subjectName: 'SSO', direction: 'OTHER_TO_ME', description: 'Retour',
      fromPerson: null, toPerson: null, otherPerson: null, dueDate: null, dueDeduced: false, status: 'OPEN',
      certainty: 'CERTAIN', sovereign: false, disowned: false, evidenceIds: [], followUpDueOn: null,
      followUpDue: false, ...c,
    } as RadarCommitmentView,
    source: null, sourceAt: null, deepLink: null, question, due: false, overdueDays: 0,
  });

  it('relance, présentation, ou rien', () => {
    expect(draftKindOf(item({}))).toBe('FOLLOW_UP');
    expect(draftKindOf(item({ status: 'POSTPONED' }))).toBe('FOLLOW_UP');
    expect(draftKindOf(item({ direction: 'INTRODUCTION' }))).toBe('INTRODUCTION');
    expect(draftKindOf(item({ direction: 'ME_TO_OTHER' }))).toBeNull();
    expect(draftKindOf(item({}, true))).toBeNull();
    expect(draftKindOf(item({ status: 'KEPT' }))).toBeNull();
    expect(draftKindOf(item({ disowned: true }))).toBeNull();
    expect(draftButtonLabel('FOLLOW_UP')).toBe('Préparer la relance');
    expect(draftButtonLabel('INTRODUCTION')).toBe('Préparer la présentation');
  });

  it('dit les échecs', () => {
    expect(draftErrorOf(new HttpErrorResponse({ status: 402 }))).toContain('quota');
    expect(draftErrorOf(new HttpErrorResponse({ status: 503 }))).toContain('indisponible');
    expect(draftErrorOf(new HttpErrorResponse({ status: 409, error: { message: 'Il n\'y a rien à relancer.' } })))
      .toBe('Il n\'y a rien à relancer.');
  });
});
