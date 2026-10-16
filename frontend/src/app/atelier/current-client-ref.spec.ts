import { WorkspaceSummary } from '../core/models/atelier.models';
import { currentClientRefOf } from './current-client-ref';

/** Le client courant imposé par le terminal ouvert (F-199 / SF-199-01). */
describe('currentClientRefOf', () => {
  const summary = (patch: Partial<WorkspaceSummary>): WorkspaceSummary => ({
    id: 'w1', name: 'projet', createdAt: '2026-10-16T00:00:00Z', source: 'ARCHIVE', gitRepo: null,
    ...patch,
  } as WorkspaceSummary);

  it('aucun terminal ouvert : le client courant n\'est pas touché', () => {
    expect(currentClientRefOf(null, null, null)).toBeUndefined();
  });

  it('le poste du terminal ouvert devient le client courant', () => {
    expect(currentClientRefOf('w1', summary({ hostId: 'h-edenred', hostName: 'EDENRED' }), 'h-edenred'))
      .toBe('h-edenred');
  });

  it('un terminal sans poste est chez « Hébergé »', () => {
    expect(currentClientRefOf('w1', summary({ hostId: null, hostName: null }), null)).toBe('heberge');
  });

  it('poste pas encore connu (backend antérieur, détail non lu) : l\'ancien client est effacé', () => {
    expect(currentClientRefOf('w1', summary({ hostName: 'EDENRED' }), null)).toBeNull();
    expect(currentClientRefOf('w1', summary({}), null)).toBeNull();
    expect(currentClientRefOf('w1', null, null)).toBeNull();
  });
});
