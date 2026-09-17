import { splitEssential } from './essential';

/**
 * Découpage « L'essentiel » / « Le détail » (F-126 / SF-126-01). Fonction pure, testée sans TestBed.
 */
describe('splitEssential', () => {
  it('découpe essentiel + détail quand les deux marqueurs sont présents', () => {
    const { essential, detail } = splitEssential(
      '<<essentiel>>\nNon — la sandbox ne valide pas.\n<</essentiel>>\nLes quatre divergences : …',
    );
    expect(essential).toBe('Non — la sandbox ne valide pas.');
    expect(detail).toBe('Les quatre divergences : …');
  });

  it('renvoie essential=null et le message entier en détail sans marqueur (repli gracieux)', () => {
    const content = 'Une réponse tout à fait normale, sans balise.';
    const { essential, detail } = splitEssential(content);
    expect(essential).toBeNull();
    expect(detail).toBe(content);
  });

  it('tolère la casse et les espaces dans les marqueurs', () => {
    const { essential, detail } = splitEssential(
      '<<  Essentiel >>Oui.<< / ESSENTIEL >>Le reste.',
    );
    expect(essential).toBe('Oui.');
    expect(detail).toBe('Le reste.');
  });

  it("prend tout le reste comme essentiel quand la fermeture manque (streaming/troncature)", () => {
    const { essential, detail } = splitEssential('<<essentiel>>\nRéponse en cours de frappe');
    expect(essential).toBe('Réponse en cours de frappe');
    expect(detail).toBe('');
  });

  it('ne renvoie pas de bloc pour un essentiel vide', () => {
    const { essential, detail } = splitEssential('<<essentiel>>\n\n<</essentiel>>\nSeul le détail.');
    expect(essential).toBeNull();
    expect(detail).toBe('Seul le détail.');
  });

  it('recolle le texte avant l\'ouverture au détail', () => {
    const { essential, detail } = splitEssential(
      'Bonjour.\n<<essentiel>>La réponse.<</essentiel>>\nLe détail.',
    );
    expect(essential).toBe('La réponse.');
    expect(detail).toBe('Bonjour.\n\nLe détail.');
  });

  it('ne confond pas le marqueur essentiel avec le marqueur fin-de-tour (F-125)', () => {
    // Le marqueur fin-de-tour est un commentaire HTML ; il ne contient pas les chevrons essentiels.
    const content = 'Réponse.\n\n<!-- fin-de-tour: promotion=aucune; dette=0 -->';
    const { essential, detail } = splitEssential(content);
    expect(essential).toBeNull();
    // splitEssential ne touche pas au marqueur fin-de-tour : c'est renderMarkdown qui le strippe.
    expect(detail).toBe(content);
  });

  it('gère null et undefined', () => {
    expect(splitEssential(null)).toEqual({ essential: null, detail: '' });
    expect(splitEssential(undefined)).toEqual({ essential: null, detail: '' });
  });
});
