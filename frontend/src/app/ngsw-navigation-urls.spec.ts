/**
 * **Le service worker ne doit jamais intercepter `/api` (correctif P0, SF-152-04).**
 *
 * <p>`ngsw-config.json` (SF-152-02) ne définissait aucun `navigationUrls`. Angular appliquait alors
 * le défaut (`["/**","!/**\/*.*","!/**\/*__*","!/**\/*__*\/**"]`) qui **n'exclut pas `/api`** : le
 * service worker traitait les **navigations pleine page** vers `/api/...` comme des navigations
 * d'application et servait `index.html` au lieu de laisser passer la requête. « Continuer avec
 * Google » (`window.location.href = '/api/oauth2/authorization/google'`) et le callback OAuth
 * (`/api/login/oauth2/code/google`) étaient donc **cassés** sur tout appareil ayant le SW.</p>
 *
 * <p>Ce garde-fou échoue si l'exclusion `!/api/**` disparaît des `navigationUrls`, ou si les 4
 * défauts Angular (fallback SPA des routes d'application) ne sont plus tous présents.</p>
 */
import ngswConfig from '../../ngsw-config.json';

describe('ngsw-config — navigationUrls', () => {
  const navigationUrls = (ngswConfig as { navigationUrls?: string[] }).navigationUrls;

  it('définit un navigationUrls explicite', () => {
    expect(Array.isArray(navigationUrls)).toBeTrue();
  });

  it('exclut /api/** des navigations interceptées (correctif OAuth P0)', () => {
    expect(navigationUrls).toContain('!/api/**');
  });

  it('conserve les 4 défauts Angular (fallback SPA des routes d\'application)', () => {
    expect(navigationUrls).toContain('/**');
    expect(navigationUrls).toContain('!/**/*.*');
    expect(navigationUrls).toContain('!/**/*__*');
    expect(navigationUrls).toContain('!/**/*__*/**');
  });

  it('ne cache toujours pas l\'API/SSE (aucun dataGroups — invariant SF-152-02)', () => {
    expect((ngswConfig as { dataGroups?: unknown }).dataGroups).toBeUndefined();
  });
});
