import { BoardCommitment, BoardSubject, RadarCommitmentView } from '../../core/models/radar.models';
import {
  commitmentChip,
  commitmentPeople,
  commitmentTitle,
  evidenceSourceIcon,
  momentLabel,
  orderSubjects,
  postponeDate,
  silentDays,
  sinceLabel,
  subjectChip,
} from './radar-columns';

/** Les trois colonnes, en fonctions pures (F-102 / SF-102-02). */
describe('radar-columns', () => {
  // Mardi 15 septembre 2026, 8 h.
  const today = new Date(2026, 8, 15, 8, 0);

  const commitment = (extra: Partial<RadarCommitmentView> = {}): RadarCommitmentView => ({
    id: 'c1', subjectId: 's1', subjectName: 'MFA', direction: 'ME_TO_OTHER', description: 'Envoyer la matrice',
    fromPerson: null, toPerson: null, otherPerson: null, dueDate: null, dueDeduced: false, status: 'OPEN',
    certainty: 'CERTAIN', sovereign: false, disowned: false, evidenceIds: [], followUpDueOn: null, followUpDue: false,
    ...extra,
  });

  const item = (c: Partial<RadarCommitmentView> = {}, extra: Partial<BoardCommitment> = {}): BoardCommitment => ({
    commitment: commitment(c), source: null, sourceAt: null, deepLink: null, question: false, due: false,
    overdueDays: 0, ...extra,
  });

  const subject = (extra: Partial<BoardSubject['subject']> = {}): BoardSubject => ({
    subject: { id: 's1', name: 'MFA', state: 'ADVANCING', nextStep: null, dueDate: null,
      lastActivityAt: new Date(2026, 8, 14, 14, 0).toISOString(), openCommitments: 0, awake: false, ...extra },
    line: null, sources: 0, people: [],
  });

  it("la pastille d'un engagement : relance due, retard, question, échéance", () => {
    expect(commitmentChip(item({ direction: 'OTHER_TO_ME', followUpDue: true }), today))
      .toEqual({ label: 'relance due', tone: 'error' });
    expect(commitmentChip(item({ dueDate: '2026-09-12' }, { overdueDays: 3, due: true }), today))
      .toEqual({ label: 'en retard de 3 j', tone: 'error' });
    expect(commitmentChip(item({ certainty: 'PROBABLE' }, { question: true }), today))
      .toEqual({ label: 'probable', tone: 'neutral' });
    expect(commitmentChip(item({ dueDate: '2026-09-15' }), today)?.label).toBe("pour aujourd'hui");
    expect(commitmentChip(item({ dueDate: '2026-09-16' }), today)?.label).toBe('pour demain');
    expect(commitmentChip(item({ dueDate: '2026-09-18' }), today)).toEqual({ label: 'pour vendredi', tone: 'warning' });
    expect(commitmentChip(item({ dueDate: '2026-10-03' }), today)?.tone).toBe('neutral');
    expect(commitmentChip(item({ dueDate: '2026-10-03' }), today)?.label).toContain('pour le 3');
    expect(commitmentChip(item(), today)).toBeNull();
  });

  it('une question se lit comme une question ; les personnes selon le sens', () => {
    expect(commitmentTitle(item({ description: 'Rédiger la note DSI' }, { question: true }))).toBe('Rédiger la note DSI ?');
    expect(commitmentTitle(item({ description: 'Qui fait la note ?' }, { question: true }))).toBe('Qui fait la note ?');
    expect(commitmentTitle(item({ description: 'Envoyer' }))).toBe('Envoyer');
    expect(commitmentPeople(item({ direction: 'OTHER_TO_ME', fromPerson: { id: 'p', displayName: 'Julie' } })))
      .toBe('Julie');
    expect(commitmentPeople(item({ direction: 'INTRODUCTION', toPerson: { id: 'a', displayName: 'Sophie' },
      otherPerson: { id: 'b', displayName: 'Karim' } }))).toBe('mise en relation : Sophie et Karim');
    expect(commitmentPeople(item({ toPerson: { id: 'p', displayName: 'Paul' } }))).toBe('pour Paul');
    expect(commitmentPeople(item())).toBeNull();
  });

  it('les dates de report', () => {
    expect(postponeDate('tomorrow', today)).toBe('2026-09-16');
    expect(postponeDate('next-monday', today)).toBe('2026-09-21');
    expect(postponeDate('next-monday', new Date(2026, 8, 14))).toBe('2026-09-21');
    expect(postponeDate('one-week', today)).toBe('2026-09-22');
    expect(postponeDate('two-weeks', today)).toBe('2026-09-29');
  });

  it('les moments et les durées', () => {
    expect(momentLabel(new Date(2026, 8, 15, 7, 5).toISOString(), today)).toBe("aujourd'hui 07:05");
    expect(momentLabel(new Date(2026, 8, 14, 14, 32).toISOString(), today)).toBe('hier 14:32');
    expect(momentLabel(new Date(2026, 8, 12, 9, 0).toISOString(), today)).toContain('12');
    expect(momentLabel(null, today)).toBeNull();
    expect(sinceLabel(new Date(2026, 8, 14, 14, 0).toISOString(), today)).toBe('il y a 18 h');
    expect(sinceLabel(new Date(2026, 8, 6, 8, 0).toISOString(), today)).toBe('il y a 9 j');
    expect(evidenceSourceIcon('TEAMS_MEETING')).toBe('videocam');
    expect(evidenceSourceIcon('PASTED_MAIL')).toBe('mail');
    expect(evidenceSourceIcon(null)).toBe('forum');
  });

  it("l'état d'un sujet : réveil, silence, états §17", () => {
    expect(subjectChip(subject(), today)).toEqual({ label: 'avance', tone: 'success' });
    expect(subjectChip(subject({ state: 'NEW' }), today)).toEqual({ label: 'nouveau', tone: 'blue' });
    expect(subjectChip(subject({ state: 'CLOSE_PROPOSED' }), today)).toEqual({ label: 'clos ?', tone: 'blue' });
    expect(subjectChip(subject({ state: 'CLOSED', awake: true }), today)).toEqual({ label: 'se réveille', tone: 'blue' });
    const quiet = subject({ state: 'WAITING', lastActivityAt: new Date(2026, 8, 6, 8, 0).toISOString() });
    expect(silentDays(quiet, today)).toBe(9);
    expect(subjectChip(quiet, today)).toEqual({ label: 'silencieux 9 j', tone: 'warning' });
    // Un sujet bloqué reste dit bloqué, même muet.
    expect(subjectChip(subject({ state: 'BLOCKED', lastActivityAt: new Date(2026, 8, 1).toISOString() }), today))
      .toEqual({ label: 'bloqué', tone: 'error' });
    expect(silentDays(subject({ state: 'DORMANT', lastActivityAt: new Date(2026, 7, 1).toISOString() }), today)).toBeNull();
  });

  it("« À traiter d'abord » : réveillés, clos ?, bloqués, en sommeil, en attente, nouveaux, qui avancent", () => {
    const list = [
      subject({ id: 'a', state: 'ADVANCING' }), subject({ id: 'n', state: 'NEW' }),
      subject({ id: 'b', state: 'BLOCKED' }), subject({ id: 'w', state: 'CLOSED', awake: true }),
      subject({ id: 'p', state: 'CLOSE_PROPOSED' }), subject({ id: 'd', state: 'DORMANT' }),
      subject({ id: 'q', state: 'WAITING' }),
    ];
    expect(orderSubjects(list, 'recent').map((s) => s.subject.id)).toEqual(['a', 'n', 'b', 'w', 'p', 'd', 'q']);
    expect(orderSubjects(list, 'attention').map((s) => s.subject.id)).toEqual(['w', 'p', 'b', 'd', 'q', 'n', 'a']);
  });
});
