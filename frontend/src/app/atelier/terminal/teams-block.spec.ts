import { AtelierTeamsCard, AtelierTerminalBlock } from '../../core/models/atelier.models';
import {
  cardAsText,
  cardBlock,
  cardOf,
  certaintyLabel,
  gapsLabel,
  hasContent,
  isUncertain,
  momentSpeaker,
  shortTime,
  withCards,
} from './teams-block';

/**
 * **Les décisions d'affichage du compte rendu** (F-89 / SF-89-03), en fonctions pures.
 *
 * <p>La plus importante est la dernière : hors d'un terminal Teams, un bloc riche est rendu
 * <b>en texte</b> — jamais en carte, jamais masqué. C'est le troisième et dernier verrou de la règle
 * non négociable du volet, après les deux de SF-89-02.</p>
 */
describe('teams-block (F-89 / SF-89-03)', () => {
  const line = (over: Partial<AtelierTeamsCard['sections'][0]['lines'][0]> = {}) => ({
    text: 'Fournir le schéma réseau',
    author: 'Paul',
    at: '2026-09-12T14:32:00Z',
    messageId: 'm-1',
    webUrl: 'https://teams.microsoft.com/l/message/m-1',
    certainty: 'EXPLICITE' as const,
    ...over,
  });

  const card = (over: Partial<AtelierTeamsCard> = {}): AtelierTeamsCard => ({
    kind: 'MEETING_CARD',
    title: 'Comité de migration',
    subtitle: '12 septembre',
    window: 'du 5 au 12 septembre, 47 messages lus',
    sections: [{ title: 'Ce qu\'on attend de vous', lines: [line()] }],
    moments: [],
    gaps: [],
    ...over,
  });

  const block = (over: Partial<AtelierTerminalBlock> = {}): AtelierTerminalBlock => ({
    tool: 'teams_meeting_card',
    toolUseId: 'tu_1',
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    ...over,
  });

  describe('la certitude est un MOT, jamais un score', () => {
    it('rend les deux libellés en toutes lettres', () => {
      expect(certaintyLabel('EXPLICITE')).toBe('explicite');
      expect(certaintyLabel('A_CONFIRMER')).toBe('à confirmer');
    });

    it('une certitude absente vaut « à confirmer » : le doute n\'affirme jamais', () => {
      expect(certaintyLabel(undefined)).toBe('à confirmer');
      expect(isUncertain(line({ certainty: undefined as never }))).toBeTrue();
    });

    it('aucun libellé ne contient de chiffre', () => {
      expect(certaintyLabel('EXPLICITE')).not.toMatch(/\d/);
      expect(certaintyLabel('A_CONFIRMER')).not.toMatch(/\d/);
    });
  });

  describe('l\'heure', () => {
    it('se lit en heures et minutes', () => {
      const at = new Date(2026, 8, 12, 14, 32).toISOString();
      expect(shortTime(at)).toBe('14:32');
    });

    it('un horodatage illisible est rendu TEL QUEL plutôt qu\'effacé', () => {
      // L'effacer masquerait une dérive de format de l'adaptateur — exactement ce qu'on veut voir.
      expect(shortTime('pas-une-date')).toBe('pas-une-date');
      expect(shortTime('')).toBe('');
    });
  });

  describe('ce qui n\'a pas pu être lu', () => {
    it('une liste vide dit « aucun manque signalé », jamais « tout a été lu »', () => {
      expect(gapsLabel(card({ gaps: [] }))).toContain('Aucun manque signalé');
    });

    it('les manques sont écrits', () => {
      expect(gapsLabel(card({ gaps: ['3 messages non reconnus', 'fil non atteint'] })))
        .toBe('Non lu : 3 messages non reconnus ; fil non atteint');
    });
  });

  describe('un bloc vide n\'est pas rendu', () => {
    it('sans ligne ni moment, il n\'y a rien à montrer', () => {
      expect(hasContent(card({ sections: [], moments: [] }))).toBeFalse();
      expect(hasContent(null)).toBeFalse();
      expect(hasContent(card())).toBeTrue();
      expect(hasContent(card({ sections: [], moments: [{ at: 'x', quote: 'y', speaker: '', imageId: '', webUrl: '' }] })))
        .toBeTrue();
    });
  });

  describe('UN TERMINAL DE PROJET RESTE TEXTUEL POUR TOUJOURS', () => {
    it('sur un terminal Teams, la carte est rendue', () => {
      expect(cardOf(block({ card: card() }), true)).not.toBeNull();
    });

    it('sur un terminal de projet, AUCUNE carte — même quand le bloc en porte une', () => {
      expect(cardOf(block({ card: card() }), false)).toBeNull();
    });

    it('le repli textuel ne perd RIEN : texte, auteur, heure, certitude et lien', () => {
      const text = cardAsText(card({
        gaps: ['3 messages non reconnus'],
        sections: [{ title: 'Décisions', lines: [line({ certainty: 'A_CONFIRMER' })] }],
      }));

      expect(text).toContain('Comité de migration');
      expect(text).toContain('Décisions');
      expect(text).toContain('Fournir le schéma réseau');
      expect(text).toContain('Paul');
      expect(text).toContain('à confirmer');
      expect(text).toContain('https://teams.microsoft.com/l/message/m-1');
      expect(text).toContain('Fenêtre lue : du 5 au 12 septembre, 47 messages lus');
      expect(text).toContain('3 messages non reconnus');
    });
  });

  describe('le locuteur d\'un moment', () => {
    it('est suivi d\'un séparateur, ou absent', () => {
      expect(momentSpeaker({ at: '', quote: '', speaker: 'Claire', imageId: '', webUrl: '' }))
        .toBe('Claire : ');
      expect(momentSpeaker({ at: '', quote: '', speaker: '', imageId: '', webUrl: '' })).toBe('');
    });
  });

  describe('les cartes reçues au fil de l\'eau se replacent où elles sont arrivées', () => {
    const step = (name: string): AtelierTerminalBlock => block({ tool: 'bash', command: name });

    it('sans carte, les blocs sont rendus tels quels', () => {
      const blocks = [step('a'), step('b')];
      expect(withCards(blocks, [])).toBe(blocks);
    });

    it('une carte arrivée après la première étape se place entre les deux', () => {
      const posted = cardBlock('tu_c', card());
      const merged = withCards([step('a'), step('b')], [{ afterSteps: 1, block: posted }]);

      expect(merged.map((entry) => entry.command ?? entry.tool))
        .toEqual(['a', 'teams_card', 'b']);
    });

    it('une carte arrivée avant toute étape ouvre la liste', () => {
      const posted = cardBlock('tu_c', card());
      const merged = withCards([step('a')], [{ afterSteps: 0, block: posted }]);

      expect(merged[0].tool).toBe('teams_card');
    });

    it('une carte arrivée après la dernière étape ferme la liste', () => {
      const posted = cardBlock('tu_c', card());
      const merged = withCards([step('a'), step('b')], [{ afterSteps: 2, block: posted }]);

      expect(merged[merged.length - 1].tool).toBe('teams_card');
    });

    it('le bloc d\'une carte n\'a ni commande ni sortie : ce n\'est pas ce que la machine a répondu', () => {
      const posted = cardBlock('tu_c', card());

      expect(posted.hasOutput).toBeFalse();
      expect(posted.output).toBe('');
      expect(posted.command).toBeUndefined();
      expect(posted.toolUseId).toBe('tu_c');
    });
  });
});
