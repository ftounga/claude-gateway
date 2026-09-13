import { TestBed } from '@angular/core/testing';
import { Route, Router, UrlSegment, provideRouter } from '@angular/router';

import { forgeMatcher, routes, vigieMatcher } from './app.routes';
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
                        'reports', 'settings', 'profile', 'admin', 'pages/:id']) {
      expect(children).withContext(`${path} n'est plus sous la route protégée`).toContain(path);
    }
  });

  it('conserve le joker en dernière position', () => {
    expect(routes[routes.length - 1].path).toBe('**');
  });

  /** Position de la route `/forge` + `/forge/:hostRef` (F-98 / SF-98-01), portée par un matcher. */
  function forgeIndex(): number {
    return (routes[guardedParentIndex()].children ?? []).findIndex((c) => c.matcher === forgeMatcher);
  }

  // ---- F-98 / SF-98-01 : le poste ouvert dans l'URL ----

  describe('le poste ouvert dans l’URL (F-98)', () => {
    const segments = (...paths: string[]) => paths.map((path) => new UrlSegment(path, {}));

    it('/forge : la Forge, sans poste désigné', () => {
      const match = forgeMatcher(segments('forge'));

      expect(match?.consumed.length).toBe(1);
      expect(match?.posParams?.['hostRef']).toBeUndefined();
    });

    it('/forge/<id> : le poste désigné par son identifiant', () => {
      const match = forgeMatcher(segments('forge', 'h1'));

      expect(match?.consumed.length).toBe(2);
      expect(match?.posParams?.['hostRef'].path).toBe('h1');
    });

    it('ne capte ni les écrans de la Forge, ni les chemins plus profonds, ni un autre préfixe', () => {
      for (const path of ['supervision', 'mosaique', 'voir']) {
        expect(forgeMatcher(segments('forge', path)))
          .withContext(`/forge/${path} serait pris pour un poste`).toBeNull();
      }
      expect(forgeMatcher(segments('forge', 'h1', 'x'))).toBeNull();
      expect(forgeMatcher(segments('atelier', 'w1'))).toBeNull();
      expect(forgeMatcher([])).toBeNull();
    });
  });

  // ---- F-106 / SF-106-02 : la Vigie ----

  describe('la Vigie (F-106)', () => {
    const segments = (...paths: string[]) => paths.map((path) => new UrlSegment(path, {}));

    it('/vigie et /vigie/<id> : une seule route, le client dans le paramètre', () => {
      expect(vigieMatcher(segments('vigie'))?.consumed.length).toBe(1);
      const match = vigieMatcher(segments('vigie', 'h1'));
      expect(match?.posParams?.['hostRef'].path).toBe('h1');
    });

    it("ne capte ni la Forge, ni l'atelier, ni les chemins plus profonds", () => {
      expect(vigieMatcher(segments('forge', 'h1'))).toBeNull();
      expect(vigieMatcher(segments('atelier', 'w1'))).toBeNull();
      expect(vigieMatcher(segments('vigie', 'h1', 'x'))).toBeNull();
      expect(vigieMatcher([])).toBeNull();
      expect(forgeMatcher(segments('vigie'))).toBeNull();
    });

    it("l'adresse d'un sujet charge la page sujet, sous la route authentifiée (F-103 / SF-103-01)", async () => {
      const guard = routes[guardedParentIndex()];
      const children = guard.children ?? [];
      const index = children.findIndex((child) => child.path === 'vigie/:hostRef/sujets/:subjectId');
      const subject = children[index];

      expect(subject).toBeDefined();
      expect(subject.redirectTo).toBeUndefined();
      // Déclarée avant la Vigie : l'ordre le rend vrai même si le matcher changeait un jour.
      expect(index).toBeLessThan(children.findIndex((child) => child.matcher === vigieMatcher));
      const loaded = await (subject.loadComponent as () => Promise<{ name: string }>)();
      expect(loaded.name).toBe('RadarSubjectPageComponent');
    });

    it('est déclarée sous la route authentifiée', () => {
      const children = routes[guardedParentIndex()].children ?? [];
      expect(children.some((child) => child.matcher === vigieMatcher)).toBeTrue();
    });
  });

  // ---- F-68 / SF-68-01 : l'accueil de la Forge, et aucun lien brisé ----

  describe("l'accueil de la Forge (F-68)", () => {
    function children(): Route[] {
      return routes[guardedParentIndex()].children ?? [];
    }

    it('déclare /forge sous la route authentifiée, sur la vue des missions', () => {
      const forge = children().find((c) => c.matcher === forgeMatcher);

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

    it('garde /forge/supervision sous la route authentifiée, en redirection (F-98 / SF-98-04)', () => {
      const supervision = children().find((c) => c.path === 'forge/supervision');

      expect(supervision).withContext('/forge/supervision absente : les anciens liens tomberaient').toBeDefined();
      expect(supervision?.redirectTo).toBeDefined();
      expect(supervision?.loadComponent).toBeUndefined();
    });

    it('ne masque ni /forge, ni /postes, ni les routes de l’Atelier', () => {
      // Elle a DEUX segments : elle ne peut capter ni un chemin d'un segment, ni `atelier/:id`,
      // dont le premier segment diffère. Le test fige ce raisonnement.
      const paths = children().map((c) => c.path);

      expect(forgeIndex()).toBeGreaterThan(-1);
      expect(paths).toContain('postes');
      expect(paths).toContain('atelier/:id');
      // F-98 : le matcher de la Forge vient APRÈS les écrans à deux segments.
      expect(forgeIndex()).toBeGreaterThan(paths.indexOf('forge/supervision'));
    });
  });

  // ---- F-83 / SF-83-02 : la mosaïque, à côté de la supervision et sans rien masquer ----

  describe('la mosaïque (F-83)', () => {
    function children(): Route[] {
      return routes[guardedParentIndex()].children ?? [];
    }

    it('garde /forge/mosaique sous la route authentifiée, en redirection (F-98 / SF-98-04)', () => {
      const mosaique = children().find((c) => c.path === 'forge/mosaique');

      expect(mosaique).withContext('/forge/mosaique absente : les anciens liens tomberaient').toBeDefined();
      expect(mosaique?.redirectTo).toBeDefined();
      expect(mosaique?.loadComponent).toBeUndefined();
    });

    it('les deux densités vivent dans /forge/voir, déclarée avant le matcher de la Forge', () => {
      const paths = children().map((c) => c.path);
      const voir = children().find((c) => c.path === 'forge/voir');

      expect(voir?.loadComponent).toBeDefined();
      expect(paths).toContain('forge/supervision');
      expect(paths).toContain('forge/mosaique');
      expect(paths).toContain('atelier/:id');
      expect(forgeIndex()).toBeGreaterThan(paths.indexOf('forge/mosaique'));
      expect(forgeIndex()).toBeGreaterThan(paths.indexOf('forge/voir'));
    });

    describe('les anciennes adresses mènent à la bonne densité (routeur réel)', () => {
      beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideRouter(children())] });
      });

      it('/forge/supervision ⇒ /forge/voir?densite=apercus, en gardant la requête', async () => {
        const router = TestBed.inject(Router);

        await router.navigateByUrl('/forge/supervision?x=1');

        expect(router.url).toBe('/forge/voir?x=1&densite=apercus');
      });

      it('/forge/mosaique ⇒ /forge/voir?densite=flux', async () => {
        const router = TestBed.inject(Router);

        await router.navigateByUrl('/forge/mosaique');

        expect(router.url).toBe('/forge/voir?densite=flux');
      });

      it('/forge/voir n\'est pas pris pour un poste', async () => {
        const router = TestBed.inject(Router);

        await router.navigateByUrl('/forge/voir?densite=flux');

        expect(router.url).toBe('/forge/voir?densite=flux');
        expect(router.routerState.snapshot.root.firstChild?.routeConfig?.path).toBe('forge/voir');
      });
    });
  });
});
