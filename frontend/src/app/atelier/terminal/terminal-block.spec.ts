import { AtelierTerminalBlock } from '../../core/models/atelier.models';
import { subtaskIndexes, subtaskLabel } from './terminal-block';

/**
 * Numérotation des sous-tâches de la vue terminal (F-35 SF-35-03). Le premier fil rencontré est le
 * coordinateur — il ne porte aucun badge, ce qui garantit qu'un run séquentiel s'affiche exactement
 * comme avant F-35.
 */
describe('terminal-block — sous-tâches (F-35)', () => {
  const block = (threadId: string | null): AtelierTerminalBlock => ({
    tool: 'bash',
    command: 'npm test',
    toolUseId: null,
    threadId,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
  });

  it('ne numérote rien quand aucun bloc ne porte de fil', () => {
    expect(subtaskIndexes([block(null), block(null)]).size).toBe(0);
  });

  it('ne numérote rien quand un seul fil est présent : c’est le coordinateur', () => {
    expect(subtaskIndexes([block('thr_main'), block('thr_main')]).size).toBe(0);
  });

  it('numérote les fils autres que le premier, dans l’ordre d’apparition', () => {
    const indexes = subtaskIndexes([
      block('thr_main'),
      block('thr_a'),
      block('thr_b'),
      block('thr_a'),
    ]);

    expect(indexes.get('thr_main')).toBeUndefined();
    expect(indexes.get('thr_a')).toBe(1);
    expect(indexes.get('thr_b')).toBe(2);
  });

  it('ignore les blocs sans fil sans perturber la numérotation', () => {
    const indexes = subtaskIndexes([block('thr_main'), block(null), block('thr_a')]);

    expect(indexes.get('thr_a')).toBe(1);
  });

  it('traite le premier fil rencontré comme coordinateur même s’il arrive après un bloc sans fil', () => {
    const indexes = subtaskIndexes([block(null), block('thr_main'), block('thr_a')]);

    expect(indexes.get('thr_main')).toBeUndefined();
    expect(indexes.get('thr_a')).toBe(1);
  });

  it('n’étiquette pas les blocs du coordinateur', () => {
    const blocks = [block('thr_main'), block('thr_a')];
    const indexes = subtaskIndexes(blocks);

    expect(subtaskLabel(blocks[0], indexes)).toBeNull();
    expect(subtaskLabel(blocks[1], indexes)).toBe('sous-tâche 1');
  });

  it('n’étiquette pas un bloc sans fil', () => {
    expect(subtaskLabel(block(null), subtaskIndexes([block('thr_main'), block('thr_a')]))).toBeNull();
  });
});
