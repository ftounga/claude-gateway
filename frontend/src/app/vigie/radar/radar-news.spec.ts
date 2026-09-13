import { HttpErrorResponse } from '@angular/common/http';

import { looksLikePastedMail, newsErrorOf, newsUndoErrorOf } from './radar-news';

/** Donner la nouvelle — fonctions pures (F-104 / SF-104-02). */
describe('radar-news', () => {
  it('reconnaît un en-tête de courriel collé, en français et en anglais', () => {
    expect(looksLikePastedMail('De : Sophie <s@x.fr>\nEnvoyé : lundi 9 septembre 2026 14:32\nObjet : MFA\n\nBonjour')).toBeTrue();
    expect(looksLikePastedMail('-----Original-----\nFrom: Julie\nSent: Monday, September 9, 2026\nSubject: SSO')).toBeTrue();
    expect(looksLikePastedMail('From: Julie\nle retour arrive')).toBeFalse();
    expect(looksLikePastedMail('Paul m\'a dit que le pilote MFA glisse à octobre.')).toBeFalse();
    expect(looksLikePastedMail('')).toBeFalse();
  });

  it('dit les échecs : quota, fournisseur, hors Vigie, conflit', () => {
    expect(newsErrorOf(new HttpErrorResponse({ status: 402 }))).toContain('quota');
    expect(newsErrorOf(new HttpErrorResponse({ status: 503 }))).toContain('indisponible');
    expect(newsErrorOf(new HttpErrorResponse({ status: 409 }))).toContain('Vigie');
    expect(newsErrorOf(new HttpErrorResponse({ status: 400, error: { message: 'La nouvelle est vide.' } })))
      .toBe('La nouvelle est vide.');
    expect(newsUndoErrorOf(new HttpErrorResponse({ status: 409 }))).toContain('Rien n’a été annulé');
  });
});
