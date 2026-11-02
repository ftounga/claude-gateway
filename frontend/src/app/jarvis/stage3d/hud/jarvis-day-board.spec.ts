import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import { JarvisUniverse, UniverseChantier } from '../../scenes/jarvis-universe.models';
import { resolveShow } from '../../scenes/jarvis-show';
import { dayBoard, weatherRows } from './jarvis-day-board';
import { Weather, weatherOf } from './jarvis-weather';

function chantier(over: Partial<UniverseChantier>): UniverseChantier {
  return { number: 1, title: 'T', phase: 'EXECUTION', phaseLabel: 'Exécution', done: 1, total: 4, step: null,
    focus: false, live: false, waitingOn: [], ...over };
}

/** Un univers plus riche : 4 chantiers aux quatre temps, un client hébergé. */
function richUniverse(): JarvisUniverse {
  const u = testUniverse();
  u.clients[1].subjects[0].chantiers = [
    chantier({ number: 7, title: 'Bloqué', waitingOn: ['Paiement'] }),
    chantier({ number: 8, title: 'Calme' }),
    chantier({ number: 9, title: 'Fini', done: 4, total: 4 }),
  ];
  u.clients.push({ id: null, name: 'Hébergé', virtual: true, connected: true, missionStatus: null, truncated: false,
    toSend: [], people: [], subjects: [] });
  return u;
}

describe('F-236 / SF-236-16 — météo des chantiers (table de vérité)', () => {
  const cases: [string, Partial<UniverseChantier>, Weather, string][] = [
    ['clos par la phase', { phase: 'CLOS', done: 1, total: 4 }, 'soleil', 'terminé'],
    ['clos par l’avancement', { done: 5, total: 5, waitingOn: ['Zahi'] }, 'soleil', 'terminé'],
    ['attend et rien ne bouge', { waitingOn: ['Zahi'] }, 'orage', 'bloqué — attend Zahi'],
    ['attend mais vivant', { waitingOn: ['Zahi'], live: true }, 'nuage', 'attend Zahi'],
    ['attend mais étape en cours', { waitingOn: ['Zahi', 'Paul'], step: { rank: 2, title: 'x' } }, 'nuage',
      'attend Zahi, Paul'],
    ['vivant', { live: true }, 'soleil', 'avance'],
    ['étape en cours', { step: { rank: 2, title: 'x' } }, 'soleil', 'avance'],
    ['sans mouvement', {}, 'brume', 'sans mouvement'],
    ['total nul, rien', { done: 0, total: 0 }, 'brume', 'sans mouvement'],
  ];
  for (const [name, over, weather, note] of cases) {
    it(name, () => expect(weatherOf(chantier(over))).toEqual({ weather, note }));
  }

  it('liste météo : tous les chantiers, du plus grave au plus serein, rattachés à leur client et sujet', () => {
    const rows = weatherRows(richUniverse());
    expect(rows.map((r) => r.weather)).toEqual(['orage', 'nuage', 'brume', 'soleil']);
    expect(rows[0].node).toEqual(jasmine.objectContaining({ kind: 'chantier', key: 's-runner#7', clientKey: 'h-eden' }));
    expect(rows[1].where).toBe('CAGIP · Migration Forge');
    expect(weatherRows(null)).toEqual([]);
  });
});

describe('F-236 / SF-236-16 — tableau de bord de la journée (chiffres = univers)', () => {
  it('chiffres comptés dans l’univers de test', () => {
    const board = dayBoard(testUniverse(), null);
    const value = (id: string) => board.find((f) => f.id === id)?.value;
    expect(value('postes')).toBe('1/2');
    expect(value('chantiers')).toBe('1');
    expect(value('attentes')).toBe('1');
    expect(value('a-envoyer')).toBe('2');
    expect(value('alertes')).toBe('0');
  });

  it('univers riche : hébergé hors des postes, chantiers clos exclus, messages du tour ajoutés', () => {
    const board = dayBoard(richUniverse(), null, 1);
    const value = (id: string) => board.find((f) => f.id === id)?.value;
    expect(value('postes')).toBe('1/2');
    expect(value('chantiers')).toBe('3');
    expect(value('attentes')).toBe('2');
    expect(value('a-envoyer')).toBe('3');
  });

  it('chaque chiffre touchable ouvre le filtre « Montrer » qui retrouve ses éléments (aucun appel)', () => {
    const u = testUniverse();
    const board = dayBoard(u, null);
    for (const figure of board.filter((f) => f.filter)) {
      const focus = resolveShow(u, { filter: figure.filter!, client: null });
      expect(focus).withContext(figure.id).not.toBeNull();
      expect(focus!.items.length).withContext(figure.id).toBeGreaterThan(0);
    }
    expect(board.find((f) => f.id === 'alertes')!.filter).toBeNull();
  });

  it('alertes : poste muet et veille qui sonne comptés ; poste muet ⇒ « déconnectés » ; teinte d’alerte', () => {
    const board = dayBoard(testUniverse(), { hostDown: { clientKey: 'h-eden', name: 'Edenred' },
      liveSubjects: new Set(), calling: null, cost: null, alert: { clientKey: 'h-cagip', label: 'Veille' } });
    const alerts = board.find((f) => f.id === 'alertes')!;
    expect(alerts.value).toBe('2');
    expect(alerts.filter).toBe('deconnectes');
    expect(alerts.tone).toBe('alert');
  });

  it('rien d’alerte en rouge sans alerte ; univers absent ⇒ aucun chiffre', () => {
    expect(dayBoard(testUniverse(), null).some((f) => f.tone === 'alert')).toBeFalse();
    expect(dayBoard(null, null)).toEqual([]);
  });
});
