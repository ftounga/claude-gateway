import { asksConfirmation, classifyConfirmation, confirmationQuestion } from './jarvis-confirm';

/** **Confirmer à voix haute** (F-231 / SF-231-04, D4) : « oui » requis, le doute ne déclenche rien. */
describe('Jarvis — confirmer à voix haute (F-231 / SF-231-04)', () => {
  it('un « oui » net vaut accord', () => {
    for (const said of ['Oui.', 'oui, vas-y', 'Oui c’est bon', 'Je confirme', 'Jarvis, oui']) {
      expect(classifyConfirmation(said)).withContext(said).toBe('oui');
    }
  });

  it('un « non » net vaut refus', () => {
    for (const said of ['Non.', 'non merci', 'Surtout pas !', 'annule']) {
      expect(classifyConfirmation(said)).withContext(said).toBe('non');
    }
  });

  it('tout le reste vaut doute : rien ne se déclenche', () => {
    for (const said of ['', 'oui mais attends', 'd’accord', 'ouvre le fichier', 'oui pas maintenant', 'euh', 'vas-y']) {
      expect(classifyConfirmation(said)).withContext(said).toBe('doute');
    }
  });

  it('reformule l’autorisation d’un outil puis demande « Tu confirmes ? »', () => {
    expect(confirmationQuestion({ tool: 'bash', detail: 'git push origin main' }))
      .toBe('Je vais lancer la commande « git push origin main ». Tu confirmes ?');
    expect(confirmationQuestion({ tool: 'email_me', detail: '' })).toBe('Je vais t’envoyer un courriel. Tu confirmes ?');
    expect(confirmationQuestion({ tool: 'bash', detail: 'x '.repeat(200) }).length).toBeLessThan(200);
  });

  it('repère une phrase de l’agent qui attend « oui »', () => {
    expect(asksConfirmation('Je lance le chantier 4 en autonome. Tu confirmes ?')).toBeTrue();
    expect(asksConfirmation('C’est fait.')).toBeFalse();
  });
});
