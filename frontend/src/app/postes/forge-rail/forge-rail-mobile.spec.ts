import { ComponentFixture, TestBed } from '@angular/core/testing';

import { RunnerHostOverview } from '../../core/models/atelier.models';
import { ForgeGroup, groupHosts } from '../forge-fleet';
import { ForgeRailComponent } from './forge-rail.component';

/**
 * **La Forge mobile pro : cartes pleine largeur, sans débordement, desktop intact** (F-160 / SF-160-02).
 *
 * <p>SF-160-02 restyle la colonne des postes en une pile de CARTES pleine largeur sous 819 px (forme
 * de la maquette validée PO), en palette 100 % charte. Trois garde-fous, sur le modèle imposé par le
 * cadrage :</p>
 *
 * <ul>
 *   <li><b>D4 — jamais de scroll horizontal</b> : mesure en DOM réel (ChromeHeadless), un contenu
 *       volontairement large ne fait pas défiler la page à 360/400 px ; un test de <i>sanity</i>
 *       prouve que sans le patron mobile, le même contenu FAIT déborder ;</li>
 *   <li><b>D3 — non-régression desktop (CSSOM)</b> : chaque règle nouvelle vit sous
 *       <code>max-width: 819px</code>, aucune ne fuit sur le desktop ;</li>
 *   <li><b>D3 — non-régression desktop (calculé)</b> : à la fenêtre Karma (1440 px, media inactive),
 *       la colonne garde son rendu d'avant F-160.</li>
 * </ul>
 *
 * <p>La fenêtre Karma mesure 1440×900 : les règles <code>@media (max-width: 819px)</code> n'y sont
 * pas actives. Pour la mesure de layout on ré-applique au document les règles mobile DE LA FEUILLE
 * forge-rail déjà injectées par Angular, sans la garde media — exactement le procédé de
 * <code>terminal-largeur-reelle.spec.ts</code> (SF-158-10).</p>
 */
describe('ForgeRailComponent — Forge mobile pro (F-160 / SF-160-02)', () => {
  let fixture: ComponentFixture<ForgeRailComponent>;
  let host: HTMLElement;
  let injected: HTMLStyleElement | null = null;

  const makeHost = (id: string, name: string, extra: Partial<RunnerHostOverview> = {}): RunnerHostOverview => ({
    id, name, connected: true, activeProjects: 0, createdAt: '',
    lastSeenAt: new Date(Date.now() - 12_000).toISOString(),
    projects: [
      { id: `${id}-w1`, name: 'audit-iam', calls: 0, active: false },
      { id: `${id}-w2`, name: 'rapport', calls: 0, active: false },
    ],
    ...extra,
  });

  function render(groups: ForgeGroup[]): void {
    TestBed.configureTestingModule({ imports: [ForgeRailComponent] });
    fixture = TestBed.createComponent(ForgeRailComponent);
    fixture.componentRef.setInput('groups', groups);
    fixture.componentRef.setInput('selectedRef', null);
    fixture.componentRef.setInput('filter', '');
    fixture.componentRef.setInput('closedOpen', false);
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
  }

  afterEach(() => {
    injected?.remove();
    injected = null;
    fixture?.destroy();
  });

  interface FlatRule { selectorText: string; cssText: string; mediaText: string; }

  /** Aplatit les CSSStyleRule des feuilles accessibles (media incluses). */
  function flatStyleRules(): FlatRule[] {
    const out: FlatRule[] = [];
    const walk = (rules: CSSRuleList, mediaText: string) => {
      for (const rule of Array.from(rules)) {
        if (rule instanceof CSSMediaRule) {
          walk(rule.cssRules, rule.media.mediaText);
        } else if (rule instanceof CSSStyleRule) {
          out.push({ selectorText: rule.selectorText, cssText: rule.cssText, mediaText });
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

  /**
   * Ré-applique au layout réel les règles `max-width: 819px` de la FEUILLE forge-rail (filtrées sur
   * `forge-rail`), sans la garde media — de sorte que le patron mobile agisse à 1440 px.
   */
  function applyMobileRules(): void {
    let css = '';
    for (const sheet of Array.from(document.styleSheets)) {
      let rules: CSSRuleList;
      try {
        rules = sheet.cssRules;
      } catch {
        continue;
      }
      for (const rule of Array.from(rules)) {
        if (rule instanceof CSSMediaRule && /max-width:\s*819px/.test(rule.media.mediaText)) {
          for (const inner of Array.from(rule.cssRules)) {
            if (inner.cssText.includes('forge-rail')) {
              css += inner.cssText + '\n';
            }
          }
        }
      }
    }
    injected = document.createElement('style');
    injected.textContent = css;
    document.head.appendChild(injected);
  }

  /**
   * Injecte une méta mono INSÉCABLE très large comme enfant direct de la première carte de poste
   * (une piste de grille implicite, sans containment de base), mesure si LA PAGE (l'hôte du composant)
   * déborde horizontalement à `width`, puis nettoie.
   */
  function pageOverflowsAt(width: number): boolean {
    host.style.width = `${width}px`;
    const card = host.querySelector('.forge-rail__host') as HTMLElement;
    expect(card).withContext('aucune carte de poste rendue').toBeTruthy();

    const probe = document.createElement('span');
    probe.className = 'sf16002-probe';
    probe.style.whiteSpace = 'nowrap';
    // ~900 caractères, aucun espace ni trait d'union : aucune opportunité de coupure naturelle.
    probe.textContent = 'CAGIPossatureTerraformInsecable'.repeat(30);
    card.appendChild(probe);

    void host.getBoundingClientRect(); // force un reflow
    const overflow = host.scrollWidth > host.clientWidth;
    probe.remove();
    return overflow;
  }

  // ------------------------------------------------------------------ D4 — jamais de scroll-x
  it('à 360 px, une carte au contenu très large ne fait PAS défiler la page', () => {
    render(groupHosts([makeHost('h1', 'CAGIP-eu-west-3-production-tres-longue-identite')], (h) => h.connected, ''));
    applyMobileRules();
    expect(pageOverflowsAt(360))
      .withContext('la carte devrait clore l\'axe horizontal — host.scrollWidth <= host.clientWidth')
      .toBe(false);
  });

  it('à 400 px, une carte au contenu très large ne fait PAS défiler la page', () => {
    render(groupHosts([makeHost('h1', 'CAGIP-eu-west-3-production-tres-longue-identite')], (h) => h.connected, ''));
    applyMobileRules();
    expect(pageOverflowsAt(400))
      .withContext('la carte devrait clore l\'axe horizontal — host.scrollWidth <= host.clientWidth')
      .toBe(false);
  });

  it('sanity : SANS le patron mobile, le même contenu large FAIT déborder (mesure réelle)', () => {
    render(groupHosts([makeHost('h1', 'CAGIP')], (h) => h.connected, ''));
    // Aucun applyMobileRules() : la carte de base n'a pas de containment → débordement réel.
    // Prouve que l'assertion mesure un vrai layout, et non une tautologie.
    expect(pageOverflowsAt(360)).toBe(true);
  });

  // ------------------------------------------------------------------ D3 — non-régression desktop
  it('CSSOM : la carte mobile (rayon 14px) ne vit QUE sous max-width: 819px', () => {
    render(groupHosts([makeHost('h1', 'CAGIP')], (h) => h.connected, ''));
    const cardRules = flatStyleRules().filter(
      (r) => r.selectorText.includes('forge-rail__host') && /border-radius:\s*14px/.test(r.cssText),
    );
    expect(cardRules.length)
      .withContext('la règle de carte mobile (rayon 14px) devrait exister')
      .toBeGreaterThan(0);
    for (const rule of cardRules) {
      expect(/max-width:\s*819px/.test(rule.mediaText))
        .withContext(`fuite desktop : "${rule.selectorText}" hors @media (media="${rule.mediaText}")`)
        .toBe(true);
    }
  });

  it('CSSOM : les règles F-160 forge-rail n\'introduisent aucune couleur hex nouvelle (jetons --cg-*)', () => {
    render(groupHosts([makeHost('h1', 'CAGIP')], (h) => h.connected, ''));
    const mobileForgeRules = flatStyleRules().filter(
      (r) => /max-width:\s*819px/.test(r.mediaText) && r.selectorText.includes('forge-rail'),
    );
    expect(mobileForgeRules.length).toBeGreaterThan(0);
    for (const rule of mobileForgeRules) {
      // Palette charte via jetons uniquement : aucun littéral hex, aucun rgb() en dur.
      expect(rule.cssText)
        .withContext(`couleur en dur dans "${rule.selectorText}"`)
        .not.toMatch(/#[0-9a-fA-F]{3,6}\b/);
      expect(rule.cssText)
        .withContext(`rgb() en dur dans "${rule.selectorText}"`)
        .not.toMatch(/\brgb\(/);
    }
  });

  it('calculé : à la fenêtre Karma (1440 px), la colonne garde son rendu desktop d\'avant F-160', () => {
    render(groupHosts([makeHost('h1', 'CAGIP')], (h) => h.connected, ''));
    // media 819 inactive à 1440 px : fond de colonne clair d'origine (--cg-surface = blanc),
    // et la carte n'a NI le filet NI le rayon 14px du mobile (structure desktop inchangée).
    const rail = host.querySelector('.forge-rail') as HTMLElement;
    const card = host.querySelector('.forge-rail__host') as HTMLElement;
    expect(getComputedStyle(rail).backgroundColor)
      .withContext('le fond de la colonne desktop doit rester blanc (--cg-surface)')
      .toBe('rgb(255, 255, 255)');
    expect(getComputedStyle(card).borderTopWidth)
      .withContext('la carte desktop n\'a pas de filet (border: 0 de base)')
      .toBe('0px');
    expect(getComputedStyle(card).borderTopLeftRadius)
      .withContext('la carte desktop garde son rayon de base (8px), pas 14px')
      .toBe('8px');
  });
});
