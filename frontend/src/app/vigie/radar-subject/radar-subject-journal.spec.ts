import { RadarCorrectionView } from '../../core/models/radar-subject.models';
import { aliasCorrection, canSplit, correctionLine } from './radar-subject-journal';

/** Les corrections d'un sujet, en mots (F-99 / SF-99-06). */
describe('radar-subject-journal', () => {
  const c = (action: string, extra: Partial<RadarCorrectionView> = {}): RadarCorrectionView => ({
    id: 'c1', subjectId: 's1', targetKind: 'SUBJECT', targetId: 's1', action, before: {}, after: {},
    createdAt: '2026-09-13T09:00:00Z', undoneAt: null, ...extra,
  });

  it('écrit les gestes sur le sujet', () => {
    expect(correctionLine(c('RENAME', { after: { name: 'MFA prestataires' } })).text).toBe('Renommé en « MFA prestataires »');
    expect(correctionLine(c('SET_STATE', { after: { state: 'BLOCKED' } })).text).toBe('État dit : bloqué');
    expect(correctionLine(c('SET_NEXT_STEP', { after: { nextStep: null } })).text).toBe('Prochaine étape effacée');
    expect(correctionLine(c('SET_NEXT_STEP', { after: { nextStep: 'Note DSI' } })).text).toBe('Prochaine étape dite : « Note DSI »');
    expect(correctionLine(c('SET_DUE_DATE', { after: { dueDate: '2026-10-15' } })).text).toBe('Échéance dite : 15 octobre 2026');
    expect(correctionLine(c('CLOSE')).text).toBe('Sujet clos');
    expect(correctionLine(c('CREATE_SUBJECT')).text).toBe('Sujet créé par votre nouvelle');
  });

  it('écrit les alias et les consignes par leur nom', () => {
    expect(correctionLine(c('ADD_ALIAS', { after: { aliasId: 'a1', alias: 'Chantier Okta' } })).text)
      .toBe('Alias ajouté : « Chantier Okta »');
    expect(correctionLine(c('REMOVE_ALIAS', { before: { aliasId: 'a1', alias: 'Okta', rejected: false } })).text)
      .toBe('Alias retiré : « Okta »');
    expect(correctionLine(c('REMOVE_ALIAS', { before: { aliasId: 'a9', alias: 'Contrat Okta', rejected: true } })).text)
      .toBe('Consigne retirée : « Contrat Okta »');
  });

  it('une séparation mène au nouveau sujet ; une fusion au sujet absorbé ou à la cible', () => {
    const split = correctionLine(c('SPLIT', { after: { source: 's1', created: 's2' } }));
    expect(split.linkSubjectId).toBe('s2');
    expect(split.linkLabel).toBe('Ouvrir le nouveau sujet');
    expect(correctionLine(c('MERGE', { subjectId: 's1', after: { source: 's3', into: 's1' } })).linkSubjectId).toBe('s3');
    expect(correctionLine(c('MERGE', { subjectId: 's1', after: { source: 's1', into: 's4' } })).text)
      .toBe('Fusionné dans un autre sujet');
  });

  it("nomme l'engagement quand la page le connaît ; une action inconnue s'écrit telle quelle", () => {
    const names = new Map([['k1', 'Relancer l’éditeur']]);
    expect(correctionLine(c('DONE', { targetKind: 'COMMITMENT', targetId: 'k1' }), names).text)
      .toBe('Engagement fait — « Relancer l’éditeur »');
    expect(correctionLine(c('POSTPONE', { targetKind: 'COMMITMENT', targetId: 'k2', after: { dueDate: '2026-10-01' } }), names).text)
      .toBe('Engagement reporté au 1 octobre 2026');
    expect(correctionLine(c('SOMETHING_NEW')).text).toBe('SOMETHING_NEW');
  });

  it("retrouve la correction active d'un geste d'alias", () => {
    const journal = [
      c('ADD_ALIAS', { id: 'old', after: { aliasId: 'a1' }, undoneAt: '2026-09-13T09:01:00Z' }),
      c('REMOVE_ALIAS', { id: 'rm', before: { aliasId: 'a1' } }),
      c('ADD_ALIAS', { id: 'add', after: { aliasId: 'a1' } }),
    ];
    expect(aliasCorrection(journal, 'ADD_ALIAS', 'a1')?.id).toBe('add');
    expect(aliasCorrection(journal, 'REMOVE_ALIAS', 'a1')?.id).toBe('rm');
    expect(aliasCorrection(journal, 'ADD_ALIAS', 'zz')).toBeNull();
  });

  it('séparer : un nom, au moins une preuve qui part et une qui reste', () => {
    expect(canSplit(1, 3, 'Okta')).toBeTrue();
    expect(canSplit(0, 3, 'Okta')).toBeFalse();
    expect(canSplit(3, 3, 'Okta')).toBeFalse();
    expect(canSplit(1, 3, '   ')).toBeFalse();
  });
});
