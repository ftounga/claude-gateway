import { Route } from '@angular/router';

import { routes } from './app.routes';
import { authGuard } from './core/guards/auth.guard';

/**
 * Garde-fous sur la table de routes (F-29 SF-29-03).
 *
 * Angular résout les routes dans leur ordre de déclaration : une route publique déclarée
 * après la route parente pathless authentifiée serait captée par celle-ci et passerait par
 * l'authGuard. Une page légale accessible aux seuls utilisateurs connectés ne remplit aucune
 * de ses fonctions — ni l'obligation légale, ni la preuve d'éditeur pour un analyste externe.
 * Ces tests vérifient la structure, et non le rendu.
 */
describe('app.routes', () => {
  const LEGAL_PATHS = ['mentions-legales', 'confidentialite', 'cgu', 'contact'];
  const PUBLIC_PATHS = ['', 'login', 'register', ...LEGAL_PATHS];

  /** Index de la route parente pathless qui porte l'authGuard. */
  function guardedParentIndex(): number {
    return routes.findIndex((r) => r.path === '' && !!r.canActivate?.includes(authGuard));
  }

  it('déclare une route pour chaque page légale', () => {
    for (const path of LEGAL_PATHS) {
      expect(routes.some((r: Route) => r.path === path))
        .withContext(`route /${path} absente`)
        .toBeTrue();
    }
  });

  it("n'applique aucun guard aux routes publiques", () => {
    for (const path of PUBLIC_PATHS) {
      const route = routes.find((r: Route) => r.path === path && !r.canActivate);
      expect(route).withContext(`/${path} devrait être publique et sans guard`).toBeDefined();
    }
  });

  it('déclare les pages légales avant la route parente authentifiée', () => {
    const parent = guardedParentIndex();
    expect(parent).toBeGreaterThan(-1);
    for (const path of LEGAL_PATHS) {
      const index = routes.findIndex((r: Route) => r.path === path);
      expect(index)
        .withContext(`/${path} déclarée après le parent authentifié : elle passerait par l'authGuard`)
        .toBeLessThan(parent);
    }
  });

  it('maintient la protection des espaces authentifiés', () => {
    const parent = routes[guardedParentIndex()];
    expect(parent.canActivate).toContain(authGuard);
    const children = (parent.children ?? []).map((c) => c.path);
    for (const path of ['chat', 'atelier', 'documents', 'ask', 'templates', 'billing',
                        'reports', 'settings', 'profile', 'admin']) {
      expect(children).withContext(`${path} n'est plus sous la route protégée`).toContain(path);
    }
  });

  it('conserve le joker en dernière position', () => {
    expect(routes[routes.length - 1].path).toBe('**');
  });
});
