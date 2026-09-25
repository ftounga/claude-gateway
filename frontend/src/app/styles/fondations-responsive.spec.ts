/**
 * **Fondations responsive globales** (F-159 / SF-159-01).
 *
 * <p>Cette subfeature pose les fondations SCSS transverses de l'app : `box-sizing` global,
 * point de rupture unique 819 px, classe de page `.page`, utilitaire `.table-scroll`, et un
 * garde-fou global des dialogs sous 819 px. Elle n'a pas de composant : on vérifie les règles
 * dans la CSSOM (les styles globaux `src/styles.scss` sont chargés par le builder de test) et,
 * pour `box-sizing`, on sonde le style calculé d'un élément réel — le garde-fou tient alors quelle
 * que soit la taille de la fenêtre Karma.</p>
 */
describe('Fondations responsive globales (F-159 / SF-159-01)', () => {
  interface FlatRule {
    selectorText: string;
    cssText: string;
    style: CSSStyleDeclaration;
    mediaText: string; // '' si hors @media
  }

  /** Aplatit toutes les CSSStyleRule des feuilles accessibles (media incluses). */
  function flatStyleRules(): FlatRule[] {
    const out: FlatRule[] = [];
    const walk = (rules: CSSRuleList, mediaText: string) => {
      for (const rule of Array.from(rules)) {
        if (rule instanceof CSSMediaRule) {
          walk(rule.cssRules, rule.media.mediaText);
        } else if (rule instanceof CSSStyleRule) {
          out.push({
            selectorText: rule.selectorText,
            cssText: rule.cssText,
            style: rule.style,
            mediaText,
          });
        }
      }
    };
    for (const sheet of Array.from(document.styleSheets)) {
      try {
        walk(sheet.cssRules, '');
      } catch {
        continue; // feuille cross-origin : ignorée
      }
    }
    return out;
  }

  it('applique box-sizing: border-box globalement (sonde de style calculé)', () => {
    const probe = document.createElement('div');
    document.body.appendChild(probe);
    try {
      expect(getComputedStyle(probe).boxSizing).toBe('border-box');
    } finally {
      probe.remove();
    }
  });

  it('.page porte margin-inline auto et un padding-inline en clamp()', () => {
    const rules = flatStyleRules().filter((r) => r.selectorText === '.page' && r.mediaText === '');
    expect(rules.length).toBeGreaterThan(0);
    const css = rules.map((r) => r.cssText).join('\n');
    expect(css).toContain('margin-inline: auto');
    expect(css).toContain('clamp(');
    // Gouttière latérale >= 16 px à toute largeur (plancher du clamp).
    expect(css).toContain('16px');
  });

  it('.table-scroll autorise le défilement horizontal (overflow-x: auto)', () => {
    const rules = flatStyleRules().filter((r) => r.selectorText === '.table-scroll');
    expect(rules.length).toBeGreaterThan(0);
    const overflowX = rules.map((r) => r.style.overflowX).find((v) => v);
    expect(overflowX).toBe('auto');
  });

  it('sous 819 px, borne le panneau de dialog à <= 96vw', () => {
    const phoneDialog = flatStyleRules().filter(
      (r) => /max-width:\s*819px/.test(r.mediaText) && /dialog-panel|cdk-overlay-pane/.test(r.selectorText),
    );
    expect(phoneDialog.length).toBeGreaterThan(0);
    const css = phoneDialog.map((r) => r.cssText).join('\n');
    expect(css).toContain('96vw');
  });

  it('les règles fondatrices n\'introduisent pas de couleur littérale hors jeton', () => {
    // box-sizing/.page/.table-scroll/dialog : aucune couleur ne doit apparaître (charte via jetons --cg-*).
    const foundational = flatStyleRules().filter((r) =>
      ['.page', '.table-scroll'].includes(r.selectorText),
    );
    for (const r of foundational) {
      expect(r.cssText).not.toMatch(/#[0-9a-fA-F]{3,6}\b/);
      expect(r.cssText).not.toMatch(/\brgb\(/);
    }
  });
});
