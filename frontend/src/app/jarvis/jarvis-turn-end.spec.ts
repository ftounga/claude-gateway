import { SUSPENDED_EXTRA_MS, isSuspended } from './jarvis-turn-end';

/** Laisser finir la phrase (F-239 / SF-239-10). */
describe('Laisser finir la phrase (F-239 / SF-239-10)', () => {
  it('les phrases restées ouvertes sont en suspens', () => {
    for (const t of ['Allons chez le client et donnez-', 'Vente chez le', 'Regarde le ticket,', 'Fais le point sur',
      'Il faudrait que', 'Euh', 'Et donc…', 'Sur le cluster de', 'Donne-moi l\'état des', 'Passe sur Edenred et']) {
      expect(isSuspended(t)).withContext(t).toBeTrue();
    }
  });

  it('les phrases closes ne le sont pas', () => {
    for (const t of ['Où en est CAGIP ?', 'Bonjour Jarvis.', 'Regarde le ticket LDIC-223', 'Merci', '', 'Lance les tests !']) {
      expect(isSuspended(t)).withContext(t).toBeFalse();
    }
  });

  it('l’attente de plus est d’une seconde et demie', () => {
    expect(SUSPENDED_EXTRA_MS).toBe(1_500);
  });
});
