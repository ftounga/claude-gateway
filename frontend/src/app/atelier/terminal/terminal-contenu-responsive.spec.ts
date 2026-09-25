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
 * **Le CONTENU du terminal est responsive** (F-158 / SF-158-09).
 *
 * <p>SF-151-03 a rendu la BARRE du terminal utilisable au doigt sous 819 px ; cette subfeature
 * traite le CONTENU (fil, ligne vivante, bandeau plafond, zone de saisie) pour qu'un téléphone
 * (~400 px) ne déclenche jamais de défilement horizontal de PAGE et reste tactile.</p>
 *
 * <p>Garde-fou INDÉPENDANT DU VIEWPORT : les styles du composant sont injectés par Angular dans le
 * document ; on inspecte la CSSOM (`document.styleSheets`) et non `matchMedia`, de sorte que le test
 * tient quelle que soit la taille de la fenêtre Karma. On vérifie les intentions de la feuille mobile
 * dédiée (`atelier-terminal-mobile.component.scss`) sous le point de rupture 819 px, et la
 * NON-RÉGRESSION des cadres de défilement des blocs larges (diff / `pre` / `table`).</p>
 */
describe('AtelierTerminalComponent — contenu responsive (F-158 / SF-158-09)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;

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
  });

  /** Le cssText concaténé de toutes les règles `@media` dont la condition vise <= 819 px. */
  function mobileMediaCss(): string {
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
          css += rule.cssText + '\n';
        }
      }
    }
    return css;
  }

  /** Le cssText concaténé de TOUTES les règles (blocs larges scrollables hors media compris). */
  function allCss(): string {
    let css = '';
    for (const sheet of Array.from(document.styleSheets)) {
      let rules: CSSRuleList;
      try {
        rules = sheet.cssRules;
      } catch {
        continue;
      }
      for (const rule of Array.from(rules)) {
        css += rule.cssText + '\n';
      }
    }
    return css;
  }

  it('sous 819 px, le fil clôt l\'axe horizontal — la PAGE ne défile jamais (D4)', () => {
    const css = mobileMediaCss();
    // Une règle mobile existe bien pour le fil du terminal.
    expect(css).withContext('règle mobile absente').toContain('terminal-scrollback');
    // Elle clôt l'axe horizontal.
    expect(css.replace(/\s+/g, ' ')).toMatch(/terminal-scrollback[^}]*overflow-x:\s*hidden/);
  });

  it('sous 819 px, la ligne vivante et le bandeau plafond passent à la ligne', () => {
    const css = mobileMediaCss().replace(/\s+/g, ' ');
    expect(css).toMatch(/terminal-live[^}]*flex-wrap:\s*wrap/);
    expect(css).toMatch(/terminal-live-limit[^}]*flex-wrap:\s*wrap/);
  });

  it('sous 819 px, le rachat de tokens reste tactile (>= 44 px)', () => {
    const css = mobileMediaCss().replace(/\s+/g, ' ');
    expect(css).toMatch(/terminal-live-limit-retry[^}]*min-height:\s*44px/);
  });

  it('sous 819 px, le champ de saisie peut rétrécir (min-width: 0)', () => {
    const css = mobileMediaCss().replace(/\s+/g, ' ');
    expect(css).toMatch(/terminal-field[^}]*min-width:\s*0/);
  });

  it('NON-RÉGRESSION : les blocs larges gardent leur cadre défilant isolé', () => {
    const css = allCss().replace(/\s+/g, ' ');
    // Diff (SF-30-13), bloc de code et tableau Markdown (SF-30-14) : chacun défile CHEZ LUI.
    expect(css).toMatch(/terminal-diff-body[^}]*overflow-x:\s*auto/);
  });

  it('sous 819 px, la vue clôt l\'axe horizontal — containment (SF-158-10)', () => {
    const css = mobileMediaCss().replace(/\s+/g, ' ');
    // `:host, .terminal-view { max-width:100%; overflow-x:hidden }` : plus rien ne remonte à `.app-content`.
    expect(css).toMatch(/terminal-view[^}]*overflow-x:\s*hidden/);
    // Backstop `.terminal-view > * { min-width:0 }` : les enfants flex peuvent rétrécir.
    // (Angular réécrit `> *` en `> [_ngcontent…]` et sérialise `0px`.)
    expect(css).toMatch(/terminal-view[^}]*>[^}]*min-width:\s*0/);
  });

  it('sous 819 px, les rangées de contrôle sont pleine largeur / enroulables (SF-158-11)', () => {
    const css = mobileMediaCss().replace(/\s+/g, ' ');
    // Toggle-groups target/mode pleine largeur, options à parts égales.
    expect(css).toMatch(/terminal-target-toggle[^}]*width:\s*100%/);
    expect(css).toMatch(/terminal-mode-toggle[^}]*width:\s*100%/);
    expect(css).toMatch(/terminal-target-toggle[^}]*mat-button-toggle[^}]*flex:\s*1/);
    // État du poste : la commande longue enroule.
    expect(css).toMatch(/terminal-host-state[^}]*code[^}]*word-break:\s*break-all/);
    // Ligne de commande : item flex qui peut rétrécir.
    expect(css).toMatch(/terminal-command[^}]*code[^}]*min-width:\s*0/);
  });
});
