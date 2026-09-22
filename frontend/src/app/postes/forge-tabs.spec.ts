import { RunnerHostOverview } from '../core/models/atelier.models';
import { GovernanceIntegrite, GovernanceMap } from '../core/models/governance.models';
import { effectiveTab, governanceTabSummary, mapTabSummary, tabsFor } from './forge-tabs';

/** Les onglets du poste ouvert (F-98 / SF-98-02) et ce qu'ils disent sans être ouverts. */
describe('forge-tabs', () => {
  const machine: RunnerHostOverview = {
    id: 'h1', name: 'EDENRED', connected: true, activeProjects: 0, createdAt: '', projects: [],
  };
  const hosted: RunnerHostOverview = {
    id: null, name: 'Hébergé', virtual: true, connected: false, activeProjects: 0, createdAt: null,
    projects: [],
  };
  const map = (facts: number, extra: Partial<GovernanceMap> = {}): GovernanceMap => ({
    hostRef: 'h1', hostId: 'h1', hostName: 'EDENRED', supported: true, governed: true, readable: true,
    message: null, files: [], filesExpected: 6, filesPresent: 6, sections: 4, facts, ...extra,
  });
  const integrite = (errors: number, warnings = 0, inspected = true): GovernanceIntegrite => ({
    hostRef: 'h1', hostId: 'h1', inspected,
    errors: Array.from({ length: errors }, (_, i) => ({ rule: 'r', target: `e${i}`, message: 'm' })),
    warnings: Array.from({ length: warnings }, (_, i) => ({ rule: 'r', target: `w${i}`, message: 'm' })),
  });

  it('les onglets d\'une machine (dont Présentations), Projets seul pour « Hébergé »', () => {
    expect(tabsFor(machine))
      .toEqual(['projets', 'carte', 'gouvernance', 'activite', 'pages', 'presentations']);
    expect(tabsFor(hosted)).toEqual(['projets']);
  });

  it('ouvre l’onglet demandé, et Projets quand il est absent, inconnu ou indisponible', () => {
    expect(effectiveTab('carte', machine)).toBe('carte');
    expect(effectiveTab(' GOUVERNANCE ', machine)).toBe('gouvernance');
    expect(effectiveTab(null, machine)).toBe('projets');
    expect(effectiveTab('radar', machine)).toBe('projets');
    expect(effectiveTab('carte', hosted)).toBe('projets');
    // F-109 / SF-109-04 : les pages d'une machine ; « Hébergé » n'a pas l'outil, donc pas l'onglet.
    expect(effectiveTab('pages', machine)).toBe('pages');
    expect(effectiveTab('pages', hosted)).toBe('projets');
  });

  it('résume la carte : faits, hors ligne, ou rien', () => {
    expect(mapTabSummary(map(12), true)).toBe('12 faits');
    expect(mapTabSummary(map(1), true)).toBe('1 fait');
    expect(mapTabSummary(map(12), false)).toBe('hors ligne');
    expect(mapTabSummary(null, true)).toBeNull();
    expect(mapTabSummary(map(0, { governed: false }), true)).toBeNull();
    expect(mapTabSummary(map(0, { readable: false }), true)).toBeNull();
  });

  it('résume la gouvernance : une mise à jour passe avant une erreur, un avertissement ne dit rien', () => {
    expect(governanceTabSummary(2, integrite(1))).toEqual({ label: 'à appliquer', tone: 'warning' });
    expect(governanceTabSummary(0, integrite(1))).toEqual({ label: 'à corriger', tone: 'error' });
    expect(governanceTabSummary(0, integrite(0, 3))).toBeNull();
    expect(governanceTabSummary(0, integrite(2, 0, false))).toBeNull();
    expect(governanceTabSummary(0, null)).toBeNull();
  });
});
