import { AtelierTerminalEmail } from '../../core/models/atelier.models';
import { emailBlock, emailFallbackNotice, emailHeadline, emailStateLabel, isFinalEmailStatus } from './terminal-email';

/** Le bloc « Courriel envoyé », en mots (F-110 / SF-110-02). */
describe('terminal-email (fonctions pures)', () => {
  const email: AtelierTerminalEmail = {
    emailId: 'e1', recipient: 'franck@cagip.fr', recipientVerified: true, clientName: 'CAGIP',
    subject: 'Compte rendu', attachmentCount: 0, status: 'PENDING',
  };

  it("dit l'état de remise", () => {
    expect(emailStateLabel('PENDING')).toBe("en cours d'envoi");
    expect(emailStateLabel('SENDING')).toBe("en cours d'envoi");
    expect(emailStateLabel('SENT')).toBe('accepté par le relais');
    expect(emailStateLabel('FAILED', 'adresse refusée par le relais')).toBe('non remis : adresse refusée par le relais');
    expect(emailStateLabel('FAILED')).toBe('non remis');
    expect(isFinalEmailStatus('SENT')).toBeTrue();
    expect(isFinalEmailStatus('FAILED')).toBeTrue();
    expect(isFinalEmailStatus('PENDING')).toBeFalse();
  });

  it('écrit la ligne, les pièces jointes et le repli', () => {
    expect(emailHeadline(email)).toBe('Courriel envoyé à franck@cagip.fr — Compte rendu');
    expect(emailHeadline({ ...email, attachmentCount: 2 })).toBe('Courriel envoyé à franck@cagip.fr — Compte rendu — 2 pièces jointes');
    expect(emailHeadline({ ...email, attachmentCount: 1 })).toContain('1 pièce jointe');
    expect(emailFallbackNotice(email)).toBeNull();
    expect(emailFallbackNotice({ ...email, recipientVerified: false }))
      .toBe("Aucune adresse vérifiée pour CAGIP : envoyé à l'adresse du compte.");
  });

  it("fait un bloc de transcription portant le reçu, sans sortie", () => {
    const block = emailBlock('toolu_1', email);
    expect(block.tool).toBe('email_me');
    expect(block.toolUseId).toBe('toolu_1');
    expect(block.hasOutput).toBeFalse();
    expect(block.email).toEqual(email);
  });
});
