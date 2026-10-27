import {
  ClientBoardChantier,
  freeSlots,
  groupOf,
  idleLabel,
  isSelectable,
  isSnoozed,
  launchSummary,
  matchesFilter,
  overLimitMessage,
  sinceLabel,
  untilLabel,
} from './client-board.models';

/** F-220 / SF-220-03 — les lectures pures du tableau du client. */
describe('client-board.models', () => {
  const chantier = (extra: Partial<ClientBoardChantier> = {}): ClientBoardChantier => ({
    workspaceId: 'w1', workspaceName: 'ingress', hostTerminal: false, number: 1, title: 'Certificat',
    phase: 'EXECUTION', phaseLabel: 'Exécution', focus: true, openedAt: null, done: 1, total: 3,
    nextStep: { rank: 2, title: 'Remplacer' },
    unblocker: { who: 'OUTIL', label: 'L\'outil peut avancer', detail: 'd', step: 2, waitingOn: null, since: null },
    ready: true, missing: [], lastActivityAt: null, idleDays: 0, dormant: false, live: false, question: null,
    run: null, ...extra,
  });

  it('ancienneté : dormant mis en avant, sinon aujourd\'hui ou il y a n j', () => {
    expect(idleLabel({ idleDays: 6, dormant: true })).toBe('dormant depuis 6 j');
    expect(idleLabel({ idleDays: 0, dormant: false })).toBe('actif aujourd\'hui');
    expect(idleLabel({ idleDays: 1, dormant: false })).toBe('il y a 1 j');
  });

  it('depuis quand une attente court', () => {
    const now = new Date('2026-10-27T12:00:00Z');
    expect(sinceLabel('2026-10-24T09:00:00Z', now)).toBe('depuis 3 j');
    expect(sinceLabel('2026-10-27T08:00:00Z', now)).toBe('depuis aujourd\'hui');
    expect(sinceLabel(null, now)).toBeNull();
    expect(sinceLabel('pas une date', now)).toBeNull();
  });

  it('groupe et filtres', () => {
    expect(groupOf(chantier())).toBe('OUTIL');
    expect(groupOf(chantier({ unblocker: null }))).toBe('AUTRUI');
    expect(matchesFilter(chantier(), null)).toBeTrue();
    expect(matchesFilter(chantier(), 'MOI')).toBeFalse();
    expect(matchesFilter(chantier({ dormant: true }), 'DORMANT')).toBeTrue();
    expect(matchesFilter(chantier(), 'DORMANT')).toBeFalse();
  });

  it('sélectionnable : prêt et rien ne tourne', () => {
    expect(isSelectable(chantier())).toBeTrue();
    expect(isSelectable(chantier({ ready: false }))).toBeFalse();
    expect(isSelectable(chantier({
      run: { id: 'r', workspaceId: 'w1', chantierNumber: 1, chantierTitle: null, status: 'RUNNING', turns: 1,
        stepsDone: 1, stepsTotal: 3, costUsd: 0, stopReason: null, errorCode: null, startedAt: '', finishedAt: null },
    }))).toBeFalse();
  });

  it('SF-220-06 : un reporté n\'est jamais sélectionnable ; date de fin courte', () => {
    expect(isSnoozed(chantier({ snoozedUntil: '2026-10-30T10:00:00Z' }))).toBeTrue();
    expect(isSnoozed(chantier({ snoozedUntil: null }))).toBeFalse();
    expect(isSnoozed(chantier())).toBeFalse();
    expect(isSelectable(chantier({ snoozedUntil: '2026-10-30T10:00:00Z' }))).toBeFalse();
    expect(untilLabel('2026-10-30T10:00:00')).toBe('30/10');
    expect(untilLabel(null)).toBe('');
    expect(untilLabel('n')).toBe('');
  });

  it('places libres et refus écrit au-delà', () => {
    const board = { autonomy: { running: 1, maxParallel: 4 } };
    expect(freeSlots(board)).toBe(3);
    expect(overLimitMessage(3, board)).toBeNull();
    expect(overLimitMessage(4, board)).toBe('Quatre chantiers autonomes au maximum : 1 en cours, 3 places libres.');
    expect(overLimitMessage(1, { autonomy: { running: 4, maxParallel: 4 } }))
      .toBe('Quatre chantiers autonomes au maximum : 4 en cours, aucune place libre.');
  });

  it('bilan d\'un lancement groupé', () => {
    expect(launchSummary(3, [])).toBe('3 chantiers lancés en autonome.');
    expect(launchSummary(1, ['api · chantier 2 — occupé'])).toBe('1 chantier lancé en autonome ; 1 refusé : api · chantier 2 — occupé');
    expect(launchSummary(0, ['a', 'b'])).toBe('Aucun chantier lancé ; 2 refusés : a ; b');
  });
});
