import { MAX_LISTEN_MS, NoiseFloor, speechGate } from './jarvis-speech-gate';

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

  it('sans voix au micro : une parole très courte est refusée, une longue invraisemblable aussi', () => {
    expect(speechGate('Vente chez le client.', { voicedMs: 0 })).toBe('no_voice');
    expect(speechGate('Vente chez le client.', { voicedMs: 99 })).toBe('no_voice');
    expect(speechGate('Vente chez le client.', { voicedMs: 100 })).toBeNull();
    expect(speechGate('un deux trois quatre cinq six sept huit neuf dix onze', { voicedMs: 500 })).toBe('implausible');
    expect(speechGate('un deux trois quatre cinq six sept huit neuf dix onze', { voicedMs: 2_000 })).toBeNull();
  });

  // F-241 / SF-241-06 — les cas mesurés en prod (essai du PO, 2026-11-02 au soir) : 6 paroles réelles sur 10 refusées.
  it('SF-241-06 — 18 mots dits en 8,6 s avec un micro à gain bas (peu de voix comptée) : acceptés', () => {
    const said = 'Est-ce que tu peux regarder où en est le chantier de migration chez CAGIP et me dire ce qui bloque ?';
    expect(said.split(/\s+/).length).toBeGreaterThanOrEqual(18);
    expect(speechGate(said, { voicedMs: 900, listenedMs: 8_600 })).toBeNull();
    expect(speechGate(said, { voicedMs: 150, listenedMs: 8_600 })).toBeNull();
    // L'ancienne règle (débit sur le temps de voix) la refusait : c'était la cause n° 1 du « micro qui n'entend pas ».
    expect(speechGate(said, { voicedMs: 900 })).toBe('implausible');
    // 119 caractères dits en 12 s, 62 en 5 s : acceptés.
    expect(speechGate('Rappelle-moi demain matin d’appeler Sophie pour la réunion du comité de pilotage, '
      + 'et prépare-moi les chiffres du mois.', { voicedMs: 400, listenedMs: 12_000 })).toBeNull();
    expect(speechGate('Ouvre le terminal de CAGIP et relance les tests du connecteur.',
      { voicedMs: 200, listenedMs: 5_000 })).toBeNull();
    // Une phrase courte à voix faible (23 caractères) : acceptée dès que le micro a entendu un peu de voix.
    expect(speechGate('Jarvis, tu m’entends ?', { voicedMs: 120, listenedMs: 2_500 })).toBeNull();
  });

  it('SF-241-06 — l’anti-hallucination tient : « Sous-titres réalisés par… » dans le silence, débit impossible', () => {
    expect(speechGate('Sous-titres réalisés par la communauté d’Amara.org', { voicedMs: 0, listenedMs: 20_000 }))
      .toBe('hallucination');
    expect(speechGate('Vente chez le client.', { voicedMs: 0, listenedMs: 20_000 })).toBe('no_voice');
    // Vingt mots « entendus » en deux secondes d'écoute : physiquement impossible.
    expect(speechGate('un deux trois quatre cinq six sept huit neuf dix onze douze treize quatorze quinze seize '
      + 'dix-sept dix-huit dix-neuf vingt', { voicedMs: 2_000, listenedMs: 2_000 })).toBe('implausible');
  });

  it('SF-241-06 — le seuil de voix suit le bruit de fond : une voix faible compte, un souffle non', () => {
    const floor = new NoiseFloor(0.12);
    for (let i = 0; i < 50; i++) {
      floor.update(0.01);
    }
    expect(floor.voiceLevel()).toBeLessThan(0.12);
    expect(floor.update(0.06)).toBeTrue();
    expect(floor.update(0.02)).toBeFalse();
    // Un micro au bruit de fond élevé garde le seuil fixe.
    const loud = new NoiseFloor(0.12);
    for (let i = 0; i < 50; i++) {
      loud.update(0.08);
    }
    expect(loud.voiceLevel()).toBe(0.12);
    expect(loud.update(0.1)).toBeFalse();
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
