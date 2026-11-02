import { applyCorrection, parseVoiceCorrection } from './jarvis-correction';

/** Corriger de la voix (F-239 / SF-239-06). */
describe('Corriger de la voix (F-239 / SF-239-06)', () => {
  it('reconnaît « non, j’ai dit … » et ses variantes', () => {
    expect(parseVoiceCorrection('Non, j’ai dit CAGIP.')).toBe('CAGIP');
    expect(parseVoiceCorrection('non j\'ai dit Edenred')).toBe('Edenred');
    expect(parseVoiceCorrection('Je voulais dire le chantier 3')).toBe('le chantier 3');
    expect(parseVoiceCorrection('Jarvis, j’ai dit agenor')).toBe('agenor');
    expect(parseVoiceCorrection('Non, c’est CAGIP')).toBe('CAGIP');
    expect(parseVoiceCorrection('C’est bon')).toBeNull();
    expect(parseVoiceCorrection('Où en est CAGIP ?')).toBeNull();
    expect(parseVoiceCorrection('')).toBeNull();
  });

  it('remplace le mot le plus proche, ou toute la demande si la correction est longue', () => {
    expect(applyCorrection('Où en est Cagibi ?', 'CAGIP')).toBe('Où en est CAGIP ?');
    expect(applyCorrection('Passe sur Eden Red et montre-moi les chantiers.', 'Edenred'))
      .toBe('Passe sur Edenred et montre-moi les chantiers.');
    expect(applyCorrection('Regarde le ticket', 'Regarde le ticket LDIC-223 de gitlab'))
      .toBe('Regarde le ticket LDIC-223 de gitlab');
    expect(applyCorrection('', 'CAGIP')).toBe('CAGIP');
  });
});
