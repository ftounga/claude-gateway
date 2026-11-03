import { Situation, SituationChantier, SituationEnvoi } from '../../core/models/situation.models';
import { SITUATION_ESSENTIALS_MAX, situationEssentials } from './situation-essentials';

/** F-225 / SF-225-04 — l'essentiel du point de situation : 1 à 5 lignes dérivées, aucune IA. */
describe('situationEssentials', () => {
  const now = Date.parse('2026-11-03T18:00:00Z');

  const chantier = (extra: Partial<SituationChantier> = {}): SituationChantier => ({
    number: 4, title: 'Migration Postgres', phaseLabel: 'Exécution', focus: true, done: 5, total: 14,
    nextRank: 6, nextStep: 'Basculer la lecture sur la réplique', planAwaitingValidation: false, diagnosisPending: false,
    who: null, whoDetail: null, stale: false, ...extra,
  });

  const envoi = (extra: Partial<SituationEnvoi> = {}): SituationEnvoi => ({
    messageId: 'm1', blockIndex: 0, kind: 'MAIL', subject: 'Fenêtre de bascule', recipient: 'Zahi', proposedAt: null,
    sentAt: null, attenteKey: null, attenteStatus: null, excerpt: null, ...extra,
  });

  const base = (extra: Partial<Situation> = {}): Situation => ({
    generatedAt: '2026-11-03T18:00:00Z', lastActivityAt: '2026-11-03T13:00:00Z', idleHours: 5, mode: 'GUIDE',
    chantiers: [], attentes: [], envois: [], pieces: [], suites: [],
    exchange: { request: 'Où en est la migration ?', requestAt: '2026-11-03T12:58:00Z',
      reply: '<<essentiel>>La réplique est prête, **5 étapes sur 14** faites.<</essentiel>>\n\nDétail long.',
      replyAt: '2026-11-03T13:00:00Z' },
    ...extra,
  });

  it('chantier au focus, réponse et étape suivante : Où / Fait / Prochaine, dans l’ordre, avec leurs cibles', () => {
    const lines = situationEssentials(base({ chantiers: [chantier(), chantier({ number: 5, focus: false, title: null })] }), null, now);

    expect(lines.map((l) => l.key)).toEqual(['where', 'last', 'next']);
    expect(lines[0].text).toBe('Chantier 4 — Migration Postgres · Exécution · 5/14 (et 1 autre ouvert)');
    expect(lines[0].target).toEqual({ kind: 'chantier', number: 4 });
    expect(lines[1].text).toBe('Il y a 5 h : La réplique est prête, 5 étapes sur 14 faites.');
    expect(lines[1].target).toEqual({ kind: 'thread' });
    expect(lines[2].text).toBe('Étape 6 : Basculer la lecture sur la réplique');
    expect(lines[2].target).toEqual({ kind: 'chantier', number: 4 });
  });

  it('plan à valider : ce qui attend prime, pas de « prochaine action » qui le doublerait', () => {
    const lines = situationEssentials(base({ chantiers: [chantier({ planAwaitingValidation: true })] }), null, now);

    expect(lines.map((l) => l.key)).toEqual(['where', 'last', 'waiting']);
    expect(lines[2].text).toBe('Valider le plan du chantier 4');
    expect(lines[2].target).toEqual({ kind: 'chantier', number: 4 });
  });

  it('mails pas marqués envoyés, regroupés : une ligne qui mène au détail', () => {
    const lines = situationEssentials(base({
      envois: [envoi(), envoi({ messageId: 'm2' }), envoi({ messageId: 'm3', recipient: 'Paul', subject: 'Point' }),
        envoi({ messageId: 'm4', recipient: 'Ana', sentAt: '2026-11-03T10:00:00Z' })],
    }), null, now);

    const waiting = lines.find((l) => l.key === 'waiting');
    expect(waiting?.text).toBe('2 mails pas marqués envoyés (à Zahi…)');
    expect(waiting?.target).toEqual({ kind: 'detail' });
  });

  it('jamais plus de cinq lignes, au plus deux « vous attend »', () => {
    const lines = situationEssentials(base({
      chantiers: [chantier({ diagnosisPending: true })],
      envois: [envoi()],
      attentes: [{ id: 'x', description: 'Recevoir le DPA signé', status: 'DEMANDE', qui: 'AUTRUI', to: 'Juridique',
        since: '2026-10-30T09:00:00Z', chantierNumber: null }],
      suites: [{ title: 'Relancer le juridique', request: 'r' }],
    }), null, now);

    expect(lines.length).toBeLessThanOrEqual(SITUATION_ESSENTIALS_MAX);
    expect(lines.filter((l) => l.key === 'waiting').length).toBe(2);
  });

  it('sans chantier : au fil de l’eau, et la première suite comme prochaine action', () => {
    const lines = situationEssentials(base({ suites: [{ title: 'Écrire le README', request: 'r' }, { title: 'B', request: 'b' }] }), null, now);

    expect(lines[0].text).toBe('Au fil de l’eau, sans chantier ouvert — dernier échange il y a 5 h');
    expect(lines[lines.length - 1]).toEqual({ key: 'next', label: 'Prochaine action', target: { kind: 'thread' },
      text: 'Écrire le README (ou 1 autre suite)' });
  });

  it('une attente rangée sous un chantier mène à ce chantier', () => {
    const lines = situationEssentials(base({ chantiers: [chantier({ nextStep: null })],
      attentes: [{ id: 'a', description: 'Accès VPN', status: 'A_FAIRE', qui: 'MOI', to: null, since: null, chantierNumber: 4 }] }), null, now);

    expect(lines.find((l) => l.key === 'waiting')?.target).toEqual({ kind: 'chantier', number: 4 });
  });

  it('dans un volet : le chantier du volet, pas celui au focus', () => {
    const lines = situationEssentials(base({ chantiers: [chantier(), chantier({ number: 7, focus: false, title: 'Docs' })] }), 7, now);

    expect(lines[0].text).toContain('Chantier 7 — Docs');
  });

  it('terminal vide : une seule ligne, non cliquable', () => {
    const lines = situationEssentials(base({ exchange: null, lastActivityAt: null }), null, now);

    expect(lines).toEqual([{ key: 'empty', label: '', text: 'Rien encore dans ce terminal.', target: null }]);
  });
});
