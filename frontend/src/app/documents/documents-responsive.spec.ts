import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';

import { DocumentsComponent } from './documents.component';
import { DocumentsService } from '../core/services/documents.service';

/**
 * **L'écran Documents est responsive** (F-159 / SF-159-05).
 *
 * <p>Il ADOPTE les fondations du Lot 0 (SF-159-01) : point de rupture unique 819 px via le mixin
 * partagé `bp.phone`. Sous 819 px, les cartes se resserrent, le lien « Poser une question… » passe
 * pleine largeur et tactile (>= 44 px) et les actions de ligne restent tactiles — le tableau est
 * enveloppé dans `.table-scroll` côté gabarit (jamais de défilement horizontal de PAGE).</p>
 *
 * <p>Garde-fou INDÉPENDANT DU VIEWPORT : les styles du composant sont injectés par Angular dans le
 * document ; on inspecte la CSSOM (`document.styleSheets`), pas `matchMedia`, de sorte que le test
 * tient quelle que soit la taille de la fenêtre Karma.</p>
 */
describe('DocumentsComponent — écran responsive (F-159 / SF-159-05)', () => {
  let fixture: ComponentFixture<DocumentsComponent>;

  beforeEach(async () => {
    const service = jasmine.createSpyObj<DocumentsService>('DocumentsService', [
      'submit',
      'list',
      'get',
      'delete',
    ]);
    service.list.and.returnValue(of([]));
    const dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    await TestBed.configureTestingModule({
      imports: [DocumentsComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: DocumentsService, useValue: service },
        { provide: MatDialog, useValue: dialog },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DocumentsComponent);
    fixture.detectChanges();
  });

  /** cssText concaténé de toutes les règles `@media` dont la condition vise <= 819 px. */
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

  it('sous 819 px, la page se resserre (padding jeton --cg-space-3)', () => {
    const css = mobileMediaCss();
    expect(css).withContext('règle mobile .documents absente').toContain('.documents');
    // Le resserrement s'exprime en jeton d'espacement, jamais en valeur littérale.
    expect(css).toContain('var(--cg-space-3)');
  });

  it('sous 819 px, le lien « Poser une question… » est pleine largeur et tactile (>= 44 px)', () => {
    const css = mobileMediaCss();
    // `[^}]*` absorbe l'attribut d'encapsulation `[_ngcontent-*]` inséré par Angular.
    expect(css).toMatch(/\.documents__ask-link[^}]*width:\s*100%/);
    expect(css).toMatch(/\.documents__ask-link[^}]*min-height:\s*44px/);
  });

  it('sous 819 px, les actions de ligne du tableau restent tactiles (>= 44 px)', () => {
    const css = mobileMediaCss();
    // Sélecteur descendant : `[^{}]*` traverse l'attribut d'encapsulation entre `.table` et `button`.
    expect(css).toMatch(/\.documents__table[^{}]*button[^}]*min-height:\s*44px/);
  });

  it('NON-RÉGRESSION charte : aucune couleur littérale hors jeton dans le bloc responsive', () => {
    const css = mobileMediaCss();
    expect(css).not.toMatch(/#[0-9a-fA-F]{3,6}\b/);
    expect(css).not.toMatch(/\brgb\(/);
  });
});
