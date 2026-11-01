import { firstSentences, isStopCommand, oralPhrase, subjectSwitch, withEndAnnouncement } from './jarvis-oral';
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
