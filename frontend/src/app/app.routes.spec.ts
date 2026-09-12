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

  // ---- F-68 / SF-68-01 : l'accueil de la Forge, et aucun lien brisé ----

  describe("l'accueil de la Forge (F-68)", () => {
    function children(): Route[] {
      return routes[guardedParentIndex()].children ?? [];
    }

    it('déclare /forge sous la route authentifiée, sur la vue des missions', () => {
      const forge = children().find((c) => c.path === 'forge');

      expect(forge).withContext('/forge absente : la Forge n\'a plus de page d\'accueil').toBeDefined();
      expect(forge?.loadComponent).toBeDefined();
    });

    it('fait répondre /postes en redirigeant — un lien partagé ne se brise pas', () => {
      const postes = children().find((c) => c.path === 'postes');

      expect(postes).withContext('/postes a disparu : les anciens liens tomberaient').toBeDefined();
      expect(postes?.redirectTo).toBe('/forge');
      // Sans `full`, la redirection capterait aussi d'éventuels chemins plus profonds.
      expect(postes?.pathMatch).toBe('full');
      expect(postes?.loadComponent).toBeUndefined();
    });

    it('conserve les routes de la Forge telles que F-58 les a figées', () => {
      const paths = children().map((c) => c.path);

      for (const path of ['atelier', 'atelier/:id', 'atelier/:id/fichiers']) {
        expect(paths)
          .withContext(`${path} renommée : un onglet ouvert ou un lien collé se briserait`)
          .toContain(path);
      }
    });

    it('déclare /atelier avant /atelier/:id, pour que la liste ne soit pas masquée', () => {
      const paths = children().map((c) => c.path);

      expect(paths.indexOf('atelier')).toBeLessThan(paths.indexOf('atelier/:id'));
    });
  });

  // ---- F-76 / SF-76-03 : la vue de supervision, sans rien masquer ----

  describe('la vue de supervision (F-76)', () => {
    function children(): Route[] {
      return routes[guardedParentIndex()].children ?? [];
    }

    it('déclare /forge/supervision sous la route authentifiée', () => {
      const supervision = children().find((c) => c.path === 'forge/supervision');

      expect(supervision).withContext('/forge/supervision absente').toBeDefined();
      expect(supervision?.loadComponent).toBeDefined();
    });

    it('ne masque ni /forge, ni /postes, ni les routes de l’Atelier', () => {
      // Elle a DEUX segments : elle ne peut capter ni un chemin d'un segment, ni `atelier/:id`,
      // dont le premier segment diffère. Le test fige ce raisonnement.
      const paths = children().map((c) => c.path);

      expect(paths).toContain('forge');
      expect(paths).toContain('postes');
      expect(paths).toContain('atelier/:id');
      expect(paths.indexOf('forge')).toBeLessThan(paths.indexOf('forge/supervision'));
    });
  });

  // ---- F-83 / SF-83-02 : la mosaïque, à côté de la supervision et sans rien masquer ----

  describe('la mosaïque (F-83)', () => {
    function children(): Route[] {
      return routes[guardedParentIndex()].children ?? [];
    }

    it('déclare /forge/mosaique sous la route authentifiée', () => {
      const mosaique = children().find((c) => c.path === 'forge/mosaique');

      expect(mosaique).withContext('/forge/mosaique absente').toBeDefined();
      expect(mosaique?.loadComponent).toBeDefined();
    });

    it('coexiste avec la supervision : deux densités, deux écrans, aucun masqué', () => {
      const paths = children().map((c) => c.path);

      // La supervision de F-76 n'est pas remplacée : ses aperçus gardent leur sens là où l'on ne
      // veut précisément PAS de flux.
      expect(paths).toContain('forge/supervision');
      expect(paths).toContain('forge/mosaique');
      expect(paths).toContain('atelier/:id');
      expect(paths.indexOf('forge')).toBeLessThan(paths.indexOf('forge/mosaique'));
    });
  });
});
