import { MAX_SUGGESTIONS, suggestionsFor } from './turn-suggestions';

/**
 * La suite suggérée (F-144 / SF-144-01).
 *
 * <p>Ce qui s'y joue : la suggestion doit être **la bonne**, en **petit nombre**, et **jamais
 * inventée**. Une puce qui ne correspond à rien apprend à ne plus les lire.</p>
 */
describe('suggestionsFor', () => {
  it('LE CRITÈRE : nomme l\'étape de plan restée ouverte', () => {
    const suggestions = suggestionsFor({
      plan: [
        { title: 'Lire la configuration du bastion', status: 'done' },
        { title: 'Vérifier le certificat', status: 'active' },
        { title: 'Écrire la note', status: 'pending' },
      ],
    });

    expect(suggestions[0].label).toBe('Continuer : Vérifier le certificat');
    expect(suggestions[0].text).toBe('Continue : Vérifier le certificat');
  });

  it('prend la première étape en attente quand aucune n\'est active', () => {
    const suggestions = suggestionsFor({
      plan: [
        { title: 'Étape faite', status: 'done' },
        { title: 'Étape suivante', status: 'pending' },
      ],
    });

    expect(suggestions[0].label).toContain('Étape suivante');
  });

  it('ne suggère rien du plan quand tout est fait', () => {
    const suggestions = suggestionsFor({ plan: [{ title: 'Tout est fait', status: 'done' }] });

    expect(suggestions.filter((suggestion) => suggestion.label.startsWith('Continuer'))).toHaveSize(0);
  });

  it('propose de reprendre un tour interrompu', () => {
    expect(suggestionsFor({ interrupted: true })[0].label)
      .toBe('Reprends là où tu t\'es arrêté');
  });

  it('propose de reprendre un tour arrêté au plafond', () => {
    expect(suggestionsFor({ budgetReached: true })[0].label)
      .toBe('Reprends : le plafond du tour a été atteint');
  });

  it('ne propose qu\'UNE façon de reprendre, même si les deux signaux sont là', () => {
    // Deux puces diraient la même chose à quelqu'un qui n'a qu'un geste à faire.
    const suggestions = suggestionsFor({ interrupted: true, budgetReached: true });

    expect(suggestions.filter((s) => s.label.startsWith('Reprends'))).toHaveSize(1);
  });

  it('propose de vérifier quand des fichiers ont changé', () => {
    // Sur la machine d'un client, ce qui suit une modification est toujours d'en constater l'effet.
    expect(suggestionsFor({ diffs: [{}] })[0].label)
      .toBe('Vérifie l\'état après ces modifications');
  });

  it('ne dépasse jamais trois suggestions', () => {
    const suggestions = suggestionsFor({
      plan: [{ title: 'À faire', status: 'pending' }],
      interrupted: true,
      diffs: [{}, {}],
    });

    expect(suggestions.length).toBeLessThanOrEqual(MAX_SUGGESTIONS);
  });

  it('coupe un titre d\'étape très long, ne le rejette pas', () => {
    const suggestions = suggestionsFor({
      plan: [{ title: 'x'.repeat(200), status: 'pending' }],
    });

    expect(suggestions).toHaveSize(1);
    expect(suggestions[0].label.length).toBeLessThan(80);
    expect(suggestions[0].label).toContain('…');
  });

  it('ne suggère rien sans relevé, ni sur un tour ordinaire', () => {
    expect(suggestionsFor(null)).toEqual([]);
    expect(suggestionsFor(undefined)).toEqual([]);
    // Un tour qui s'est bien terminé, sans plan et sans modification : rien à proposer.
    expect(suggestionsFor({})).toEqual([]);
  });
});
