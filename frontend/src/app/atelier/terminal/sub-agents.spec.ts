import { AtelierTerminalBlock } from '../../core/models/atelier.models';
import {
  exploreGroupAt,
  isGroupedExploreMember,
  isTaskBlock,
  subAgentQuestion,
} from './sub-agents';

/**
 * Rendu des sous-agents en action (F-150 / SF-150-06) : regroupement des explorations parallèles et
 * repérage de la sous-tâche `task`. Fonction pure, testée seule — le gabarit ne fait que la lire.
 */
describe('sub-agents (F-150 SF-150-06)', () => {
  const block = (tool: string, command?: string, output = ''): AtelierTerminalBlock => ({
    tool,
    command,
    toolUseId: null,
    threadId: null,
    output,
    hasOutput: output.length > 0,
    error: false,
    expanded: false,
  });

  const explore = (question: string) => block('explore', `exploration « ${question} »`);

  it('regroupe ≥ 2 explore consécutifs en un lot portant leurs questions dans l’ordre', () => {
    const blocks = [explore('où est AppConfig ?'), explore('qui appelle login ?')];

    const group = exploreGroupAt(blocks, 0);
    expect(group).not.toBeNull();
    expect(group!.questions).toEqual(['où est AppConfig ?', 'qui appelle login ?']);
    // Le second bloc est un membre : le gabarit le masque, l’ouverture a déjà rendu tout le lot.
    expect(isGroupedExploreMember(blocks, 0)).toBeFalse();
    expect(isGroupedExploreMember(blocks, 1)).toBeTrue();
  });

  it('ne regroupe jamais un explore isolé : son rendu ligne est préservé', () => {
    const blocks = [explore('où est AppConfig ?')];

    expect(exploreGroupAt(blocks, 0)).toBeNull();
    expect(isGroupedExploreMember(blocks, 0)).toBeFalse();
  });

  it('ne regroupe que les explore ADJACENTS : un outil intercalé coupe le lot', () => {
    const blocks = [explore('q1'), block('bash', 'npm test'), explore('q2')];

    // Deux explore, mais pas côte à côte : aucun lot (chacun garde sa ligne).
    expect(exploreGroupAt(blocks, 0)).toBeNull();
    expect(exploreGroupAt(blocks, 2)).toBeNull();
    expect(isGroupedExploreMember(blocks, 0)).toBeFalse();
    expect(isGroupedExploreMember(blocks, 2)).toBeFalse();
  });

  it('regroupe un lot au milieu du tour, sans toucher aux blocs voisins', () => {
    const blocks = [block('read', 'lecture a.ts'), explore('q1'), explore('q2'), block('bash', 'ls')];

    const group = exploreGroupAt(blocks, 1);
    expect(group!.questions).toEqual(['q1', 'q2']);
    expect(isGroupedExploreMember(blocks, 1)).toBeFalse();
    expect(isGroupedExploreMember(blocks, 2)).toBeTrue();
    // Les blocs read/bash ne sont ni des ouvertures ni des membres.
    expect(exploreGroupAt(blocks, 0)).toBeNull();
    expect(isGroupedExploreMember(blocks, 3)).toBeFalse();
  });

  it('subAgentQuestion retire l’habillage « exploration « … » » du direct', () => {
    expect(subAgentQuestion(explore('où est X ?'))).toBe('où est X ?');
  });

  it('subAgentQuestion lit la consigne relue telle quelle (historique), sans habillage', () => {
    // En historique, la consigne persistée vaut déjà la question brute (SF-150-06 backend).
    expect(subAgentQuestion(block('explore', 'qui appelle login ?'))).toBe('qui appelle login ?');
  });

  it('subAgentQuestion retombe sur le type plutôt qu’une chaîne vide', () => {
    expect(subAgentQuestion(block('explore'))).toBe('explore');
  });

  it('isTaskBlock distingue la sous-tâche écrivaine', () => {
    expect(isTaskBlock(block('task', 'sous-tâche « range les imports »'))).toBeTrue();
    expect(isTaskBlock(explore('q'))).toBeFalse();
    expect(isTaskBlock(block('bash', 'ls'))).toBeFalse();
  });
});
