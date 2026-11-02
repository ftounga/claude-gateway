import {
  BRAIN_CANCEL_MS, BRAIN_FOLD_MS, BRAIN_UNFOLD_MS, BrainFrame, BrainsClock, brainsFor, foldFrame, nearestPairs,
  unfoldFrame,
} from './jarvis-brains';

const KEYS: (keyof BrainFrame)[] = ['deploy', 'hue', 'rings', 'reveal', 'coreScale', 'coreLight', 'bloom', 'spread',
  'pearl', 'gather'];

/** Le plus grand pas d'une propriété entre deux images de 16 ms sur une séquence. */
function worstStep(at: (t: number) => BrainFrame, duration: number): Record<string, number> {
  const worst: Record<string, number> = {};
  let previous = { ...at(0) };
  for (let t = 16; t <= duration + 16; t += 16) {
    const f = { ...at(t) };
    for (const k of KEYS) {
      worst[k] = Math.max(worst[k] ?? 0, Math.abs((f[k] as number) - (previous[k] as number)));
    }
    previous = f;
  }
  return worst;
}

describe('F-236 / SF-236-03 — les deux cerveaux', () => {
  describe('machine d’état (§10.1)', () => {
    it('cerveau rapide actif : délégation n → n+1 ⇒ déploiement ; la dernière finie ⇒ repli', () => {
      const on = { fastBrainOn: true, turnActive: true, end: 'normal' as const };
      expect(brainsFor({ ...on, delegations: 0 }).target).toBe('folded');
      expect(brainsFor({ ...on, delegations: 1 })).toEqual({ target: 'deployed', variant: 'normal', pearls: 1 });
      expect(brainsFor({ ...on, delegations: 3 }).pearls).toBe(3);
      expect(brainsFor({ ...on, delegations: 0 }).target).toBe('folded');
    });

    it('coupe-circuit (fastBrainOn faux) ⇒ chaque tour est un tour Opus : déployé pendant turnActive', () => {
      expect(brainsFor({ fastBrainOn: false, turnActive: true, delegations: 0, end: 'normal' }))
        .toEqual({ target: 'deployed', variant: 'normal', pearls: 1 });
      expect(brainsFor({ fastBrainOn: false, turnActive: false, delegations: 0, end: 'normal' }).target)
        .toBe('folded');
    });

    it('la fin du travail donne la variante du repli (annulé, échec)', () => {
      expect(brainsFor({ fastBrainOn: true, turnActive: false, delegations: 0, end: 'cancel' }).variant).toBe('cancel');
      expect(brainsFor({ fastBrainOn: true, turnActive: false, delegations: 0, end: 'fail' }).variant).toBe('fail');
    });

    it('perles bornées à 6', () => {
      expect(brainsFor({ fastBrainOn: true, turnActive: true, delegations: 40, end: 'normal' }).pearls).toBe(6);
    });
  });

  describe('chorégraphie aller T-B1 (1,6 s) — phases aux temps prévus', () => {
    it('0-200 ms inspiration : le petit cœur se contracte (0,85), lumière −20 %, rien n’est déployé', () => {
      const f = unfoldFrame(200);
      expect(f.coreScale).toBeCloseTo(0.85, 2);
      expect(f.coreLight).toBeCloseTo(0.8, 2);
      expect(f.deploy).toBe(0);
    });

    it('200-450 ms étincelle : flash au centre, onde de choc violette lancée, bloom +0,5 (1,1 → 1,6)', () => {
      const f = unfoldFrame(325);
      expect(f.flash).toBeCloseTo(1, 2);
      expect(f.shock).toBeGreaterThan(0);
      expect(f.bloom).toBeCloseTo(0.5, 2);
      expect(f.pearl).toBe(1);
    });

    it('450-1 300 ms éclosion : déploiement, teinte vers le violet, anneaux 3 → 5, connexions révélées', () => {
      const mid = unfoldFrame(875);
      expect(mid.deploy).toBeGreaterThan(0.5);
      expect(mid.hue).toBeGreaterThan(0.3);
      expect(unfoldFrame(1300).deploy).toBeCloseTo(1, 5);
      expect(unfoldFrame(1300).rings).toBeCloseTo(1, 5);
    });

    it('1 300-1 600 ms stabilisation : rebond 1,03 → 1, bloom +0,5 → +0,2 (1,6 → 1,3), puis régime déployé', () => {
      expect(unfoldFrame(1300).coreScale).toBeCloseTo(1.03, 2);
      expect(unfoldFrame(BRAIN_UNFOLD_MS).coreScale).toBe(1);
      expect(unfoldFrame(BRAIN_UNFOLD_MS).bloom).toBeCloseTo(0.2, 5);
      expect(unfoldFrame(5000).deploy).toBe(1);
    });
  });

  describe('chorégraphie retour T-B3 (1,2 s)', () => {
    it('rassemblement puis repli puis dépôt (flash doux), tout replié à 1,2 s', () => {
      expect(foldFrame(150, 'normal', 1).gather).toBeGreaterThan(0.3);
      expect(foldFrame(300, 'normal', 1).deploy).toBeCloseTo(1, 5);
      expect(foldFrame(900, 'normal', 1).deploy).toBeCloseTo(0, 5);
      expect(foldFrame(1000, 'normal', 1).flash).toBeGreaterThan(0.5);
      expect(foldFrame(BRAIN_FOLD_MS, 'normal', 1)).toEqual(jasmine.objectContaining({ deploy: 0, pearl: 0 }));
    });

    it('annulé : repli en 0,8 s, sans flash ; échec : sans flash non plus', () => {
      for (let t = 0; t <= BRAIN_CANCEL_MS; t += 16) {
        expect(foldFrame(t, 'cancel', 1).flash).toBe(0);
        expect(foldFrame(t, 'fail', 1).flash).toBe(0);
      }
      expect(foldFrame(BRAIN_CANCEL_MS, 'cancel', 1).deploy).toBe(0);
    });
  });

  describe('aucun saut (règle 1) et interruptions (règle 2)', () => {
    it('aller : aucune propriété ne varie de plus de son pas maximal entre deux images', () => {
      const worst = worstStep((t) => unfoldFrame(t), BRAIN_UNFOLD_MS);
      expect(worst['deploy']).toBeLessThan(0.15);
      expect(worst['coreScale']).toBeLessThan(0.05);
      expect(worst['bloom']).toBeLessThan(0.12);
      expect(worst['hue']).toBeLessThan(0.1);
    });

    it('retour : idem', () => {
      const worst = worstStep((t) => foldFrame(t, 'normal', 1), BRAIN_FOLD_MS);
      expect(worst['deploy']).toBeLessThan(0.1);
      expect(worst['bloom']).toBeLessThan(0.12);
    });

    it('un aller interrompu par un repli repart du déploiement atteint (aucun retour au début)', () => {
      const clock = new BrainsClock();
      clock.set('deployed', 'normal', 0);
      const reached = clock.at(800).deploy;
      clock.set('folded', 'normal', 800);
      expect(clock.at(800).deploy).toBeCloseTo(reached, 5);
      expect(clock.at(800 + BRAIN_FOLD_MS).deploy).toBe(0);
    });

    it('un repli interrompu par une nouvelle délégation repart de là où il en est', () => {
      const clock = new BrainsClock('deployed');
      clock.set('folded', 'normal', 0);
      const left = clock.at(600).deploy;
      clock.set('deployed', 'normal', 600);
      expect(clock.at(600).deploy).toBeCloseTo(left, 2);
      expect(clock.at(600 + BRAIN_UNFOLD_MS).deploy).toBe(1);
    });

    it('replié au repos : rien (pas de flash fantôme à l’ouverture)', () => {
      const clock = new BrainsClock();
      expect(clock.at(500)).toEqual(jasmine.objectContaining({ deploy: 0, flash: 0, gather: 0, pearl: 0 }));
    });
  });

  describe('connexions (plus proches voisins, grille spatiale)', () => {
    it('déterministes, sans doublon, bornées au budget, voisins réellement proches', () => {
      const n = 400;
      const pts = new Float32Array(n * 3);
      let seed = 7;
      const rnd = (): number => {
        seed = (seed * 16807) % 2147483647;
        return seed / 2147483647;
      };
      for (let i = 0; i < n * 3; i++) {
        pts[i] = rnd() * 6 - 3;
      }
      const a = nearestPairs(pts, n, 300, 2);
      const b = nearestPairs(pts, n, 300, 2);
      expect(Array.from(a)).toEqual(Array.from(b));
      expect(a.length / 2).toBeLessThanOrEqual(300);
      const keys = new Set<string>();
      for (let e = 0; e < a.length / 2; e++) {
        const i = a[e * 2];
        const j = a[e * 2 + 1];
        keys.add(`${Math.min(i, j)}-${Math.max(i, j)}`);
        const d = Math.hypot(pts[i * 3] - pts[j * 3], pts[i * 3 + 1] - pts[j * 3 + 1], pts[i * 3 + 2] - pts[j * 3 + 2]);
        expect(d).toBeLessThan(2.5);
      }
      expect(keys.size).toBe(a.length / 2);
    });
  });
});
