import {
  ExchangeFiller, FORMULAS, FORMULA_HISTORY_KEY, FormulaPicker, RECENT_WINDOW, hasMonsieur, tutoie,
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

  it('jamais la même formule deux fois de suite, ni parmi les 5 dernières (500 tirages, mêlés)', () => {
    for (const seed of [1, 7, 42]) {
      const picker = new FormulaPicker(seeded(seed), memory());
      const said: string[] = [];
      for (let i = 0; i < 500; i++) {
        const formula = picker.pick(i % 7 === 0 ? 'wake' : 'filler')!;
        expect(said.slice(-RECENT_WINDOW)).withContext(`tirage ${i}`).not.toContain(formula.id);
        said.push(formula.id);
      }
    }
  });

  it('« Monsieur » rarement : au plus une fois sur 5 formules', () => {
    const picker = new FormulaPicker(seeded(3), memory());
    const said = Array.from({ length: 400 }, () => picker.pick('filler')!);
    for (let i = 0; i + RECENT_WINDOW <= said.length; i++) {
      expect(said.slice(i, i + RECENT_WINDOW).filter(hasMonsieur).length).toBeLessThanOrEqual(1);
    }
    expect(said.filter(hasMonsieur).length).toBeLessThan(said.length / 5);
  });

  it('se souvient des dernières formules d’une ouverture à l’autre', () => {
    const store = memory();
    const first = new FormulaPicker(() => 0.5, store).pick('filler')!;
    expect(JSON.parse(store.data[FORMULA_HISTORY_KEY])).toEqual([first.id]);
    const next = new FormulaPicker(() => 0.5, store).pick('filler')!;
    expect(next.id).not.toBe(first.id);
  });

  it('ne rend que les formules disponibles (clips chargés), et rien s’il n’y en a pas', () => {
    const picker = new FormulaPicker(() => 0, memory());
    expect(picker.pick('filler', (id) => id === 'je-regarde')!.id).toBe('je-regarde');
    // Seule disponible et déjà dite : rien plutôt qu'une répétition.
    expect(picker.pick('filler', (id) => id === 'je-regarde')).toBeNull();
    expect(picker.pick('wake', () => false)).toBeNull();
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
