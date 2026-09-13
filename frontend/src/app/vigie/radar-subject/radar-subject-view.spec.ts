import {
  dayLabel,
  evidenceNumbers,
  linkLabel,
  peopleByRole,
  refsOf,
  roleLabel,
  safeLink,
  sourceView,
  stateBadge,
  whenLabel,
} from './radar-subject-view';
import { subjectDetail } from './radar-subject.fixtures';

/** La page sujet en fonctions pures (F-103 / SF-103-01). */
describe('radar-subject-view', () => {
  it("écrit chaque état en mots, avec la pastille de l'onglet Radar (§17)", () => {
    expect(stateBadge('NEW')).toEqual({ label: 'nouveau', badgeClass: 'radar-state--blue' });
    expect(stateBadge('ADVANCING')).toEqual({ label: 'avance', badgeClass: 'radar-state--success' });
    expect(stateBadge('WAITING')).toEqual({ label: 'en attente', badgeClass: 'radar-state--warning' });
    expect(stateBadge('BLOCKED')).toEqual({ label: 'bloqué', badgeClass: 'radar-state--error' });
    expect(stateBadge('DORMANT')).toEqual({ label: 'en sommeil', badgeClass: 'radar-state--neutral' });
    expect(stateBadge('CLOSE_PROPOSED')).toEqual({ label: 'clos ?', badgeClass: 'radar-state--blue' });
    expect(stateBadge('CLOSED')).toEqual({ label: 'clos', badgeClass: 'radar-state--neutral' });
    expect(stateBadge('CLOSED', true)).toEqual({ label: 'se réveille', badgeClass: 'radar-state--blue' });
    expect(stateBadge('INCONNU')).toEqual({ label: 'INCONNU', badgeClass: 'radar-state--neutral' });
  });

  it('nomme chaque source et lui donne une icône (§17)', () => {
    expect(sourceView('TEAMS_MESSAGE')).toEqual({ label: 'Message Teams', icon: 'forum' });
    expect(sourceView('TEAMS_MEETING')).toEqual({ label: 'Réunion Teams', icon: 'videocam' });
    expect(sourceView('LOCAL_RECORDING')).toEqual({ label: 'Enregistrement hors Teams', icon: 'mic' });
    expect(sourceView('USER_NOTE')).toEqual({ label: 'Votre nouvelle', icon: 'edit_note' });
    expect(sourceView('PASTED_MAIL')).toEqual({ label: 'Courriel collé', icon: 'mail' });
    expect(sourceView('AUTRE').icon).toBe('description');
  });

  it("numérote les preuves dans l'ordre où la page les cite, le même numéro partout", () => {
    const ev = (id: string) => ({ id, source: 'TEAMS_MESSAGE' as const, sourceRef: id,
      occurredAt: '2026-09-12T10:00:00Z', quote: id, deepLink: null, authorPersonId: null });
    const numbers = evidenceNumbers(subjectDetail({
      // Positions volontairement désordonnées : c'est la position qui fait foi.
      summary: [
        { id: 'f2', position: 1, text: 'B', evidenceIds: ['p3', 'p1'] },
        { id: 'f1', position: 0, text: 'A', evidenceIds: ['p1'] },
      ],
      stateEvidenceIds: ['p2', 'p1'],
      nextStepEvidenceIds: ['p3'],
      chronology: [ev('p4'), ev('p1')],
    }));

    expect(numbers.get('p1')).toBe(1);
    expect(numbers.get('p3')).toBe(2);
    expect(numbers.get('p2')).toBe(3);
    expect(numbers.get('p4')).toBe(4);
    expect(refsOf(['p3', 'p1', 'p3', 'inconnue'], numbers)).toEqual([1, 2]);
  });

  it("dit une date en mots : aujourd'hui, hier, date courte", () => {
    const now = new Date(2026, 8, 13, 18, 0, 0);
    expect(whenLabel(new Date(2026, 8, 13, 14, 32).toISOString(), now)).toBe("aujourd'hui 14:32");
    expect(whenLabel(new Date(2026, 8, 12, 9, 5).toISOString(), now)).toBe('hier 09:05');
    expect(whenLabel(new Date(2026, 8, 4, 9, 5).toISOString(), now)).toContain('4');
    expect(whenLabel(new Date(2025, 8, 4, 9, 5).toISOString(), now)).toContain('2025');
    expect(whenLabel(null, now)).toBe('—');
    expect(whenLabel('pas une date', now)).toBe('—');
    expect(dayLabel('2026-10-01')).toBe('1 octobre 2026');
    expect(dayLabel(null)).toBeNull();
  });

  it('ne rend un lien profond que s\'il est https', () => {
    expect(safeLink('https://teams.microsoft.com/l/message/1')).toBe('https://teams.microsoft.com/l/message/1');
    expect(safeLink(' https://x.sharepoint.com/a ')).toBe('https://x.sharepoint.com/a');
    expect(safeLink('javascript:alert(1)')).toBeNull();
    expect(safeLink('http://teams.microsoft.com')).toBeNull();
    expect(safeLink('https://')).toBeNull();
    expect(safeLink(null)).toBeNull();
  });

  it('écrit les rôles, et range les personnes : décide, pilote, expert, informé, puis par nom', () => {
    const role = (displayName: string, r: string) =>
      ({ id: displayName, personId: displayName, displayName, jobTitle: null, role: r, evidenceIds: [] }) as never;
    expect(roleLabel('DECIDES')).toBe('décide');
    expect(roleLabel('DRIVES')).toBe('pilote');
    expect(roleLabel('EXPERT')).toBe('expert');
    expect(roleLabel('INFORMED')).toBe('informé');
    expect(peopleByRole([role('Zoé', 'INFORMED'), role('karim', 'EXPERT'), role('Anne', 'EXPERT'),
      role('Paul', 'DECIDES'), role('Sophie', 'DRIVES')]).map((p) => p.displayName))
      .toEqual(['Paul', 'Sophie', 'Anne', 'karim', 'Zoé']);
    expect(peopleByRole(null)).toEqual([]);
  });

  it('libelle le lien selon la source — la seconde pour une réunion', () => {
    const at = new Date(2026, 8, 12, 14, 32, 10).toISOString();
    expect(linkLabel({ source: 'TEAMS_MEETING', occurredAt: at })).toBe('Ouvrir la source · 14:32:10');
    expect(linkLabel({ source: 'TEAMS_MEETING', occurredAt: 'x' })).toBe('Ouvrir la source');
    expect(linkLabel({ source: 'TEAMS_MESSAGE', occurredAt: at })).toBe('Ouvrir la source');
    expect(linkLabel({ source: 'LOCAL_RECORDING', occurredAt: at })).toBe('Ouvrir la source');
  });
});
