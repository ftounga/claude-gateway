import {
  OralStream, classifyInterruption, firstSentences, guardOral, invitationOf, isGenericInvitation, isStopCommand,
  oralLine, oralPhrase, oralSentences, stripInvitationLines, subjectSwitch, withEndAnnouncement,
} from './jarvis-oral';
import { EndOfSpeech, END_OF_SPEECH_MS, NO_SPEECH_MS } from './jarvis-mic-meter';
import { sphere, withAlpha } from './jarvis-orb.component';

describe('Jarvis — ce qu’il dit (F-231 / SF-231-02)', () => {
  it('dit la ligne 🎙 quand l’agent l’a posée', () => {
    expect(oralPhrase('Contexte.\n🎙 C’est fait : l’étape 3 est validée.\n\n## Détail\n- a')).toBe(
      'C’est fait : l’étape 3 est validée.');
  });

  it('sinon, les deux premières phrases de prose, sans balisage ni blocs', () => {
    const reply = '<<essentiel>>**Le build passe.** Les tests aussi. Le déploiement attend.<</essentiel>>\n'
      + '```bash\nmvn test\n```\n| a | b |';
    expect(oralPhrase(reply)).toBe('Le build passe. Les tests aussi.');
  });

  it('rien de lisible : chaîne vide', () => {
    expect(oralPhrase('```mail\nObjet : x\n```')).toBe('');
    expect(oralPhrase(null)).toBe('');
  });

  it('coupe une phrase trop longue au dernier mot', () => {
    const phrase = oralPhrase(`🎙 ${'mot '.repeat(200)}`);
    expect(phrase.length).toBeLessThanOrEqual(401);
    expect(phrase.endsWith('…')).toBeTrue();
  });

  it('firstSentences garde la ponctuation', () => {
    expect(firstSentences('Un. Deux ! Trois ?', 2)).toBe('Un. Deux !');
  });
});

describe('Jarvis — parler court (F-231 / SF-231-03)', () => {
  it('annonce la fin d’un tour long, pas d’un tour bref', () => {
    expect(withEndAnnouncement('Les tests passent.', 31_000)).toBe('C’est terminé. Les tests passent.');
    expect(withEndAnnouncement('Les tests passent.', 4_000)).toBe('Les tests passent.');
  });

  it('reconnaît l’ordre d’arrêt dit seul, pas une phrase qui le contient', () => {
    expect(isStopCommand('Stop !')).toBeTrue();
    expect(isStopCommand('Jarvis, arrête.')).toBeTrue();
    expect(isStopCommand('annule')).toBeTrue();
    expect(isStopCommand('arrête le serveur de recette')).toBeFalse();
  });
});

describe('Jarvis — « passe sur … » (F-231 / SF-231-02)', () => {
  const subjects = [
    { id: '1', name: 'CAGIP — migration' },
    { id: '2', name: 'Édenred' },
    { id: '3', name: 'Connecteur AGENOR' },
  ];

  it('reconnaît le sujet nommé, sans accents ni casse', () => {
    expect(subjectSwitch('Passe sur edenred', subjects)?.id).toBe('2');
    expect(subjectSwitch('Jarvis, bascule sur le sujet CAGIP.', subjects)?.id).toBe('1');
    expect(subjectSwitch('va sur agenor', subjects)?.id).toBe('3');
  });

  it('ne devine pas : inconnu ou ambigu ⇒ null (la phrase part au cerveau)', () => {
    expect(subjectSwitch('passe sur Thales', subjects)).toBeNull();
    expect(subjectSwitch('passe sur e', subjects)).toBeNull();
    expect(subjectSwitch('valide l’étape 3', subjects)).toBeNull();
  });
});

describe('Jarvis — fin de phrase au silence (F-231 / SF-231-02)', () => {
  it('clôt après un silence qui suit de la voix', () => {
    const end = new EndOfSpeech(0);
    expect(end.update(0.5, 100)).toBeFalse();
    expect(end.update(0.01, 200)).toBeFalse();
    expect(end.update(0.01, 200 + END_OF_SPEECH_MS)).toBeTrue();
    expect(end.spoke).toBeTrue();
  });

  it('une reprise de voix remet le silence à zéro', () => {
    const end = new EndOfSpeech(0);
    end.update(0.5, 100);
    end.update(0.01, 200);
    end.update(0.5, 1_000);
    expect(end.update(0.01, 200 + END_OF_SPEECH_MS)).toBeFalse();
  });

  it('sans un mot, s’arrête d’elle-même', () => {
    const end = new EndOfSpeech(0);
    expect(end.update(0.01, NO_SPEECH_MS - 1)).toBeFalse();
    expect(end.update(0.01, NO_SPEECH_MS)).toBeTrue();
    expect(end.spoke).toBeFalse();
  });
});

describe('Jarvis — l’orbe (F-231 / SF-231-02)', () => {
  it('répartit ses neurones sur la sphère unité', () => {
    const nodes = sphere(50);
    expect(nodes.length).toBe(50);
    for (const n of nodes) {
      expect(Math.hypot(n.x, n.y, n.z)).toBeCloseTo(1, 5);
    }
  });

  it('applique une opacité aux couleurs du design system', () => {
    expect(withAlpha('#E07B39', 0.5)).toBe('rgba(224, 123, 57, 0.500)');
    expect(withAlpha('rgb(1,2,3)', 0.5)).toBe('rgb(1,2,3)');
  });
});

describe('La parole au fil de l’écriture (F-233 / SF-233-03)', () => {
  it('une phrase n’est complète que ponctuée et suivie d’un blanc, ou ligne close', () => {
    expect(oralSentences('Il reste 3.5 Go. Je', false)).toEqual(['Il reste 3.5 Go.']);
    expect(oralSentences('Il reste 3.5 Go.', false)).toEqual([]);
    expect(oralSentences('Il reste 3.5 Go. Je nettoie', true)).toEqual(['Il reste 3.5 Go.', 'Je nettoie']);
    expect(oralSentences(' **Prêt**, Monsieur ? ', false)).toEqual(['Prêt, Monsieur ?']);
  });

  it('rend chaque phrase une fois, au plus deux par ligne, hors blocs de code', () => {
    const oral = new OralStream();
    expect(oral.push('🎙 Je lance les tes')).toEqual([]);
    expect(oral.push('ts. Ça prend ')).toEqual(['Je lance les tests.']);
    expect(oral.push('une minute.\n```\n🎙 non.\n```\n🎙 Un. Deux. Trois.\n')).toEqual(
      ['Ça prend une minute.', 'Un.', 'Deux.']);
    expect(oral.push('suite')).toEqual([]);
    expect(oral.spoken.length).toBe(4);
  });

  it('remaining : ce qui reste à dire de la phrase finale', () => {
    const oral = new OralStream();
    oral.push('🎙 Les tests passent. ');
    expect(oral.remaining('Les tests passent. Je pousse ?')).toBe('Je pousse ?');
    expect(oral.remaining('Les tests passent.')).toBe('');
  });
});

describe('Comprendre l’interruption (F-233 / SF-233-09)', () => {
  it('acquiescement, silence, annulation, sinon demande (dans le doute, une demande)', () => {
    expect(classifyInterruption('OK.')).toBe('ack');
    expect(classifyInterruption('mmh')).toBe('ack');
    expect(classifyInterruption('Oui oui')).toBe('ack');
    expect(classifyInterruption('D’accord')).toBe('ack');
    expect(classifyInterruption('Stop !')).toBe('silence');
    expect(classifyInterruption('Tais-toi')).toBe('silence');
    expect(classifyInterruption('c’est bon')).toBe('silence');
    expect(classifyInterruption('Jarvis, laisse tomber')).toBe('cancel');
    expect(classifyInterruption('Arrête ça')).toBe('cancel');
    expect(classifyInterruption('Ok mais vérifie les tests')).toBe('request');
    expect(classifyInterruption('Non, l’autre branche')).toBe('request');
    expect(classifyInterruption('')).toBe('request');
    expect(isStopCommand('arrête ça')).toBeTrue();
  });
});

describe('Jarvis — la ligne de chaque phrase dite (F-240 / SF-240-01)', () => {
  it('chaque phrase porte sa ligne 🎙 ; l’annonce avant outils se distingue de la réponse finale', () => {
    const oral = new OralStream();
    expect(oral.lastLine).toBe(-1);
    const first = oral.pushLines('🎙 Je regarde le chantier 3.\n');
    expect(first.map((s) => s.sentence)).toEqual(['Je regarde le chantier 3.']);
    oral.pushLines('Outil…\n🎙 Le chantier 3 est terminé. ');
    const announceLine = first[0].line;
    expect(oral.lastLine).toBeGreaterThan(announceLine);
    expect(oral.spokenOutside(oral.lastLine)).toEqual(['Je regarde le chantier 3.']);
    expect(oral.spokenOutside(announceLine)).toEqual(['Le chantier 3 est terminé.']);
    expect(oral.spoken).toEqual(['Je regarde le chantier 3.', 'Le chantier 3 est terminé.']);
  });
});

describe('Jarvis — une idée, 25 mots (F-240 / SF-240-02)', () => {
  const LONG = 'Monsieur, tout le réseau tourne autour d’une transit gateway unique dans AWS, reliée à l’on-premise '
    + 'par un Direct Connect. Et l’on-premise ne répond toujours pas à la plage ca-savings, ce qui bloque encore tout le reste du chantier.';

  it('D5 — au-delà de 35 mots, seule la première phrase est dite (coupe comptée) ; sinon rien ne change', () => {
    expect(guardOral('Le build passe. Les tests aussi.')).toEqual({ text: 'Le build passe. Les tests aussi.', cut: false });
    const cut = guardOral(LONG);
    expect(cut.cut).toBeTrue();
    expect(cut.text).toBe('Monsieur, tout le réseau tourne autour d’une transit gateway unique dans AWS, reliée à '
      + 'l’on-premise par un Direct Connect.');
    // Une phrase unique trop longue : rien à couper, elle est dite telle quelle.
    const single = 'mot '.repeat(40).trim();
    expect(guardOral(single)).toEqual({ text: single, cut: false });
    expect(oralLine(`🎙 ${LONG}\n\nDétail`).cut).toBeTrue();
    expect(oralLine('🎙 Le build passe.').cut).toBeFalse();
  });

  it('D6 — l’invitation ciblée est lue à part, jamais comme phrase ni dans le détail', () => {
    const reply = '🎙 Le déploiement est terminé, Monsieur.\n↪ Il y a aussi un point sur Helios, je vous le dis ?\n'
      + '<<essentiel>>\n- Déployé\n<</essentiel>>\nDétail';
    expect(oralPhrase(reply)).toBe('Le déploiement est terminé, Monsieur.');
    expect(invitationOf(reply)).toBe('Il y a aussi un point sur Helios, je vous le dis ?');
    expect(stripInvitationLines(reply)).not.toContain('Helios');
    expect(oralPhrase('↪ Il y a aussi un point sur Helios, je vous le dis ?\nLe build passe.')).toBe('Le build passe.');
  });

  it('D6 — une invitation générique, sans question, trop longue ou après « Vous confirmez ? » est écartée', () => {
    expect(isGenericInvitation('Voulez-vous plus de détails, Monsieur ?')).toBeTrue();
    expect(isGenericInvitation('Je peux développer si vous le souhaitez ?')).toBeTrue();
    expect(isGenericInvitation('Souhaitez-vous que je détaille ?')).toBeTrue();
    expect(isGenericInvitation('Il y a aussi un point sur Helios, je vous le dis ?')).toBeFalse();
    expect(invitationOf('🎙 C’est fait.\n↪ Voulez-vous plus de détails ?')).toBeNull();
    expect(invitationOf('🎙 C’est fait.\n↪ Il y a aussi un point sur Helios.')).toBeNull();
    expect(invitationOf('🎙 C’est fait.\n↪ ' + 'mot '.repeat(25) + '?')).toBeNull();
    expect(invitationOf('🎙 Je lance le chantier 3 en autonome, vous confirmez ?\n↪ Il y a aussi un point sur Helios, '
      + 'je vous le dis ?')).toBeNull();
    expect(invitationOf('🎙 C’est fait.')).toBeNull();
  });

  it('au fil de l’écriture : la garde coupe la 2e phrase, l’invitation est dite une fois sa ligne close', () => {
    const oral = new OralStream();
    const said = oral.pushLines(`🎙 ${LONG}\n`);
    expect(said.map((s) => s.sentence)).toEqual([
      'Monsieur, tout le réseau tourne autour d’une transit gateway unique dans AWS, reliée à l’on-premise par un '
      + 'Direct Connect.']);
    expect(oral.wasCut(said[0].line)).toBeTrue();
    expect(oral.pushLines('↪ Il y a aussi un point sur Helios,')).toEqual([]);
    const invite = oral.pushLines(' je vous le dis ?\nDétail…');
    expect(invite).toEqual([jasmine.objectContaining({ sentence: 'Il y a aussi un point sur Helios, je vous le dis ?',
      invitation: true })]);
    expect(oral.invitation).toBe('Il y a aussi un point sur Helios, je vous le dis ?');
    // L'invitation n'est pas une phrase de la réponse (ni de l'annonce).
    expect(oral.spokenOutside(oral.lastLine)).toEqual([]);
  });

  it('au fil de l’écriture : une invitation générique ou après une confirmation n’est jamais dite', () => {
    const generic = new OralStream();
    expect(generic.pushLines('🎙 C’est fait.\n↪ Voulez-vous plus de détails ?\n')
      .map((s) => s.sentence)).toEqual(['C’est fait.']);
    const confirm = new OralStream();
    expect(confirm.pushLines('🎙 J’envoie le courriel à Paul, vous confirmez ?\n↪ Il y a aussi Helios, je vous le dis ?\n')
      .map((s) => s.sentence)).toEqual(['J’envoie le courriel à Paul, vous confirmez ?']);
    expect(confirm.invitation).toBeNull();
  });
});

