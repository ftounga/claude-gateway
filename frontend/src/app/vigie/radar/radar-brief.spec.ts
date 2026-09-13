import { RadarCoverageItem, RadarSyncView } from '../../core/models/radar.models';
import {
  briefDay,
  coverageHeading,
  coverageKindIcon,
  coverageSourceIcon,
  coverageStatusLabel,
  itemGestures,
  ruleLabel,
} from './radar-brief';

/** Le résumé du matin, en fonctions pures (F-102 / SF-102-01). */
describe('radar-brief', () => {
  const sync = (startedAt: string | null): RadarSyncView => ({
    id: 's1', status: 'SUCCEEDED', startedAt, finishedAt: null, trigger: 'SCHEDULED', scheduledFor: null, summary: null,
  });

  const item = (extra: Partial<RadarCoverageItem> = {}): RadarCoverageItem => ({
    ref: '19:x', label: 'Projet IAM', kind: 'CONVERSATION', status: 'PARTIAL', detail: null, actions: ['IGNORE'],
    rule: null, ...extra,
  });

  it('le jour du résumé commence par une capitale', () => {
    expect(briefDay(new Date(2026, 8, 15, 8, 0))).toBe('Mardi 15 septembre');
  });

  it("l'en-tête de la couverture date la synchro", () => {
    const now = new Date(2026, 8, 15, 8, 0);
    expect(coverageHeading(sync(new Date(2026, 8, 14, 22, 0).toISOString()), now))
      .toBe("Synchro d'hier, 22 h 00 — ce qui a été lu");
    expect(coverageHeading(sync(new Date(2026, 8, 15, 7, 5).toISOString()), now))
      .toBe("Synchro d'aujourd'hui, 7 h 05 — ce qui a été lu");
    expect(coverageHeading(sync(new Date(2026, 8, 10, 22, 0).toISOString()), now))
      .toBe('Synchro du 10 septembre, 22 h 00 — ce qui a été lu');
    expect(coverageHeading(null, now)).toBe('Aucune synchro encore');
    expect(coverageHeading(sync('pas une date'), now)).toBe('Dernière synchro — ce qui a été lu');
  });

  it('dit les manques en mots, avec leur icône', () => {
    expect(coverageStatusLabel('NO_TRANSCRIPT')).toBe('sans transcription');
    expect(coverageStatusLabel('UNREAD_CHANNEL')).toBe('canal non lu');
    expect(coverageStatusLabel('FAILED')).toBe('non lu');
    expect(coverageKindIcon('MEETING')).toBe('videocam');
    expect(coverageKindIcon('CONVERSATION')).toBe('forum');
    expect(coverageSourceIcon('RECORDINGS')).toBe('mic');
    expect(ruleLabel('IGNORE')).toBe('ignoré');
    expect(ruleLabel(null)).toBeNull();
  });

  it("les gestes d'un manque : lire le canal d'abord, aucun si une règle est posée", () => {
    expect(itemGestures(item({ kind: 'CHANNEL', status: 'UNREAD_CHANNEL', actions: ['IGNORE', 'READ_CHANNEL'] })))
      .toEqual(['READ_CHANNEL', 'IGNORE']);
    expect(itemGestures(item())).toEqual(['IGNORE']);
    expect(itemGestures(item({ rule: 'IGNORE' }))).toEqual([]);
    expect(itemGestures(item({ kind: 'MEETING', actions: [] }))).toEqual([]);
  });
});
