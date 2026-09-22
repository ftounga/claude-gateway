import { HostSpaces, VigieRadarCounts, VigieSyncSummary } from '../core/models/vigie.models';
import {
  effectiveVigieTab,
  fleetSummary,
  followUpLabel,
  importableHosts,
  syncLabel,
  syncNeedsAttention,
  toHandleLabel,
} from './vigie-fleet';

/** La Vigie en fonctions pures (F-106 / SF-106-02). */
describe('vigie-fleet', () => {
  const sync = (status: VigieSyncSummary['status'], startedAt: string,
    finishedAt: string | null = startedAt): VigieSyncSummary => ({ id: startedAt, status, startedAt, finishedAt });

  it("ouvre l'onglet nommé, et le Radar sinon", () => {
    expect(effectiveVigieTab('personnes')).toBe('personnes');
    expect(effectiveVigieTab('REUNIONS')).toBe('reunions');
    expect(effectiveVigieTab('carte')).toBe('radar');
    // F-109 / SF-109-04 : l'onglet des pages du client.
    expect(effectiveVigieTab('pages')).toBe('pages');
    // F-129 / SF-129-02 : l'onglet des présentations du client.
    expect(effectiveVigieTab('presentations')).toBe('presentations');
    expect(effectiveVigieTab(null)).toBe('radar');
  });

  it('additionne le bandeau et retient la synchro la plus récente', () => {
    const counts: Record<string, VigieRadarCounts> = {
      h1: { followUpsDue: 2, blockedSubjects: 1, lastSync: sync('SUCCEEDED', '2026-09-12T20:00:00Z') },
      h2: { followUpsDue: 1, blockedSubjects: 0, lastSync: sync('PARTIAL', '2026-09-13T20:00:00Z') },
      h3: { followUpsDue: 0, blockedSubjects: 1, lastSync: null },
    };

    const summary = fleetSummary(counts);

    expect(summary.followUpsDue).toBe(3);
    expect(summary.blockedSubjects).toBe(2);
    expect(summary.toHandle).toBe(0);
    expect(fleetSummary({ a: { followUpsDue: 0, blockedSubjects: 0, toHandle: 5, lastSync: null },
      b: { followUpsDue: 0, blockedSubjects: 0, toHandle: 2, lastSync: null } }).toHandle).toBe(7);
    expect(toHandleLabel(5)).toBe('5 à traiter');
    expect(summary.lastSync?.status).toBe('PARTIAL');
    expect(fleetSummary({}).lastSync).toBeNull();
  });

  it('dit la synchro en mots', () => {
    const now = new Date(2026, 8, 13, 9, 0, 0);
    const yesterdayEvening = new Date(2026, 8, 12, 22, 5).toISOString();
    const thisMorning = new Date(2026, 8, 13, 7, 0).toISOString();

    expect(syncLabel(null, now)).toBe('aucune synchro encore');
    expect(syncLabel(sync('RUNNING', thisMorning, null), now)).toBe('synchro en cours');
    expect(syncLabel(sync('SUCCEEDED', yesterdayEvening), now)).toBe("synchro d'hier soir complète");
    expect(syncLabel(sync('PARTIAL', thisMorning), now)).toBe("synchro d'aujourd'hui incomplète");
    expect(syncLabel(sync('FAILED', new Date(2026, 8, 1, 22).toISOString()), now))
      .toBe('synchro du 1 septembre en échec');
  });

  it("signale une synchro incomplète ou en échec, jamais une synchro réussie", () => {
    expect(syncNeedsAttention(sync('PARTIAL', '2026-09-13T20:00:00Z'))).toBeTrue();
    expect(syncNeedsAttention(sync('FAILED', '2026-09-13T20:00:00Z'))).toBeTrue();
    expect(syncNeedsAttention(sync('SUCCEEDED', '2026-09-13T20:00:00Z'))).toBeFalse();
    expect(syncNeedsAttention(null)).toBeFalse();
  });

  it('propose les clients hors Vigie, missions en cours d’abord puis par nom', () => {
    const hosts: HostSpaces[] = [
      { hostId: 'h1', name: 'Richemont', missionStatus: 'CLOSED', spaces: ['FORGE'] },
      { hostId: 'h2', name: 'edenred', missionStatus: 'ACTIVE', spaces: ['FORGE'] },
      { hostId: 'h3', name: 'CAGIP', missionStatus: 'PENDING', spaces: ['FORGE'] },
      { hostId: 'h4', name: 'FREE', missionStatus: 'ACTIVE', spaces: ['FORGE', 'VIGIE'] },
    ];

    expect(importableHosts(hosts).map((h) => h.hostId)).toEqual(['h3', 'h2', 'h1']);
  });

  it('écrit la pastille de relance', () => {
    expect(followUpLabel(1)).toBe('1 relance');
    expect(followUpLabel(3)).toBe('3 relances');
  });
});
