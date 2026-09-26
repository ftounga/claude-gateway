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
 * **Les bandeaux « texte + boutons » du terminal s'empilent sur téléphone** (F-158 / SF-158-18, P0).
 *
 * <p>À 390 px, la rangée d'un bandeau de compaction (« Cette conversation est longue — repartir
 * propre ? ») gardait texte et boutons côte à côte : le texte, en `min-width: 0`, s'écrasait à ~100 px
 * (un mot par ligne). Le correctif fait passer, sous 819 px, la rangée d'actions en pleine largeur
 * (`flex: 1 1 100%`) sous un conteneur `flex-wrap: wrap` → les boutons descendent, le texte récupère
 * toute la première ligne. Même complément sur la proposition de runner (déjà `flex-wrap` en
 * SF-158-11, mais dont les actions écrasaient encore le texte).</p>
 *
 * <p>Garde-fou INDÉPENDANT DU VIEWPORT (patron SF-158-09) : on inspecte la CSSOM
 * (`document.styleSheets`) et non `matchMedia`, de sorte que le test tient quelle que soit la taille de
 * la fenêtre Karma. On vérifie les intentions de la feuille mobile dédiée sous le point de rupture
 * 819 px. La descente pleine largeur des boutons garantit STRUCTURELLEMENT que le texte occupe la
 * première ligne complète (≥ ~250 px à 390 px), sans dépendre d'un rendu à 390 px.</p>
 */
describe('AtelierTerminalComponent — bandeaux empilés sur mobile (F-158 / SF-158-18)', () => {
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
    return css.replace(/\s+/g, ' ');
  }

  it('sous 819 px, le bandeau de compaction empile ses boutons sous le texte (pleine largeur)', () => {
    const css = mobileMediaCss();
    // Le conteneur autorise le passage à la ligne…
    expect(css).toMatch(/terminal-compaction-hint[^}]*flex-wrap:\s*wrap/);
    // …et la rangée d'actions prend toute la largeur → elle saute SOUS le texte.
    expect(css).toMatch(/terminal-compaction-hint-actions[^}]*flex:\s*1\s+1\s+100%/);
  });

  it('sous 819 px, les boutons du bandeau de compaction restent tactiles (>= 44 px)', () => {
    const css = mobileMediaCss();
    expect(css).toMatch(/terminal-compaction-hint-actions[^}]*button[^}]*min-height:\s*44px/);
  });

  it('sous 819 px, la proposition de runner empile aussi ses actions (pleine largeur, >= 44 px)', () => {
    const css = mobileMediaCss();
    // Complément de SF-158-11 : la rangée d'actions passe pleine largeur.
    expect(css).toMatch(/terminal-hint-runner-actions[^}]*flex:\s*1\s+1\s+100%/);
    // Ses boutons restent tactiles.
    expect(css).toMatch(/terminal-hint-runner-actions[^}]*min-height:\s*44px/);
  });
});
