import { testUniverse } from '../../scenes/jarvis-universe.fixture';
import type { Ambient } from '../../scenes/jarvis-scene-bus.service';
import { CORE_KEY } from '../jarvis-link-graph';
import { directAmbient } from '../scenes3d/jarvis-ambiance-3d';
import { radarEchoes } from '../scenes3d/jarvis-radar';
import { clockX, watchClock } from './jarvis-watch-clock';

function w(id: string, runTimes: unknown, extra: Record<string, unknown> = {}): unknown {
  return { workspaceName: 'Migration Forge', watch: { id, name: `Veille ${id}`, active: true, proposed: false,
    runTimes, ...extra } };
}

describe('F-236 / SF-236-25 — horloge des veilles (pur)', () => {
  const tuesday9 = new Date(2026, 10, 3, 9, 0); // mardi 3 novembre, 9 h

  it('lun → ven, un passage par heure de veille et par jour ; actives seulement ; heures invalides ignorées', () => {
    const clock = watchClock([w('a', ['07:30', '13:30']), w('b', ['12:00', 'midi', '25:00']),
      w('c', ['10:00'], { active: false }), w('d', ['11:00'], { proposed: true })], tuesday9)!;
    expect(clock.days.map((d) => d.label)).toEqual(['lun.', 'mar.', 'mer.', 'jeu.', 'ven.']);
    expect(clock.days[1].today).toBeTrue();
    expect(clock.days[0].marks.map((m) => m.time)).toEqual(['07:30', '12:00', '13:30']);
    expect(clock.from).toBe(6 * 60);
    expect(clock.to).toBe(22 * 60);
  });

  it('la prochaine en surbrillance : la première après maintenant (une seule) ; lundi après le week-end', () => {
    const clock = watchClock([w('a', ['07:30', '13:30']), w('b', ['12:00'])], tuesday9)!;
    const marked = clock.days.flatMap((d, i) => d.marks.filter((m) => m.next).map((m) => `${i}:${m.time}`));
    expect(marked).toEqual(['1:12:00']);
    expect(clock.next).toEqual({ name: 'Veille b', subject: 'Migration Forge', when: 'aujourd’hui 12:00' });
    const friday23 = new Date(2026, 10, 6, 23, 0);
    expect(watchClock([w('a', ['07:30'])], friday23)?.next?.when).toBe('lun. 07:30');
    const sunday = new Date(2026, 10, 8, 10, 0);
    expect(watchClock([w('a', ['07:30'])], sunday)?.next?.when).toBe('lun. 07:30');
  });

  it('watches illisible ⇒ rien, aucune erreur', () => {
    for (const bad of [null, undefined, 'x', 42, {}, [null], [{}], [{ watch: { id: 'z', active: true } }],
      [w('a', 'pas une liste')]]) {
      expect(() => watchClock(bad, tuesday9)).not.toThrow();
      expect(watchClock(bad, tuesday9)).toBeNull();
    }
  });

  it('axe élargi pour une veille hors 6 h – 22 h ; position en %', () => {
    const clock = watchClock([w('a', ['05:15', '23:10'])], tuesday9)!;
    expect(clock.from).toBe(5 * 60);
    expect(clock.to).toBe(24 * 60);
    expect(clockX(clock, 5 * 60)).toBe(0);
    expect(clockX(clock, 24 * 60)).toBe(100);
  });
});

describe('F-236 / SF-236-25 — radar des alertes (pur) : échos = alertes réelles', () => {
  const quiet: Ambient = { hostDown: null, liveSubjects: new Set(['s-forge']), calling: null, cost: '1 €',
    alert: null };
  const u = testUniverse();
  const nodes = new Set(['h-cagip', 'h-eden', 's-forge', 's-doc']);

  it('aucune alerte (sujets vivants, coût) ⇒ aucun écho, aucun radar', () => {
    expect(radarEchoes(quiet, u, nodes)).toEqual([]);
    expect(radarEchoes(null, u, nodes)).toEqual([]);
    const plan = directAmbient(quiet, { universe: u, nodes, busy: false, admin: true, costFresh: true, seq: 0 });
    expect(plan?.effects.some((e) => e.kind === 'radar')).toBeFalse();
  });

  it('veille qui sonne, poste muet ⇒ écho rouge au client ; sujet qui appelle ⇒ écho d’attente à SON client', () => {
    const echoes = radarEchoes({ ...quiet, alert: { clientKey: 'h-cagip', label: 'x' },
      hostDown: { clientKey: 'h-eden', name: 'Edenred' }, calling: { subjectId: 's-doc', label: 'y' } }, u, nodes);
    expect(echoes).toEqual([
      { kind: 'veille', anchor: 'h-cagip', tone: 'alert' },
      { kind: 'poste', anchor: 'h-eden', tone: 'alert' },
      { kind: 'cloche', anchor: 'h-cagip', tone: 'wait' },
    ]);
  });

  it('client non posé ⇒ écho au cœur (rien d’inventé) ; pendant un tour, seul l’écho de la veille (A5) reste', () => {
    expect(radarEchoes({ ...quiet, hostDown: { clientKey: 'h-absent', name: 'X' } }, u, nodes))
      .toEqual([{ kind: 'poste', anchor: CORE_KEY, tone: 'alert' }]);
    const both = { ...quiet, hostDown: { clientKey: 'h-eden', name: 'Edenred' },
      alert: { clientKey: 'h-cagip', label: 'x' } };
    const idle = directAmbient(both, { universe: u, nodes, busy: false, admin: false, costFresh: false, seq: 0 });
    expect(idle?.effects).toContain({ kind: 'radar', echoes: [{ anchor: 'h-cagip', tone: 'alert' },
      { anchor: 'h-eden', tone: 'alert' }] });
    const busy = directAmbient(both, { universe: u, nodes, busy: true, admin: false, costFresh: false, seq: 0 });
    expect(busy?.effects).toContain({ kind: 'radar', echoes: [{ anchor: 'h-cagip', tone: 'alert' }] });
    expect(directAmbient({ ...quiet, hostDown: { clientKey: 'h-eden', name: 'Edenred' } },
      { universe: u, nodes, busy: true, admin: false, costFresh: false, seq: 0 })).toBeNull();
  });
});
