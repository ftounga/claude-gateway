import { HttpErrorResponse } from '@angular/common/http';

import { HostMailAddress } from '../../core/models/mail.models';
import { isPlausibleAddress, isValidCode, mailErrorOf, mailSentence } from './host-mail-address';

/** L'adresse de réception, en mots (F-110 / SF-110-01). */
describe('host-mail-address (fonctions pures)', () => {
  const none: HostMailAddress = {
    address: null, verified: false, verifiedAt: null, codePending: false, codeExpiresAt: null,
    accountEmail: 'ntounga@gmail.com', recipient: 'ntounga@gmail.com', fallback: true, clientName: 'CAGIP',
  };

  it("dit l'adresse vérifiée, sans attention", () => {
    const sentence = mailSentence({ ...none, address: 'franck@cagip.fr', verified: true, recipient: 'franck@cagip.fr',
      fallback: false });
    expect(sentence.text).toBe('Courriels : franck@cagip.fr');
    expect(sentence.attention).toBeFalse();
  });

  it('dit le code en attente et le repli, en ambre', () => {
    const sentence = mailSentence({ ...none, address: 'franck@cagip.fr', codePending: true });
    expect(sentence.text).toContain('code envoyé à franck@cagip.fr');
    expect(sentence.text).toContain('ntounga@gmail.com');
    expect(sentence.attention).toBeTrue();
  });

  it("dit le repli sur l'adresse du compte quand rien n'est vérifié", () => {
    expect(mailSentence(none).text).toBe("Courriels : aucune adresse vérifiée — envoi à l'adresse du compte ntounga@gmail.com");
    // Une adresse déclarée dont le code a expiré ne reçoit rien : le repli est dit.
    expect(mailSentence({ ...none, address: 'franck@cagip.fr' }).text).toContain("l'adresse du compte");
  });

  it('valide une adresse plausible et un code à six chiffres', () => {
    expect(isPlausibleAddress(' franck@cagip.fr ')).toBeTrue();
    expect(isPlausibleAddress('franck@cagip')).toBeFalse();
    expect(isPlausibleAddress('fr anck@cagip.fr')).toBeFalse();
    expect(isPlausibleAddress('')).toBeFalse();
    expect(isValidCode('012345')).toBeTrue();
    expect(isValidCode('12345')).toBeFalse();
    expect(isValidCode('12345a')).toBeFalse();
  });

  it('rend le message de la gateway, ou une phrase de repli', () => {
    const refused = new HttpErrorResponse({ status: 429, error: { error: 'mail_code_throttled', message: 'Patientez.' } });
    expect(mailErrorOf(refused)).toBe('Patientez.');
    expect(mailErrorOf(new HttpErrorResponse({ status: 404 }))).toContain("n'est pas disponible");
    expect(mailErrorOf(new HttpErrorResponse({ status: 0 }))).toContain('injoignable');
  });
});
