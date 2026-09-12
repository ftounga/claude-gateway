import { HttpErrorResponse } from '@angular/common/http';

import {
  FORGE_ACCESS_BILLING_ROUTE,
  FORGE_ACCESS_CODE_FRAGMENT,
  FORGE_ACCESS_REFUSAL,
  isForgeAccessDenied,
} from './forge-access';

/** Fabrique une erreur HTTP telle que l'écran la reçoit réellement. */
function httpError(status: number, body: unknown = null): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: body, url: '/api/runner-hosts' });
}

describe('forge-access (F-85 / SF-85-04)', () => {
  describe('reconnaître le refus — et seulement lui', () => {
    it('reconnaît le 403 de la garde, avec son discriminant', () => {
      expect(
        isForgeAccessDenied(
          httpError(403, {
            error: 'atelier_forbidden',
            message: "La Forge demande l'offre Gold, ou l'option Forge ajoutée à votre offre.",
          }),
        ),
      ).toBeTrue();
    });

    it('reconnaît un 403 nu — sur ces chemins, la garde en est la seule source', () => {
      expect(isForgeAccessDenied(httpError(403))).toBeTrue();
      expect(isForgeAccessDenied(httpError(403, ''))).toBeTrue();
    });

    it("ne reconnaît PAS une panne réseau : c'est toute la distinction", () => {
      // status 0 = la gateway n'a pas répondu. Le dire « accès refusé » serait un mensonge.
      expect(isForgeAccessDenied(httpError(0))).toBeFalse();
    });

    it('ne reconnaît pas un 5xx, un 409, un 404 ni un 415', () => {
      expect(isForgeAccessDenied(httpError(500))).toBeFalse();
      expect(isForgeAccessDenied(httpError(502))).toBeFalse();
      expect(isForgeAccessDenied(httpError(409, { message: 'Runner déconnecté.' }))).toBeFalse();
      expect(isForgeAccessDenied(httpError(404, { error: 'not_found' }))).toBeFalse();
      expect(isForgeAccessDenied(httpError(415))).toBeFalse();
    });

    it("ne reconnaît pas un 403 d'une AUTRE origine", () => {
      expect(isForgeAccessDenied(httpError(403, { error: 'admin_forbidden' }))).toBeFalse();
      expect(
        isForgeAccessDenied(httpError(403, { error: 'access_code_not_for_account' })),
      ).toBeFalse();
    });

    it("ne reconnaît pas ce qui n'est pas une erreur HTTP", () => {
      expect(isForgeAccessDenied(new Error('boom'))).toBeFalse();
      expect(isForgeAccessDenied(null)).toBeFalse();
      expect(isForgeAccessDenied(undefined)).toBeFalse();
    });
  });

  describe('le message', () => {
    it("dit que l'accès n'est pas ouvert", () => {
      expect(FORGE_ACCESS_REFUSAL).toContain("n'est pas ouvert");
    });

    it('nomme LES DEUX sorties, dont le code d\'accès', () => {
      expect(FORGE_ACCESS_REFUSAL).toContain('souscrire');
      expect(FORGE_ACCESS_REFUSAL).toContain("code d'accès");
    });

    it("n'emploie aucun jargon d'abonnement", () => {
      for (const jargon of ['Gold', 'option', 'plan', 'abonnement', 'offre', 'BYOK', 'Stripe']) {
        expect(FORGE_ACCESS_REFUSAL.toLowerCase()).not.toContain(jargon.toLowerCase());
      }
    });

    it('conduit à la section du code, et pas seulement à la Facturation', () => {
      expect(FORGE_ACCESS_BILLING_ROUTE).toBe('/billing');
      expect(FORGE_ACCESS_CODE_FRAGMENT).toBe('code-acces');
    });
  });
});
