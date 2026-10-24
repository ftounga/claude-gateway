import { neutralizeQuestionMarkers, splitEssential, stripNextSteps } from './essential';

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

  // F-192 / SF-192-02 — le bloc « Et maintenant ? » n'est jamais rendu.
  describe('stripNextSteps', () => {
    it('retire un bloc fermé', () => {
      expect(stripNextSteps('Fait.\n\n<<suites>>\n[{}]\n<</suites>>')).toBe('Fait.');
    });
    it('retire tout ce qui suit une ouverture sans fermeture (flux en cours)', () => {
      expect(stripNextSteps('Fait.\n<<suites>>\n[{"titre":"x')).toBe('Fait.');
    });
    it('rend le texte tel quel sans bloc', () => {
      expect(stripNextSteps('Rien\n\n\nici')).toBe('Rien\n\n\nici');
    });
    it('splitEssential ne montre jamais le bloc', () => {
      const split = splitEssential('<<essentiel>>Oui.<</essentiel>>\nDétail.\n<<suites>>[1]<</suites>>');
      expect(split.essential).toBe('Oui.');
      expect(split.detail).toBe('Détail.');
    });
  });
});

describe('neutralizeQuestionMarkers (F-210 / SF-210-03)', () => {
  it('ne montre jamais un marqueur de question brut, et retire les essentiels internes', () => {
    const { essential, detail } = splitEssential(
      '<<question id="1">>\n<<essentiel>>\nOui.\n<</essentiel>>\nDétail 1\n<</question>>\n'
        + '<<question id="2">>\n<<essentiel>>\nNon.\n<</essentiel>>\n<</question>>',
    );
    expect(essential).toBeNull();
    expect(detail).not.toContain('<<');
    expect(detail).toContain('**1.**');
    expect(detail).toContain('**2.**');
    expect(detail).toContain('Oui.');
  });

  it('retire un marqueur encore incomplet en fin de flux', () => {
    expect(neutralizeQuestionMarkers('Texte\n<<quest')).toBe('Texte');
    expect(neutralizeQuestionMarkers('Texte\n<<question id="2">')).toBe('Texte');
  });

  it('laisse un texte sans marqueur intact', () => {
    expect(neutralizeQuestionMarkers('Rien à voir << ici >> !')).toBe('Rien à voir << ici >> !');
  });
});
