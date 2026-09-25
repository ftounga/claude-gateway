import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';

import { TemplatesComponent } from './templates.component';
import { TemplatesService } from '../core/services/templates.service';

/**
 * **L'écran Modèles de prompts est responsive** (F-159 / SF-159-05).
 *
 * <p>Il ADOPTE les fondations du Lot 0 (SF-159-01) : point de rupture unique 819 px via le mixin
 * partagé `bp.phone`. Sous 819 px, les cartes se resserrent, l'en-tête s'empile et son bouton
 * « Nouveau modèle » passe pleine largeur et tactile (>= 44 px) ; les actions de ligne restent
 * tactiles — le tableau est enveloppé dans `.table-scroll` côté gabarit (jamais de défilement
 * horizontal de PAGE).</p>
 *
 * <p>Garde-fou INDÉPENDANT DU VIEWPORT : on inspecte la CSSOM (`document.styleSheets`), pas
 * `matchMedia`, de sorte que le test tient quelle que soit la taille de la fenêtre Karma.</p>
 */
describe('TemplatesComponent — écran responsive (F-159 / SF-159-05)', () => {
  let fixture: ComponentFixture<TemplatesComponent>;

  beforeEach(async () => {
    const service = jasmine.createSpyObj<TemplatesService>('TemplatesService', [
      'list',
      'get',
      'create',
      'update',
      'delete',
    ]);
    service.list.and.returnValue(of([]));
    const dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    const snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    await TestBed.configureTestingModule({
      imports: [TemplatesComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: TemplatesService, useValue: service },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TemplatesComponent);
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

  it('sous 819 px, l\'en-tête s\'empile en une colonne', () => {
    const css = mobileMediaCss();
    expect(css).withContext('règle mobile .templates__header absente').toContain('.templates__header');
    expect(css).toMatch(/\.templates__header[^}]*flex-direction:\s*column/);
  });

  it('sous 819 px, le bouton d\'en-tête est pleine largeur et tactile (>= 44 px)', () => {
    const css = mobileMediaCss();
    // Sélecteur descendant : `[^{}]*` traverse l'attribut d'encapsulation entre `.header` et `button`.
    expect(css).toMatch(/\.templates__header[^{}]*button[^}]*width:\s*100%/);
    expect(css).toMatch(/\.templates__header[^{}]*button[^}]*min-height:\s*44px/);
  });

  it('sous 819 px, les actions de ligne du tableau restent tactiles (>= 44 px)', () => {
    const css = mobileMediaCss();
    expect(css).toMatch(/\.templates__table[^{}]*button[^}]*min-height:\s*44px/);
  });

  it('NON-RÉGRESSION charte : aucune couleur littérale hors jeton dans le bloc responsive', () => {
    const css = mobileMediaCss();
    expect(css).not.toMatch(/#[0-9a-fA-F]{3,6}\b/);
    expect(css).not.toMatch(/\brgb\(/);
  });
});
