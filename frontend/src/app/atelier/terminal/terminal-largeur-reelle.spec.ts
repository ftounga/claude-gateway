import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { MailService } from '../../core/services/mail.service';
import { PagesService } from '../../core/services/pages.service';

/**
 * **Le terminal ne déborde JAMAIS à droite sur téléphone** (F-158 / SF-158-10, correctif P0).
 *
 * <p>C'est LE garde-fou qui manquait. Les specs CSSOM (`terminal-contenu-responsive.spec.ts`)
 * n'inspectaient que la présence d'une règle — jamais le layout réel — de sorte que les fixes
 * « passaient » sans corriger. Ici on MESURE, dans un vrai DOM ChromeHeadless :
 * `host.scrollWidth <= host.clientWidth` (pas de débordement horizontal) avec un contenu
 * volontairement très large (ligne mono insécable), dans un hôte de 360 px puis 400 px.</p>
 *
 * <p>La fenêtre Karma mesure 1440×900 (F-98) : les règles `@media (max-width: 819px)` de la feuille
 * mobile n'y sont pas actives. On les applique donc au layout réel — extraction des règles déjà
 * injectées par Angular dans `document.styleSheets`, ré-application sans la garde media — puis on
 * mesure. C'est une mesure de LAYOUT, pas de CSSOM. On ne touche pas la config Karma globale.</p>
 */
describe('AtelierTerminalComponent — largeur réelle (F-158 / SF-158-10)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let host: HTMLElement;
  let injected: HTMLStyleElement | null = null;

  beforeEach(async () => {
    const mail = jasmine.createSpyObj<MailService>('MailService', ['email']);
    mail.email.and.returnValue(of({}) as never);
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(of({}) as never);

    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: MailService, useValue: mail },
        { provide: PagesService, useValue: pages },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AtelierTerminalComponent);
    fixture.componentInstance.projectName = 'mon-projet';
    fixture.detectChanges();

    host = fixture.nativeElement as HTMLElement;
    // Ancré au document pour un layout réel, quelle que soit la racine de test.
    document.body.appendChild(host);
  });

  afterEach(() => {
    injected?.remove();
    injected = null;
    fixture.destroy();
  });

  /**
   * Applique au layout réel les règles `@media (max-width: 819px)` DU TERMINAL déjà injectées par
   * Angular (émulation) dans le document, sans la garde media — de sorte que le patron mobile agisse
   * réellement à un viewport Karma de 1440 px. Filtrées sur `terminal` pour n'affecter que le
   * composant (jamais les feuilles globales ou Material).
   */
  function applyTerminalMobileRules(): void {
    let css = '';
    for (const sheet of Array.from(document.styleSheets)) {
      let rules: CSSRuleList;
      try {
        rules = sheet.cssRules;
      } catch {
        continue; // feuille cross-origin : ignorée
      }
      for (const rule of Array.from(rules)) {
        if (rule instanceof CSSMediaRule && /max-width:\s*819px/.test(rule.media.mediaText)) {
          for (const inner of Array.from(rule.cssRules)) {
            if (inner.cssText.includes('terminal')) {
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
   * Injecte une ligne mono INSÉCABLE très large comme SŒUR du fil (à l'image d'une rangée de
   * contrôle : target/mode/host-state), mesure, puis nettoie. Rend vrai si la PAGE (l'hôte) déborde
   * horizontalement à la largeur donnée.
   */
  function pageOverflowsAt(width: number): boolean {
    host.style.width = `${width}px`;
    const view = host.querySelector('.terminal-view') as HTMLElement;
    expect(view).withContext('.terminal-view absente').toBeTruthy();

    const probe = document.createElement('div');
    probe.className = 'sf15810-probe';
    probe.style.whiteSpace = 'nowrap';
    // ~1080 caractères, aucun espace ni trait d'union : aucune opportunité de coupure.
    probe.textContent = 'jarclaudeRUNNERinsecable'.repeat(45);
    view.appendChild(probe);

    void host.getBoundingClientRect(); // force un reflow
    const overflow = host.scrollWidth > host.clientWidth;
    probe.remove();
    return overflow;
  }

  it('à 360 px, une ligne mono très large ne fait PAS défiler la page (containment de la vue)', () => {
    applyTerminalMobileRules();
    expect(pageOverflowsAt(360))
      .withContext('la vue devrait clore l\'axe horizontal — host.scrollWidth <= host.clientWidth')
      .toBe(false);
  });

  it('à 400 px, une ligne mono très large ne fait PAS défiler la page', () => {
    applyTerminalMobileRules();
    expect(pageOverflowsAt(400))
      .withContext('la vue devrait clore l\'axe horizontal — host.scrollWidth <= host.clientWidth')
      .toBe(false);
  });

  it('sanity : SANS le patron mobile, la même ligne mono FAIT déborder (le test mesure bien le layout)', () => {
    // Aucun `applyTerminalMobileRules()` : les règles de base n'ont aucun containment → débordement réel.
    // Prouve que l'assertion mesure un vrai layout (et non une tautologie).
    expect(pageOverflowsAt(360)).toBe(true);
  });
});
