import { chatStepsToBlocks } from './chat-steps';

/**
 * Adaptation des étapes de la boucle maison en blocs de terminal (F-39 / SF-39-08, D-L4-5).
 *
 * <p>C'est le point où les deux flux de l'Atelier se rejoignent : sans lui, le moteur le plus abouti
 * — celui qui exécute sur la machine de l'utilisateur — resterait privé des acquis §4 de F-30.</p>
 */
describe('chatStepsToBlocks (F-39 SF-39-08)', () => {

  it('rend la commande puis sa sortie pour une étape bash', () => {
    const blocks = chatStepsToBlocks([{ type: 'bash', path: 'npm test', output: 'ok 1\nok 2\n' }]);

    expect(blocks.length).toBe(1);
    expect(blocks[0].tool).toBe('bash');
    expect(blocks[0].command).toBe('npm test');
    expect(blocks[0].output).toBe('ok 1\nok 2\n');
    expect(blocks[0].hasOutput).toBeTrue();
  });

  it('distingue « pas encore de sortie » d\'une sortie vide', () => {
    const blocks = chatStepsToBlocks([
      { type: 'read', path: 'src/main.ts' },
      { type: 'bash', path: 'true', output: '' },
    ]);

    // `read` n'en produit jamais ; un `bash` peut en produire une vide, et ça n'est pas la même chose.
    expect(blocks[0].hasOutput).toBeFalse();
    expect(blocks[1].hasOutput).toBeTrue();
  });

  it('nomme les gestes de fichier en clair', () => {
    const blocks = chatStepsToBlocks([
      { type: 'read', path: 'a.txt' },
      { type: 'write', path: 'b.txt' },
    ]);

    expect(blocks[0].command).toBe('lecture a.txt');
    expect(blocks[1].command).toBe('écriture b.txt');
  });

  it('reste présentable sur un type inconnu plutôt que de l\'étiqueter faussement', () => {
    // Le type est une chaîne libre (contrat de messages runner §3) : le backend peut en ajouter.
    const blocks = chatStepsToBlocks([{ type: 'quelque_chose', path: 'argument brut' }]);

    expect(blocks[0].tool).toBe('quelque_chose');
    expect(blocks[0].command).toBe('argument brut');
  });

  it('donne à chaque bloc un identifiant distinct', () => {
    const blocks = chatStepsToBlocks([{ type: 'bash', path: 'a' }, { type: 'bash', path: 'b' }]);

    expect(blocks[0].toolUseId).not.toBe(blocks[1].toolUseId);
  });

  it('ne place aucun fil : la boucle maison est séquentielle', () => {
    const blocks = chatStepsToBlocks([{ type: 'bash', path: 'a' }]);

    expect(blocks[0].threadId).toBeNull();
  });
});
