import { RevenueSummary } from '../core/services/poste-billing.service';
import { buildShowcase, derivedDays, monthLabel } from './client-showcase';

/** La Vitrine des clients (F-124 / SF-124-05) — les règles de présentation, sans DOM. */
describe('client-showcase', () => {
  const summary = (postes: RevenueSummary['postes'], extra: Partial<RevenueSummary> = {}): RevenueSummary => ({
    startMonth: '2025-09',
    currentMonth: '2026-01',
    totalCents: postes.reduce((sum, p) => sum + p.cumulCents, 0),
    totalDeclaredCents: postes.reduce((sum, p) => sum + p.declaredCents, 0),
    totalSupposedCents: postes.reduce((sum, p) => sum + p.supposedCents, 0),
    postes,
    ...extra,
  });

  describe('derivedDays — jours retrouvés depuis cumul / TJM (inverse du calcul serveur)', () => {
    it('retrouve les jours entiers', () => {
      expect(derivedDays(1_430_000, 65_000)).toBe(22);
    });

    it('retrouve les demi-journées', () => {
      expect(derivedDays(1_258_000, 68_000)).toBe(18.5);
    });

    it('rend null sans TJM (garde anti-division, jamais de NaN)', () => {
      expect(derivedDays(1_000_000, 0)).toBeNull();
    });
  });

  describe('monthLabel — le mois de départ en toutes lettres', () => {
    it('écrit « septembre 2025 »', () => {
      expect(monthLabel('2025-09')).toBe('septembre 2025');
    });

    it('rend l\'entrée telle quelle sur une valeur illisible (pas de « Invalid Date »)', () => {
      expect(monthLabel('pas-un-mois')).toBe('pas-un-mois');
      expect(monthLabel(null)).toBe('');
    });
  });

  describe('buildShowcase — le modèle du bandeau et des cartes', () => {
    const names: Record<string, string> = { h1: 'Free', h2: 'KG', h3: 'CAGIP' };
    const nameOf = (id: string) => names[id];

    const base = summary([
      { hostId: 'h1', tjmCents: 65_000, cumulCents: 1_430_000, declaredCents: 1_430_000, supposedCents: 0 },
      { hostId: 'h3', tjmCents: 68_000, cumulCents: 1_224_000, declaredCents: 748_000, supposedCents: 476_000 },
      { hostId: 'h2', tjmCents: 72_000, cumulCents: 936_000, declaredCents: 936_000, supposedCents: 0 },
    ]);

    it('rend une carte par poste, du revenu le plus fort au plus faible', () => {
      const showcase = buildShowcase(base, nameOf);
      expect(showcase.cards.map((c) => c.name)).toEqual(['Free', 'CAGIP', 'KG']);
      expect(showcase.clientCount).toBe(3);
    });

    it('distingue déclaré et partiellement estimé', () => {
      const showcase = buildShowcase(base, nameOf);
      const cagip = showcase.cards.find((c) => c.name === 'CAGIP')!;
      const free = showcase.cards.find((c) => c.name === 'Free')!;
      expect(cagip.estimated).toBe(true);
      expect(cagip.supposedDays).toBe(7);
      expect(free.estimated).toBe(false);
      expect(free.days).toBe(22);
    });

    it('totalise le revenu et les jours cumulés', () => {
      const showcase = buildShowcase(base, nameOf);
      expect(showcase.totalCents).toBe(1_430_000 + 1_224_000 + 936_000);
      expect(showcase.totalDays).toBe(22 + 18 + 13);
      expect(showcase.startMonthLabel).toBe('septembre 2025');
    });

    it('ignore un poste introuvable dans l\'overview (on ne nomme jamais un client inconnu)', () => {
      const withGhost = summary([
        { hostId: 'h1', tjmCents: 65_000, cumulCents: 1_430_000, declaredCents: 1_430_000, supposedCents: 0 },
        { hostId: 'ghost', tjmCents: 50_000, cumulCents: 500_000, declaredCents: 500_000, supposedCents: 0 },
      ]);
      const showcase = buildShowcase(withGhost, nameOf);
      expect(showcase.cards.map((c) => c.name)).toEqual(['Free']);
    });

    it('rend une Vitrine vide quand aucun poste n\'a de TJM', () => {
      const showcase = buildShowcase(summary([]), nameOf);
      expect(showcase.cards).toEqual([]);
      expect(showcase.clientCount).toBe(0);
    });
  });

  // ------------------------------------------------------------------ contraste AA (charte)
  describe('contraste AA des ajouts charte (SF-124-05)', () => {
    // Jetons ajoutés/employés par la Vitrine, valeurs de styles.scss.
    const NAVY = '#1A3A5C';    // --cg-primary
    const GOLD_2 = '#D9AE5C';  // --cg-accent-2 : or sur navy (bandeau)
    const GOLD_INK = '#8A5200'; // --cg-gold-ink : or-encre sur blanc (cartes)
    const WHITE = '#FFFFFF';

    function channel(value: number): number {
      const c = value / 255;
      return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    function luminance(hex: string): number {
      const r = parseInt(hex.slice(1, 3), 16);
      const g = parseInt(hex.slice(3, 5), 16);
      const b = parseInt(hex.slice(5, 7), 16);
      return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
    }

    function contrast(a: string, b: string): number {
      const la = luminance(a);
      const lb = luminance(b);
      return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    it('vérifie sa propre mesure sur les extrêmes connus', () => {
      expect(contrast('#000000', WHITE)).toBeCloseTo(21, 0);
      expect(contrast(WHITE, WHITE)).toBeCloseTo(1, 5);
    });

    it('or sur navy (total du bandeau, grand texte) tient l\'AA large (≥ 3:1)', () => {
      expect(contrast(GOLD_2, NAVY)).toBeGreaterThanOrEqual(3);
    });

    it('or-encre sur blanc (symbole monétaire des cartes) tient l\'AA texte (≥ 4,5:1)', () => {
      expect(contrast(GOLD_INK, WHITE)).toBeGreaterThanOrEqual(4.5);
    });
  });
});
