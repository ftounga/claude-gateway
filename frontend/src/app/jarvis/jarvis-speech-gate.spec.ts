import { MAX_LISTEN_MS, speechGate } from './jarvis-speech-gate';

/** **La porte de parole réelle** (F-239 / SF-239-01, D3b). */
describe('La porte de parole réelle (F-239 / SF-239-01)', () => {
  const voiced = { voicedMs: 2_000 };

  it('une vraie phrase passe', () => {
    expect(speechGate('Où en est le chantier 3 sur CAGIP ?', voiced)).toBeNull();
    expect(speechGate('Bonjour Jarvis.', voiced)).toBeNull();
  });

  it('vide, bruit, hésitations : refusés', () => {
    expect(speechGate('', voiced)).toBe('empty');
    expect(speechGate('   ', voiced)).toBe('empty');
    expect(speechGate('euh', voiced)).toBe('noise');
    expect(speechGate('Hmm... euh.', voiced)).toBe('noise');
  });

  it('hallucinations typiques du transcripteur, dites seules : refusées', () => {
    for (const ghost of ['Sous-titres réalisés par la communauté d’Amara.org', 'Merci.', 'Merci d’avoir regardé !',
      '(musique)', '[rires]', '♪♪', 'Sous-titrage ST’ 501']) {
      expect(speechGate(ghost, voiced)).withContext(ghost).toBe('hallucination');
    }
    // Dans une vraie phrase, « merci » ne pose aucun problème.
    expect(speechGate('Merci, et regarde maintenant les tickets d’Edenred.', voiced)).toBeNull();
  });

  it('sans voix au micro : une parole courte est refusée, une longue invraisemblable aussi', () => {
    expect(speechGate('Vente chez le client.', { voicedMs: 0 })).toBe('no_voice');
    expect(speechGate('Vente chez le client.', { voicedMs: 299 })).toBe('no_voice');
    expect(speechGate('Vente chez le client.', { voicedMs: 300 })).toBeNull();
    expect(speechGate('un deux trois quatre cinq six sept huit neuf dix onze', { voicedMs: 500 })).toBe('implausible');
    expect(speechGate('un deux trois quatre cinq six sept huit neuf dix onze', { voicedMs: 2_000 })).toBeNull();
  });

  it('niveau non mesuré, ou phrase du réveil : les règles de voix ne s’appliquent pas', () => {
    expect(speechGate('Vente chez le client.', { voicedMs: null })).toBeNull();
    expect(speechGate('Bonjour Jarvis.', { voicedMs: 0, preRoll: true })).toBeNull();
    expect(speechGate('Merci.', { voicedMs: 0, preRoll: true })).toBe('hallucination');
  });

  it('une écoute est bornée à 30 s', () => {
    expect(MAX_LISTEN_MS).toBe(30_000);
  });
});
