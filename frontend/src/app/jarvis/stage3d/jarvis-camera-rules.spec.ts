import {
  CameraDirector, CameraInput, ENTRY_FULL_MS, ENTRY_KEY, IDLE_OVERVIEW_MS, MOVE_GAP_MS, NODE_DISTANCE,
  PICK_RADIUS_PX, Spring, entryFrame, entryKindToday, exitFrame, nearestAngle, nodeShot, pickNearest,
} from './jarvis-camera-rules';

const CHANTIER = { key: 's-forge#3', kind: 'chantier' as const, x: 3, y: 1, z: 4 };
const BASE: CameraInput = { focus: null, subjectId: 's-forge', listening: false, waiting: false, overview: 14,
  portrait: false };

describe('F-236 / SF-236-05 — caméra : directeur, ressort, transitions', () => {
  describe('directeur (§9.3)', () => {
    it('repos ⇒ vue d’ensemble ; nœud cité ⇒ cadré à sa distance (× 1,5 en portrait)', () => {
      const d = new CameraDirector();
      expect(d.decide(BASE, 0).kind).toBe('overview');
      const shot = d.decide({ ...BASE, focus: CHANTIER }, 3000);
      expect(shot).toEqual(jasmine.objectContaining({ kind: 'node', key: 's-forge#3', distance: NODE_DISTANCE.chantier }));
      expect(nodeShot(CHANTIER, true).distance).toBe(NODE_DISTANCE.chantier * 1.5);
    });

    it('aucun déplacement ne démarre pendant l’écoute', () => {
      const d = new CameraDirector();
      d.decide(BASE, 0);
      expect(d.decide({ ...BASE, focus: CHANTIER, listening: true }, 5000).kind).toBe('overview');
      expect(d.moves).toBe(0);
      expect(d.decide({ ...BASE, focus: CHANTIER }, 5100).kind).toBe('node');
    });

    it('au plus un déplacement toutes les 2,5 s', () => {
      const d = new CameraDirector();
      d.decide(BASE, 0);
      d.decide({ ...BASE, focus: CHANTIER }, 3000);
      const other = { ...CHANTIER, key: 'h-cagip', kind: 'client' as const };
      expect(d.decide({ ...BASE, focus: other }, 3000 + MOVE_GAP_MS - 1).key).toBe('s-forge#3');
      expect(d.decide({ ...BASE, focus: other }, 3000 + MOVE_GAP_MS).key).toBe('h-cagip');
      expect(d.moves).toBe(2);
    });

    it('changement de sujet ⇒ dézoom immédiat ; 10 s sans citation ⇒ vue d’ensemble', () => {
      const d = new CameraDirector();
      d.decide(BASE, 0);
      d.decide({ ...BASE, focus: CHANTIER }, 3000);
      expect(d.decide({ ...BASE, subjectId: 's-doc' }, 3100).kind).toBe('overview');
      const e = new CameraDirector();
      e.decide(BASE, 0);
      e.decide({ ...BASE, focus: CHANTIER }, 3000);
      expect(e.decide(BASE, 3000 + IDLE_OVERVIEW_MS - 1).kind).toBe('node');
      expect(e.decide(BASE, 3000 + IDLE_OVERVIEW_MS).kind).toBe('overview');
    });

    it('autorisation S2 ouverte ⇒ recul de 20 %', () => {
      const d = new CameraDirector();
      expect(d.decide({ ...BASE, waiting: true, focus: CHANTIER }, 0)).toEqual(
        jasmine.objectContaining({ kind: 'overview', distance: 14 * 1.2 }));
    });

    it('le cadrage d’un nœud se place côté extérieur : le cœur n’est jamais traversé', () => {
      const shot = nodeShot(CHANTIER, false);
      // La caméra est au-delà du nœud par rapport au cœur (même direction horizontale).
      expect(Math.sin(shot.yaw) * CHANTIER.x + Math.cos(shot.yaw) * CHANTIER.z).toBeGreaterThan(0);
    });
  });

  describe('ressort amorti critique', () => {
    it('parti au repos, ne dépasse jamais la cible ; atteint la cible en ~1,4 s', () => {
      const s = new Spring(0);
      s.target = 10;
      let max = 0;
      for (let t = 0; t < 3000; t += 16) {
        max = Math.max(max, s.step(16));
      }
      expect(max).toBeLessThanOrEqual(10 + 1e-9);
      const r = new Spring(0);
      r.target = 10;
      for (let t = 0; t < 1400; t += 16) {
        r.step(16);
      }
      expect(r.value).toBeGreaterThan(9.5);
    });

    it('indépendant de la cadence (30 et 60 i/s ⇒ même position)', () => {
      const a = new Spring(0);
      const b = new Spring(0);
      a.target = 5;
      b.target = 5;
      for (let i = 0; i < 30; i++) {
        a.step(1000 / 30);
      }
      for (let i = 0; i < 60; i++) {
        b.step(1000 / 60);
      }
      expect(a.value).toBeCloseTo(b.value, 6);
    });

    it('le lacet prend toujours le chemin court', () => {
      expect(nearestAngle(0.1, Math.PI * 2 - 0.1)).toBeCloseTo(-0.1, 6);
      expect(nearestAngle(3, -3)).toBeCloseTo(2 * Math.PI - 3, 6);
    });
  });

  describe('transitions d’écran', () => {
    it('T-E1 : fond qui s’éclaire, cœur qui s’allume, caméra qui recule depuis le cœur, cascade, HUD en dernier', () => {
      expect(entryFrame('full', 0).light).toBe(0);
      expect(entryFrame('full', 300).light).toBe(1);
      expect(entryFrame('full', 500).distance).toBeLessThan(0.1);
      expect(entryFrame('full', 1800).distance).toBe(1);
      const f = entryFrame('full', 1250);
      expect(f.clientAt(0)).toBeGreaterThan(f.clientAt(1));
      expect(entryFrame('full', 1800).hud).toBe(0);
      expect(entryFrame('full', ENTRY_FULL_MS).done).toBeTrue();
      expect(entryFrame('none', 0).done).toBeTrue();
    });

    it('T-E2 (0,9 s) : fondu-échelle 0,96 → 1', () => {
      expect(entryFrame('short', 0).core).toBeCloseTo(0.96, 5);
      expect(entryFrame('short', 900).done).toBeTrue();
    });

    it('T-S : HUD d’abord (200 ms), univers replié (350 ms), cœur réduit à un point (500 ms)', () => {
      expect(exitFrame(200).hud).toBe(0);
      expect(exitFrame(200).universe).toBeGreaterThan(0);
      expect(exitFrame(350).universe).toBe(0);
      expect(exitFrame(350).core).toBe(1);
      expect(exitFrame(500).core).toBe(0);
    });

    it('la première ouverture du jour joue T-E1, les suivantes T-E2 ; stockage bloqué ⇒ T-E2', () => {
      localStorage.removeItem(ENTRY_KEY);
      expect(entryKindToday('2026-11-02')).toBe('full');
      expect(entryKindToday('2026-11-02')).toBe('short');
      expect(entryKindToday('2026-11-03')).toBe('full');
      spyOn(localStorage, 'getItem').and.throwError('bloqué');
      expect(entryKindToday('2026-11-04')).toBe('short');
      localStorage.removeItem(ENTRY_KEY);
    });
  });

  describe('sélection en espace écran', () => {
    it('le nœud le plus proche à ≤ 28 px : cible ≥ 44 px à toute profondeur ; rien au-delà, rien d’invisible', () => {
      const pts = [
        { key: 'a', sx: 100, sy: 100, visible: true },
        { key: 'b', sx: 120, sy: 100, visible: true },
        { key: 'c', sx: 104, sy: 100, visible: false },
      ];
      expect(pickNearest(pts, 108, 100)?.key).toBe('a');
      expect(pickNearest(pts, 100 + PICK_RADIUS_PX, 140)).toBeNull();
      expect(pickNearest(pts, 100, 100 + 22)?.key).toBe('a');
      expect(2 * PICK_RADIUS_PX).toBeGreaterThanOrEqual(44);
    });
  });
});
