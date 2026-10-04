import { MapDeadline } from '../../core/models/governance.models';
import { daysBetween, deadlineGroups, dueLabel, signalCount, signalSummary, todayKey } from './forge-map-signals';
import { mapGraph, mapNode } from './forge-map.testing';

describe('forge-map-signals (F-173 / SF-173-06)', () => {
  const deadline = (dueOn: string, text = 'jeton'): MapDeadline => ({
    nodeId: null, nodeLabel: null, text, dueOn, overdue: false, path: 'acces.md', heading: null, lineNo: 1,
  });

  it('compte les jours entre deux dates', () => {
    expect(daysBetween('2026-10-04', '2026-10-18')).toBe(14);
    expect(daysBetween('2026-10-04', '2026-10-01')).toBe(-3);
    expect(todayKey(new Date(2026, 9, 4, 23, 30))).toBe('2026-10-04');
  });

  it('groupe et trie : dépassées, dans les 14 jours, plus tard', () => {
    const groups = deadlineGroups(
      [deadline('2026-12-01', 'certificat'), deadline('2026-10-10', 'jeton'), deadline('2026-09-01', 'accès'), deadline('2026-10-18', 'vpn')],
      '2026-10-04',
    );
    expect(groups.overdue.map((d) => d.text)).toEqual(['accès']);
    expect(groups.soon.map((d) => d.text)).toEqual(['jeton', 'vpn']);
    expect(groups.later.map((d) => d.text)).toEqual(['certificat']);
    expect(groups.soon[0].days).toBe(6);
  });

  it('le compteur et le résumé', () => {
    const graph = mapGraph(
      [mapNode('a', { traps: 2, stale: true }), mapNode('b', { traps: 1 })],
      { deadlines: [deadline('2026-09-01'), deadline('2026-10-05'), deadline('2027-01-01')], toMap: [{ nodeId: null, label: null, text: 'x', path: null, heading: null, lineNo: null }] },
    );
    expect(signalCount(graph, '2026-10-04')).toBe(2);
    expect(signalCount(null, '2026-10-04')).toBe(0);
    expect(signalSummary(graph, '2026-10-04')).toEqual({ traps: 3, stale: 1, overdue: 1, toMap: 1 });
  });

  it('dit l’échéance en mots', () => {
    expect(dueLabel(-3)).toBe('dépassée depuis 3 j');
    expect(dueLabel(0)).toBe("aujourd'hui");
    expect(dueLabel(5)).toBe('dans 5 j');
  });
});
