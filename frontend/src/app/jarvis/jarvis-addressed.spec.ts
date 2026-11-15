import { awaitsAnswer, isBriefReply, isClarification, isNotForMe, liveFinalDiscord, mayHushPardon } from './jarvis-addressed';
import { speechGate } from './jarvis-speech-gate';

/** **« Pas pour moi »** (F-265 / SF-265-01) — cas réels tirés de `jarvis_audio_captures` et `atelier_messages`. */
describe('« Pas pour moi » (F-265 / SF-265-01)', () => {
  /** Les paires (direct, finale) du PO, 09/11 et 15/11 : sa voix, jamais discordante. */
  const PO = [
    ['Terminé. Est-ce que tu vois des sujets en cours, euh, côté WAF sur EDENRED ?', 'Est-ce que tu vois des sujets en cours côté WAF sur EDENRED ?'],
    ['OK, au niveau de ces chantiers, qu\'est-ce qu\'il reste à faire ?', 'OK. Au niveau de ces chantiers, qu\'est-ce qui reste à faire ?'],
    ['Bonjour Jarvis.', 'Bonjour Jarvis'],
    ['OK, allons plus dans le sujet Alarm4tech. Can I change ?', 'OK, allons un peu dans le sujet alarm4tech. Qu\'en est-il ?'],
    ['OK, réponds-moi vers ce que tu sais.', 'OK, réponds-moi avec ce que tu sais'],
    ['Alors, celui chantier 2, sujet RH.', 'Avance le chantier 2, sujet RH'],
    ['Un sujet qui était lié à DevOps Observability. Tu te rappelles ?', 'Le sujet qui était lié à DevOps Observability. Tu te rappelles ?'],
    ['Il répond pas avec ce que tu sais, tu viens de le dire.', 'Mais répondant, est-ce que tu sais ? Tu viens de le dire'],
    ['OK, est-ce que tu peux me montrer la galerie des vidéos ?', 'OK. Est-ce que tu peux me montrer la galerie des vidéos ?'],
  ];

  it('la voix du PO (9 paires réelles) : jamais discordante', () => {
    for (const [live, final] of PO) {
      expect(liveFinalDiscord(live, final)).withContext(final).toBeFalse();
    }
  });

  it('15/11 09:34:44 — direct « Lui-même ? », finale « Maman ? Maman ? » : discordante, refusée par la porte', () => {
    expect(liveFinalDiscord('Lui-même ?', 'Maman ? Maman ?')).toBeTrue();
    expect(speechGate('Maman ? Maman ?', { voicedMs: 787, listenedMs: 2_600, transcribed: true, liveText: 'Lui-même ?' }))
      .toBe('discordant');
    // Sans retranscription finale (le texte EST le direct) : rien à comparer.
    expect(speechGate('Maman ? Maman ?', { voicedMs: 787, listenedMs: 2_600, liveText: 'Lui-même ?' })).toBeNull();
  });

  it('15/11 09:34:55 — direct vide : la discordance ne juge rien (un mot bref manqué par le flux reste entendu)', () => {
    expect(liveFinalDiscord('', 'Ah bah, c\'est pas dit non')).toBeFalse();
    expect(liveFinalDiscord(null, 'Oui.')).toBeFalse();
  });

  it('contrainte dure : une réponse brève n’est jamais discordante, même mal transcrite en direct', () => {
    for (const [live, final] of [['Nan.', 'Non.'], ['Ouais.', 'Oui.'], ['Wi', 'Oui.'], ['Stoppe.', 'Stop.'],
      ['La 2e.', 'La deuxième.'], ['Vas-y.', 'Allons-y.'], ['Celle-là.', 'La deuxième.'], ['Lui-même ?', 'Oui.'],
      ['Arrête.', 'Stop.'], ['Non merci.', 'Oui, vas-y.']]) {
      expect(liveFinalDiscord(live, final)).withContext(`${live} / ${final}`).toBeFalse();
    }
  });

  it('une phrase longue n’est pas jugée (le flux y est le plus fiable)', () => {
    expect(liveFinalDiscord('Montre-moi le message sur le dernier chantier', 'Montre-moi les messages sur les niche entier '
      + 'que je t\'ai envoyés pour pouvoir le faire')).toBeFalse();
    expect(liveFinalDiscord('Rien à voir du tout', 'un deux trois quatre cinq six sept huit')).toBeFalse();
  });

  it('réponses brèves reconnues ; une phrase adressée à un tiers ne l’est pas', () => {
    for (const brief of ['Oui.', 'Non.', 'Non merci.', 'La deuxième', 'Stop', 'Vas-y.', 'Oui, Jarvis.', 'D\'accord.',
      'Celle-là.', 'Le premier.', 'Arrête.', 'Allons-y.', 'Exactement.', 'C\'est ça.', 'Pas du tout.', 'Numéro 3.']) {
      expect(isBriefReply(brief)).withContext(brief).toBeTrue();
    }
    for (const other of ['Maman ? Maman ?', 'Ah bah, c\'est pas dit non', 'Lui-même ?', 'Tu viens manger ?', '']) {
      expect(isBriefReply(other)).withContext(other).toBeFalse();
    }
  });

  it('« je n’ai pas compris » reconnu (réponses réelles) ; une question de précision non', () => {
    for (const pardon of ['Pardon, Monsieur, je n\'ai pas bien compris : pouvez-vous répéter?',
      'Pardon, Monsieur, je n\'ai pas bien saisi : à quoi faites-vous référence?',
      'Pardon, Monsieur, je n\'ai pas saisi la fin.', 'Je n\'ai pas bien compris, pouvez-vous répéter ?',
      'Pardon, Monsieur ?', '🎙 Pardon, Monsieur, je n’ai pas compris.',
      'Pardon, Monsieur, je ne suis pas certain de vous suivre.']) {
      expect(isClarification(pardon)).withContext(pardon).toBeTrue();
    }
    for (const answer of ['Pardon, Monsieur, à quel sujet répondez-vous : agenor, data-platform ou le WAF?',
      'Voici la galerie des vidéos, Monsieur.', 'La deuxième quoi, Monsieur ?',
      'Pardon, Monsieur, je me suis arrêté avant de les montrer.']) {
      expect(isClarification(answer)).withContext(answer).toBeFalse();
    }
  });

  it('« ne s’adresse pas à moi » reconnu (banc F-256, voix tierce « Tu viens manger ? ») ; une vraie réponse non', () => {
    for (const aside of ['Pardon, Monsieur, je crains que cette invitation ne s\'adresse pas à moi.',
      'Cette question ne m\'est sans doute pas adressée, Monsieur.', 'Je crois que cela ne m\'était pas destiné.']) {
      expect(isNotForMe(aside)).withContext(aside).toBeTrue();
    }
    for (const answer of ['Voici la galerie des vidéos, Monsieur.', 'Pardon, Monsieur, que souhaitez-vous que je montre?',
      'Ce message s\'adresse à l\'équipe RH, Monsieur.', 'Vos deux clients, CAGIP et EDENRED.']) {
      expect(isNotForMe(answer)).withContext(answer).toBeFalse();
    }
  });

  it('Jarvis attend une réponse après une vraie question, pas après un « Voici » ni une demande de répéter', () => {
    expect(awaitsAnswer('Le WAF est à jour. Par quel sujet commençons-nous ?')).toBeTrue();
    expect(awaitsAnswer('Comment souhaitez-vous que je vous appelle ?')).toBeTrue();
    expect(awaitsAnswer('Voici la galerie des vidéos, Monsieur.')).toBeFalse();
    expect(awaitsAnswer('Pardon, Monsieur, je n\'ai pas bien compris : pouvez-vous répéter?')).toBeFalse();
    expect(awaitsAnswer(null)).toBeFalse();
  });

  it('se taire : phrase courte, hors question, hors réveil, jamais un mot bref ni une phrase qui nomme Jarvis', () => {
    const voici = { lastSaid: 'Voici la galerie des vidéos, Monsieur.' };
    expect(mayHushPardon('Ah bah, c\'est pas dit non.', voici)).toBeTrue();
    expect(mayHushPardon('Maman ? Maman ?', voici)).toBeTrue();
    expect(mayHushPardon('Maman ? Maman ?', { lastSaid: null })).toBeTrue();
    expect(mayHushPardon('Ah bah, c\'est pas dit non.', { lastSaid: 'Par quel sujet commençons-nous ?' })).toBeFalse();
    expect(mayHushPardon('Ah bah, c\'est pas dit non.', { ...voici, preRoll: true })).toBeFalse();
    for (const brief of ['Oui.', 'Non.', 'La deuxième', 'Stop', 'Vas-y.']) {
      expect(mayHushPardon(brief, voici)).withContext(brief).toBeFalse();
    }
    expect(mayHushPardon('Jarvis, montre ça.', voici)).toBeFalse();
    // Banc F-256 (enchaînement) : « Sur data-platform », phrase réelle du PO coupée — elle nomme un sujet : pas de silence.
    expect(mayHushPardon('Sur data-platform', { lastSaid: null, known: ['data-platform', 'CAGIP'] })).toBeFalse();
    expect(mayHushPardon('Et chez Edenred ?', { lastSaid: null, known: ['waf', 'EDENRED'] })).toBeFalse();
    expect(mayHushPardon('Sur le chantier 2.', voici)).toBeFalse();
    expect(mayHushPardon('Maman ? Maman ?', { ...voici, known: ['data-platform', 'CAGIP'] })).toBeTrue();
    expect(mayHushPardon('On a actuellement dans l\'application une série de galeries vidéo.', voici)).toBeFalse();
  });
});
