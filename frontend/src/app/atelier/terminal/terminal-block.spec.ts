import { AtelierTerminalBlock } from '../../core/models/atelier.models';
import { MAIN_THREAD_LABEL, subtaskCount, terminalRows } from './terminal-block';

/**
 * Provenance des blocs du rendu terminal (F-35 / SF-35-03) : fonctions **pures**, testées sans DOM.
 *
 * <p>Le point le plus important est la <b>non-régression</b> : sans délégation — le cas courant — ces
 * fonctions ne doivent produire aucun marqueur, sans quoi tout run séquentiel se mettrait à afficher
 * des libellés qui n'ont pas lieu d'être.</p>
 */
describe('terminal-block — provenance des sous-tâches (F-35)', () => {
  function block(threadId: string | null, command = 'npm test'): AtelierTerminalBlock {
    return {
      tool: 'bash',
      command,
      toolUseId: null,
      output: '',
      hasOutput: false,
      error: false,
      expanded: false,
      threadId,
    };
  }

  it('ne marque rien quand aucun bloc ne porte de fil (rendu d\'avant F-35)', () => {
    const rows = terminalRows([block(null), block(null, 'npm run build')]);

    expect(rows.map((row) => row.header)).toEqual([null, null]);
    expect(rows.every((row) => !row.subtask)).toBeTrue();
    expect(subtaskCount([block(null)])).toBe(0);
  });

  it('numérote les fils dans l\'ordre de leur première apparition', () => {
    const rows = terminalRows([
      block('th_b'),
      block('th_a'),
      block('th_b'),
    ]);

    expect(rows.map((row) => row.header)).toEqual([
      'Sous-tâche 1',
      'Sous-tâche 2',
      'Sous-tâche 1',
    ]);
    expect(rows.every((row) => row.subtask)).toBeTrue();
  });

  it('n\'annonce le fil qu\'au changement : une rafale du même fil ne porte qu\'un libellé', () => {
    const rows = terminalRows([block('th_a'), block('th_a'), block('th_a')]);

    expect(rows.map((row) => row.header)).toEqual(['Sous-tâche 1', null, null]);
  });

  it('annonce le retour au fil principal après une sous-tâche', () => {
    const rows = terminalRows([block(null), block('th_a'), block(null)]);

    expect(rows.map((row) => row.header)).toEqual([
      MAIN_THREAD_LABEL,
      'Sous-tâche 1',
      MAIN_THREAD_LABEL,
    ]);
    expect(rows.map((row) => row.subtask)).toEqual([false, true, false]);
  });

  it('conserve la référence du bloc d\'origine (le repli le mute depuis la vue)', () => {
    const original = block('th_a');

    expect(terminalRows([original])[0].block).toBe(original);
  });

  it('compte les fils distincts, sans jamais compter le fil principal', () => {
    expect(subtaskCount([block(null), block('th_a'), block('th_a'), block('th_b')])).toBe(2);
  });

  it('ne produit aucune ligne pour une transcription vide', () => {
    expect(terminalRows([])).toEqual([]);
    expect(subtaskCount([])).toBe(0);
  });
});
