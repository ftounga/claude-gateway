import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AtelierFilesComponent } from './atelier-files.component';
import { AtelierService } from '../../core/services/atelier.service';
import { WorkspaceDetail } from '../../core/models/atelier.models';

/**
 * **L'écran Atelier / Fichiers est responsive** (F-159 / SF-159-02).
 *
 * <p>Il ADOPTE les fondations du Lot 0 (SF-159-01) : point de rupture unique 819 px via le mixin
 * partagé `bp.phone`. Sous 819 px, l'arbre (`tree-pane`, 320 px fixe) et l'aperçu s'EMPILENT, la
 * recherche passe en pleine largeur et les gestes de l'arbre deviennent tactiles (>= 44 px,
 * actions visibles sans survol) — jamais de défilement horizontal de PAGE.</p>
 *
 * <p>Garde-fou INDÉPENDANT DU VIEWPORT : les styles du composant sont injectés par Angular dans le
 * document ; on inspecte la CSSOM (`document.styleSheets`), pas `matchMedia`, de sorte que le test
 * tient quelle que soit la taille de la fenêtre Karma.</p>
 */
describe('AtelierFilesComponent — écran responsive (F-159 / SF-159-02)', () => {
  let fixture: ComponentFixture<AtelierFilesComponent>;

  const detail: WorkspaceDetail = {
    id: 'w1',
    name: 'projet',
    fileCount: 1,
    files: ['src/a.js'],
    createdAt: '2026-07-11T00:00:00Z',
    source: 'ARCHIVE',
    gitRepoUrl: null,
    gitRepo: null,
    gitBranch: null,
    truncated: false,
  };

  beforeEach(async () => {
    const service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'getWorkspace',
      'getFile',
      'gitBranches',
    ]);
    service.getWorkspace.and.returnValue(of(detail));
    service.getFile.and.returnValue(of({ path: 'src/a.js', content: 'const x = 1;' }));
    service.gitBranches.and.returnValue(
      of({ branches: ['main'], current: 'main', defaultBranch: 'main' }),
    );

    await TestBed.configureTestingModule({
      imports: [AtelierFilesComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AtelierService, useValue: service },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap({ id: 'w1' }),
              queryParamMap: convertToParamMap({}),
            },
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AtelierFilesComponent);
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

  it('sous 819 px, le corps s\'empile en une colonne (arbre au-dessus, aperçu dessous)', () => {
    const css = mobileMediaCss();
    expect(css).withContext('règle mobile .body absente').toContain('.body');
    expect(css).toMatch(/\.body[^}]*flex-direction:\s*column/);
  });

  it('sous 819 px, l\'arbre passe en pleine largeur (largeur fixe 320 px neutralisée)', () => {
    const css = mobileMediaCss();
    expect(css).toMatch(/\.tree-pane[^}]*width:\s*100%/);
  });

  it('sous 819 px, la recherche est en pleine largeur', () => {
    const css = mobileMediaCss();
    expect(css).toMatch(/\.search-field[^}]*width:\s*100%/);
  });

  it('sous 819 px, les lignes de l\'arbre restent tactiles (>= 44 px)', () => {
    const css = mobileMediaCss();
    expect(css).toMatch(/\.row[^}]*min-height:\s*44px/);
  });

  it('sous 819 px, les actions de ligne sont visibles sans survol', () => {
    const css = mobileMediaCss();
    expect(css).toMatch(/\.row-actions[^}]*display:\s*flex/);
  });

  it('NON-RÉGRESSION charte : aucune couleur littérale hors jeton dans le bloc responsive', () => {
    const css = mobileMediaCss();
    // Seul `var(--cg-divider)` est autorisé ; pas de #hex ni rgb() en dur.
    expect(css).not.toMatch(/#[0-9a-fA-F]{3,6}\b/);
    expect(css).not.toMatch(/\brgb\(/);
  });
});
