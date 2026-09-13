import { HostProjectSummary, TerminalPreview } from '../core/models/atelier.models';
import { filterProjects, sortProjects, tileCenter } from './forge-projects';

/** La grille des projets (F-98 / SF-98-03) : l'ordre des tuiles et ce que chacune montre. */
describe('forge-projects', () => {
  const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();
  const project = (id: string, name: string, extra: Partial<HostProjectSummary> = {}): HostProjectSummary => ({
    id, name, calls: 0, active: false, lastActivityAt: null, ...extra,
  });
  const preview = (activity: TerminalPreview['activity'], lines: string[] = []): TerminalPreview => ({
    activity, lines,
  });

  it('montre au centre : l’autorisation qui attend, sinon l’aperçu, sinon le repos', () => {
    expect(tileCenter(project('a', 'a', { terminalPreview: preview('AWAITING_APPROVAL', ['x']) })))
      .toBe('awaiting');
    expect(tileCenter(project('a', 'a', { terminalPreview: preview('RUNNING') }))).toBe('preview');
    expect(tileCenter(project('a', 'a', { terminalPreview: preview('IDLE', ['$ ls']) }))).toBe('preview');
    // Un aperçu au repos sans lignes n'a rien à dire : la tuile dit « au repos ».
    expect(tileCenter(project('a', 'a', { terminalPreview: preview('IDLE') }))).toBe('idle');
    expect(tileCenter(project('a', 'a'))).toBe('idle');
  });

  it('« Actifs d’abord » : ce qui attend, puis ce qui travaille ou vit, puis le plus récent', () => {
    const projects = [
      project('old', 'old', { lastActivityAt: ago(50) }),
      project('live', 'live', { liveTerminal: true, lastActivityAt: ago(40) }),
      project('fresh', 'fresh', { lastActivityAt: ago(1) }),
      project('wait', 'wait', { terminalPreview: preview('AWAITING_APPROVAL') }),
      project('busy', 'busy', { active: true, lastActivityAt: ago(2) }),
    ];

    expect(sortProjects(projects, 'actifs').map((p) => p.id)).toEqual(['wait', 'busy', 'live', 'fresh', 'old']);
  });

  it('« A → Z » : ordre alphabétique français, sans casse ni accents', () => {
    const projects = [project('1', 'zèbre'), project('2', 'Écluse'), project('3', 'avion'), project('4', 'Bateau')];

    expect(sortProjects(projects, 'alpha').map((p) => p.name)).toEqual(['avion', 'Bateau', 'Écluse', 'zèbre']);
  });

  it('« Récents » : le plus récent d’abord, sans activité (ou illisible) à la fin, puis par nom', () => {
    const projects = [
      project('1', 'b', { lastActivityAt: null }),
      project('2', 'c', { lastActivityAt: ago(10) }),
      project('3', 'a', { lastActivityAt: 'pas-une-date' }),
      project('4', 'd', { lastActivityAt: ago(1) }),
    ];

    expect(sortProjects(projects, 'recents').map((p) => p.name)).toEqual(['d', 'c', 'a', 'b']);
  });

  it('ne modifie jamais la liste reçue', () => {
    const projects = [project('1', 'b'), project('2', 'a')];

    sortProjects(projects, 'alpha');

    expect(projects.map((p) => p.name)).toEqual(['b', 'a']);
  });

  it('filtre les projets par leur nom, et rend tout sur un filtre vide', () => {
    const projects = [project('1', 'audit-iam'), project('2', 'Rapport'), project('3', 'audit-kms')];

    expect(filterProjects(projects, 'AUDIT').map((p) => p.id)).toEqual(['1', '3']);
    expect(filterProjects(projects, '  ')).toBe(projects);
  });
});
