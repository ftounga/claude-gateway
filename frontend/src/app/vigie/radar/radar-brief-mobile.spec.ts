import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';

import { RadarBrief } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { RadarBriefComponent } from './radar-brief.component';

/**
 * **Le résumé du matin mobile pro : carte digest à filet orange, sans débordement, desktop intact**
 * (F-160 / SF-160-03).
 *
 * <p>SF-160-03 donne au résumé du matin la silhouette « digest » de la maquette validée PO (carte à
 * FILET GAUCHE orange en tête du Radar), en palette 100 % charte claire. Trois garde-fous, sur le
 * modèle imposé par le cadrage (`terminal-largeur-reelle.spec.ts`, `fondations-responsive.spec.ts`) :</p>
 *
 * <ul>
 *   <li><b>D4 — jamais de scroll horizontal</b> : mesure en DOM réel (ChromeHeadless) ; un libellé
 *       volontairement insécable ne fait pas défiler la page à 360/400 px ; un test de <i>sanity</i>
 *       prouve que SANS le patron mobile, le même contenu FAIT déborder ;</li>
 *   <li><b>D3 — non-régression desktop (CSSOM)</b> : chaque règle nouvelle vit sous
 *       <code>max-width: 819px</code>, aucune ne fuit sur le desktop, aucune couleur en dur ;</li>
 *   <li><b>D3 — non-régression desktop (calculé)</b> : à la fenêtre Karma (media inactive), la carte
 *       garde son rendu d'avant F-160 (pas de filet gauche orange).</li>
 * </ul>
 *
 * <p>La fenêtre Karma n'active pas <code>max-width: 819px</code> : pour la mesure de layout on ré-applique
 * au document les règles mobile DE LA FEUILLE radar-brief déjà injectées par Angular, sans la garde
 * media — exactement le procédé de <code>terminal-largeur-reelle.spec.ts</code>.</p>
 */
describe('RadarBriefComponent — résumé du matin mobile pro (F-160 / SF-160-03)', () => {
  let fixture: ComponentFixture<RadarBriefComponent>;
  let host: HTMLElement;
  let injected: HTMLStyleElement | null = null;

  const brief = (): RadarBrief => ({
    generatedAt: '2026-09-15T06:00:00Z',
    since: '2026-09-14T06:00:00Z',
    sentences: [
      { kind: 'OVERDUE', text: 'Vous deviez répondre à Daoud sur l\'ossature Agenor.', subjectId: 'subj1', commitmentId: 'c1' },
    ],
    counts: { toDoByMe: 2, followUpsDue: 1, introductions: 0, subjectsFollowed: 4, blockedSubjects: 0, toHandle: 2 },
    running: null,
    lastSync: null,
    coverageComplete: true,
    coverageWarning: null,
    coverageLines: [],
  });

  function render(): void {
    const radar = jasmine.createSpyObj<RadarService>('RadarService',
      ['brief', 'syncNow', 'cancelSync', 'threadRules', 'addThreadRule', 'removeThreadRule']);
    radar.brief.and.returnValue(of(brief()));
    const snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    TestBed.configureTestingModule({
      imports: [RadarBriefComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: RadarService, useValue: radar },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(RadarBriefComponent);
    fixture.componentRef.setInput('hostId', 'h1');
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
   * Ré-applique au layout réel les règles `max-width: 819px` de la FEUILLE radar-brief (filtrées sur
   * `radar-brief`), sans la garde media — de sorte que le patron mobile agisse à la largeur Karma.
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
            if (inner.cssText.includes('radar-brief')) {
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
   * Injecte dans la colonne « ce qui a bougé » (`.radar-brief__main`, sans containment de base) une
   * sonde mono INSÉCABLE très large, mesure si LA PAGE (l'hôte du composant) déborde à `width`,
   * puis nettoie.
   */
  function pageOverflowsAt(width: number): boolean {
    host.style.width = `${width}px`;
    const main = host.querySelector('.radar-brief__main') as HTMLElement;
    expect(main).withContext('la colonne principale du résumé n\'est pas rendue').toBeTruthy();

    const probe = document.createElement('span');
    probe.className = 'sf16003-probe';
    probe.style.whiteSpace = 'nowrap';
    // ~900 caractères, aucun espace ni trait d'union : aucune opportunité de coupure naturelle.
    probe.textContent = 'AgenorOssatureTerraformInsecable'.repeat(30);
    main.appendChild(probe);

    void host.getBoundingClientRect(); // force un reflow
    const overflow = host.scrollWidth > host.clientWidth;
    probe.remove();
    return overflow;
  }

  // ------------------------------------------------------------------ D4 — jamais de scroll-x
  it('à 360 px, un libellé très large ne fait PAS défiler la page', () => {
    render();
    applyMobileRules();
    expect(pageOverflowsAt(360))
      .withContext('la carte digest devrait clore l\'axe horizontal — host.scrollWidth <= host.clientWidth')
      .toBe(false);
  });

  it('à 400 px, un libellé très large ne fait PAS défiler la page', () => {
    render();
    applyMobileRules();
    expect(pageOverflowsAt(400))
      .withContext('la carte digest devrait clore l\'axe horizontal — host.scrollWidth <= host.clientWidth')
      .toBe(false);
  });

  it('sanity : SANS le patron mobile, le même contenu large FAIT déborder (mesure réelle)', () => {
    render();
    // Aucun applyMobileRules() : `.radar-brief__main` n'a pas de containment → débordement réel.
    // Prouve que l'assertion mesure un vrai layout, et non une tautologie.
    expect(pageOverflowsAt(360)).toBe(true);
  });

  // ------------------------------------------------------------------ D3 — non-régression desktop
  it('CSSOM : le filet gauche orange du digest ne vit QUE sous max-width: 819px', () => {
    render();
    const digestRules = flatStyleRules().filter(
      (r) => r.selectorText.includes('radar-brief') && /border-left:\s*3px\s+solid/.test(r.cssText),
    );
    expect(digestRules.length)
      .withContext('la règle de filet gauche du digest mobile devrait exister')
      .toBeGreaterThan(0);
    for (const rule of digestRules) {
      expect(/max-width:\s*819px/.test(rule.mediaText))
        .withContext(`fuite desktop : "${rule.selectorText}" hors @media (media="${rule.mediaText}")`)
        .toBe(true);
    }
  });

  it('CSSOM : les règles F-160 radar-brief n\'introduisent aucune couleur hex/rgb nouvelle (jetons --cg-*)', () => {
    render();
    const mobileRules = flatStyleRules().filter(
      (r) => /max-width:\s*819px/.test(r.mediaText) && r.selectorText.includes('radar-brief'),
    );
    expect(mobileRules.length).toBeGreaterThan(0);
    for (const rule of mobileRules) {
      expect(rule.cssText)
        .withContext(`couleur en dur dans "${rule.selectorText}"`)
        .not.toMatch(/#[0-9a-fA-F]{3,6}\b/);
      expect(rule.cssText)
        .withContext(`rgb() en dur dans "${rule.selectorText}"`)
        .not.toMatch(/\brgb\(/);
    }
  });

  it('calculé : à la fenêtre Karma (media inactive), la carte garde son rendu desktop d\'avant F-160', () => {
    render();
    // media 819 inactive : la carte n'a PAS le filet gauche orange 3px du mobile (rendu desktop intact).
    const card = host.querySelector('.radar-brief') as HTMLElement;
    expect(getComputedStyle(card).borderLeftWidth)
      .withContext('le filet gauche orange 3px ne doit pas fuir sur le desktop')
      .not.toBe('3px');
  });
});
