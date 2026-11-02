import {
  ExchangeFiller, FORMULAS, FORMULA_HISTORY_KEY, FormulaPicker, LINKED_FILLER_DELAY_MS, RECENT_WINDOW, hasMonsieur,
  isSmallTalk, linkedFiller, tutoie,
} from './jarvis-phrases';

function memory(): Pick<Storage, 'getItem' | 'setItem'> & { data: Record<string, string> } {
  const data: Record<string, string> = {};
  return { data, getItem: (k) => data[k] ?? null, setItem: (k, v) => { data[k] = v; } };
}

/** Un hasard reproductible (LCG). */
function seeded(seed: number): () => number {
  let x = seed;
  return () => {
    x = (x * 1_103_515_245 + 12_345) % 2_147_483_648;
    return x / 2_147_483_648;
  };
}

/** **Les formules de Jarvis** (F-233 / SF-233-11). */
describe('Les formules de Jarvis (F-233 / SF-233-11)', () => {
  it('aucune formule ne tutoie ; le vouvoiement est reconnu comme tel', () => {
    for (const formula of FORMULAS) {
      expect(tutoie(formula.text)).withContext(formula.text).toBeFalse();
    }
    expect(tutoie('Je t’écoute.')).toBeTrue();
    expect(tutoie('Tu veux que je regarde ?')).toBeTrue();
    expect(tutoie('Je vous écoute.')).toBeFalse();
    expect(tutoie('C’est en cours.')).toBeFalse();
  });

  it('F-239 — les formules d’attente génériques et « Je vous écoute » / « À votre écoute » ne sont plus tirées', () => {
    const picker = new FormulaPicker(seeded(5), memory());
    expect(picker.pick('filler')).toBeNull();
    const wakes = new Set(Array.from({ length: 200 }, () => picker.pick('wake')!.id));
    expect([...wakes].sort()).toEqual(['allez-y', 'dites-moi', 'oui', 'oui-court']);
  });

  it('jamais la même formule de réveil deux fois de suite (500 tirages)', () => {
    for (const seed of [1, 7, 42]) {
      const picker = new FormulaPicker(seeded(seed), memory());
      let last = '';
      for (let i = 0; i < 500; i++) {
        const formula = picker.pick('wake')!;
        expect(formula.id).withContext(`tirage ${i}`).not.toBe(last);
        last = formula.id;
      }
    }
  });

  it('« Monsieur » rarement : au plus une fois sur 5 formules', () => {
    const picker = new FormulaPicker(seeded(3), memory());
    const said = Array.from({ length: 400 }, () => picker.pick('wake')!);
    for (let i = 0; i + RECENT_WINDOW <= said.length; i++) {
      expect(said.slice(i, i + RECENT_WINDOW).filter(hasMonsieur).length).toBeLessThanOrEqual(1);
    }
    expect(said.filter(hasMonsieur).length).toBeLessThan(said.length / 5);
  });

  it('se souvient des dernières formules d’une ouverture à l’autre', () => {
    const store = memory();
    const first = new FormulaPicker(() => 0.5, store).pick('wake')!;
    expect(JSON.parse(store.data[FORMULA_HISTORY_KEY])).toEqual([first.id]);
    const next = new FormulaPicker(() => 0.5, store).pick('wake')!;
    expect(next.id).not.toBe(first.id);
  });

  it('ne rend que les formules disponibles (clips chargés), et rien s’il n’y en a pas', () => {
    const picker = new FormulaPicker(() => 0, memory());
    expect(picker.pick('wake', (id) => id === 'dites-moi')!.id).toBe('dites-moi');
    // Seule disponible et déjà dite : rien plutôt qu'une répétition.
    expect(picker.pick('wake', (id) => id === 'dites-moi')).toBeNull();
    expect(picker.pick('wake', () => false)).toBeNull();
  });

  it('F-239 — salutations et politesses reconnues ; une demande n’en est pas une', () => {
    for (const said of ['Bonjour Jarvis.', 'Bonjour, Monsieur', 'Merci beaucoup !', 'Bonjour Jarvis, ça va ?',
      'Salut', 'Au revoir, à demain', 'Ok Jarvis', '']) {
      expect(isSmallTalk(said)).withContext(said).toBeTrue();
    }
    for (const said of ['Bonjour, regarde les tickets d’Edenred', 'Où en est CAGIP ?', 'Merci de lancer les tests']) {
      expect(isSmallTalk(said)).withContext(said).toBeFalse();
    }
  });

  it('F-239 — l’attente liée : le sujet ou le chantier nommé, sinon rien ; jamais le gabarit précédent', () => {
    const names = ['CAGIP', 'EDENRED', 'agenor', 'data-platform'];
    expect(LINKED_FILLER_DELAY_MS).toBe(2_500);
    expect(linkedFiller('Regarde les tickets bloqués d’Edenred', names, null, () => 0)!.text)
      .toBe('Je regarde pour EDENRED.');
    expect(linkedFiller('Où en est data-platform ?', names, 0, () => 0)!.text).toBe('Je consulte data-platform.');
    expect(linkedFiller('Où en est le chantier 7 ?', names, null, () => 0.99)!.text)
      .toBe('Je me penche sur le chantier 7.');
    expect(linkedFiller('Bonjour Jarvis', names, null)).toBeNull();
    expect(linkedFiller('Lance les tests', names, null)).toBeNull();
    for (let last = 0; last < 3; last++) {
      for (const r of [0, 0.5, 0.99]) {
        expect(linkedFiller('Et CAGIP ?', names, last, () => r)!.template).not.toBe(last);
      }
    }
  });

  it('une formule d’attente au plus par échange, et aucune si la réponse est déjà là', () => {
    const filler = new ExchangeFiller();
    filler.begin();
    expect(filler.take()).toBeTrue();
    expect(filler.take()).toBeFalse();

    filler.begin();
    filler.answered();
    expect(filler.take()).toBeFalse();
  });
});
