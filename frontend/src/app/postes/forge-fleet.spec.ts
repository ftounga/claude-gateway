import { HostProjectSummary, RunnerHostOverview } from '../core/models/atelier.models';
import {
  HOSTED_REF,
  awaitingCount,
  defaultHostRef,
  filterMatch,
  groupHosts,
  hostRef,
  normalizeSearch,
} from './forge-fleet';

/**
 * Les règles d'ordre de la Forge refondue (F-98 / SF-98-01) : ce qui attend passe devant (D4), puis
 * ce qui est en ligne, puis le reste — et le filtre cherche dans les postes ET leurs projets.
 */
describe('forge-fleet', () => {
  const project = (id: string, name: string, awaiting = false): HostProjectSummary => ({
    id, name, calls: 0, active: false,
    terminalPreview: awaiting ? { activity: 'AWAITING_APPROVAL', lines: [] } : null,
  });

  const host = (id: string, name: string, extra: Partial<RunnerHostOverview> = {}): RunnerHostOverview => ({
    id, name, connected: true, activeProjects: 0, createdAt: '', projects: [], ...extra,
  });

  const hosted: RunnerHostOverview = {
    id: null, name: 'Hébergé', virtual: true, connected: false, activeProjects: 0, createdAt: null,
    projects: [],
  };

  const online = (h: RunnerHostOverview) => h.connected;

  it('donne à un poste son identifiant pour adresse, et « heberge » au poste virtuel', () => {
    expect(hostRef(host('h1', 'A'))).toBe('h1');
    expect(hostRef(hosted)).toBe(HOSTED_REF);
  });

  it('compte les autorisations des projets ET du terminal du poste', () => {
    const edenred = host('h1', 'EDENRED', {
      projects: [project('w1', 'a', true), project('w2', 'b'), project('w3', 'c', true)],
      hostTerminalPreview: { activity: 'AWAITING_APPROVAL', lines: [] },
    });

    expect(awaitingCount(edenred)).toBe(3);
    expect(awaitingCount(host('h2', 'FREE'))).toBe(0);
  });

  it('range dans l’ordre À regarder › En ligne › Hors ligne › Sans machine › Clôturés', () => {
    const groups = groupHosts([
      host('off', 'CAGIP', { connected: false }),
      host('closed', 'Ancien', { missionStatus: 'CLOSED' }),
      hosted,
      host('on', 'FREE'),
      host('wait', 'EDENRED', { projects: [project('w1', 'audit', true)] }),
    ], online, '');

    expect(groups.map((g) => g.key)).toEqual(['attention', 'online', 'offline', 'hosted', 'closed']);
    expect(groups.map((g) => g.rows[0].ref)).toEqual(['wait', 'on', 'off', 'heberge', 'closed']);
    expect(groups[0].rows[0].awaiting).toBe(1);
  });

  it('ne rend pas un groupe vide', () => {
    expect(groupHosts([host('h1', 'A')], online, '').map((g) => g.key)).toEqual(['online']);
  });

  it('range une mission clôturée avec les clôturées, même si quelque chose y attend', () => {
    const groups = groupHosts(
      [host('h1', 'A', { missionStatus: 'CLOSED', projects: [project('w1', 'x', true)] })], online, '');

    expect(groups.map((g) => g.key)).toEqual(['closed']);
  });

  it('filtre par le nom du poste, sans casse ni accents', () => {
    expect(normalizeSearch('  Évry ')).toBe('evry');
    expect(filterMatch(host('h1', 'Évry'), 'EVR')).toBe('host');
    expect(filterMatch(host('h1', 'FREE'), '')).toBe('host');
  });

  it('garde un poste qui ne correspond que par ses projets, avec leur nombre', () => {
    const free = host('h1', 'FREE', {
      projects: [project('w1', 'audit-iam'), project('w2', 'audit-kms'), project('w3', 'rapport')],
    });

    expect(filterMatch(free, 'audit')).toBe(2);
    expect(filterMatch(free, 'zzz')).toBeNull();
    const [group] = groupHosts([free, host('h2', 'CAGIP')], online, 'audit');
    expect(group.rows.map((r) => r.ref)).toEqual(['h1']);
    expect(group.rows[0].matchedProjects).toBe(2);
  });

  it('ouvre par défaut : À regarder, sinon en ligne, sinon hors ligne, sinon Hébergé', () => {
    const off = host('off', 'CAGIP', { connected: false });
    const on = host('on', 'FREE');
    const wait = host('wait', 'EDENRED', { connected: false, projects: [project('w', 'x', true)] });
    const closed = host('closed', 'Ancien', { missionStatus: 'CLOSED' });

    expect(defaultHostRef([off, on, wait, hosted], online)).toBe('wait');
    expect(defaultHostRef([off, on, hosted], online)).toBe('on');
    expect(defaultHostRef([off, hosted], online)).toBe('off');
    expect(defaultHostRef([closed, hosted], online)).toBe(HOSTED_REF);
    expect(defaultHostRef([], online)).toBe(HOSTED_REF);
  });

  it('range par ce qui attend dans l’espace : les relances dues de la Vigie (F-106)', () => {
    const followUps = (h: RunnerHostOverview) => (h.id === 'h2' ? 3 : 0);
    const hosts = [host('h1', 'FREE'), host('h2', 'CAGIP', { connected: false })];

    const groups = groupHosts(hosts, online, '', followUps);

    expect(groups.map((g) => g.key)).toEqual(['attention', 'online']);
    expect(groups[0].rows[0].awaiting).toBe(3);
    expect(defaultHostRef(hosts, online, followUps)).toBe('h2');
    // Sans la fonction, la règle de la Forge est inchangée.
    expect(defaultHostRef(hosts, online)).toBe('h1');
  });
});
