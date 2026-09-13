import { VigiePerson } from '../../core/models/vigie.models';
import {
  directoryKey,
  filterPeople,
  interactionLabel,
  personSubjects,
  sortPeople,
  subjectsLabel,
} from './radar-directory';

/** L'annuaire du Radar en fonctions pures (F-103 / SF-103-04). */
describe('radar-directory', () => {
  const person = (displayName: string, extra: Partial<VigiePerson> = {}): VigiePerson => ({
    id: displayName, displayName, jobTitle: null, lastInteractionAt: null, subjects: [], ...extra,
  });

  it("range par interaction la plus récente, les inconnues à la fin, puis par nom", () => {
    const sorted = sortPeople([
      person('Zoé'),
      person('Paul', { lastInteractionAt: '2026-09-01T10:00:00Z' }),
      person('élodie'),
      person('Sophie', { lastInteractionAt: '2026-09-12T10:00:00Z' }),
      person('Karim', { lastInteractionAt: 'pas une date' }),
    ]);

    expect(sorted.map((p) => p.displayName)).toEqual(['Sophie', 'Paul', 'élodie', 'Karim', 'Zoé']);
    expect(sortPeople(null)).toEqual([]);
  });

  it('filtre par nom, fonction ou sujet, sans casse ni accents', () => {
    const people = [
      person('Élodie Dupré', { jobTitle: 'RSSI' }),
      person('Paul Martin', { subjects: [{ subjectId: 's1', subjectName: 'Sécurité MFA', state: 'ADVANCING', role: 'DECIDES' }] }),
      person('Karim'),
    ];

    expect(filterPeople(people, 'elodie').map((p) => p.displayName)).toEqual(['Élodie Dupré']);
    expect(filterPeople(people, 'rssi').map((p) => p.displayName)).toEqual(['Élodie Dupré']);
    expect(filterPeople(people, '  SECURITE ').map((p) => p.displayName)).toEqual(['Paul Martin']);
    expect(filterPeople(people, 'personne')).toEqual([]);
    expect(filterPeople(people, '').length).toBe(3);
    expect(directoryKey('  Élodie   DUPRÉ ')).toBe('elodie dupre');
  });

  it('les sujets en cours avant les sujets clos, puis par nom', () => {
    const p = person('Paul', { subjects: [
      { subjectId: 's3', subjectName: 'LDAP', state: 'CLOSED', role: 'EXPERT' },
      { subjectId: 's2', subjectName: 'VPN', state: 'BLOCKED', role: 'DRIVES' },
      { subjectId: 's1', subjectName: 'MFA', state: 'ADVANCING', role: 'DECIDES' },
    ] });

    expect(personSubjects(p).map((s) => s.subjectName)).toEqual(['MFA', 'VPN', 'LDAP']);
  });

  it('écrit le nombre de sujets et le dernier échange', () => {
    const now = new Date(2026, 8, 13);
    expect(subjectsLabel(person('A'))).toBe('0 sujet');
    expect(subjectsLabel(person('A', { subjects: [{ subjectId: 's', subjectName: 'x', state: 'NEW', role: 'EXPERT' }] })))
      .toBe('1 sujet');
    expect(subjectsLabel(person('A', { subjects: [
      { subjectId: 's', subjectName: 'x', state: 'NEW', role: 'EXPERT' },
      { subjectId: 't', subjectName: 'y', state: 'NEW', role: 'EXPERT' },
    ] }))).toBe('2 sujets');
    expect(interactionLabel(person('A', { lastInteractionAt: new Date(2026, 8, 12, 10).toISOString() }), now))
      .toBe('dernier échange le 12 septembre');
    expect(interactionLabel(person('A', { lastInteractionAt: new Date(2025, 8, 12, 10).toISOString() }), now))
      .toBe('dernier échange le 12 septembre 2025');
    expect(interactionLabel(person('A'), now)).toBeNull();
  });
});
