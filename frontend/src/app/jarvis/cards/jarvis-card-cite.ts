import { EXCLUDED_NAMES, normalizeName } from '../scenes/jarvis-name-matcher';
import { CardsEvent, JarvisCard } from './jarvis-card.models';

/**
 * F-248 / SF-248-05 — **citer = montrer** (cadrage §4.3, D2, D3) : reconnaître, **sur l'appareil**, au moment où une
 * phrase commence à être jouée, les pancartes du catalogue qu'elle nomme. Patron prudent de `jarvis-name-matcher`
 * (F-234 D2) : texte normalisé (casse, accents), frontière de mot, alias ≥ 3 caractères, mots courants exclus ; **clés
 * techniques** reconnues telles quelles (`PROJ-123`, `!42`, `#17`, `2.14.1`) ; ordinaux d'un jeu déjà ouvert (« le
 * deuxième ») ; un alias qui désigne deux pancartes n'allume rien. Un faux positif coûte une pancarte, un faux négatif
 * rien. 0 ms côté serveur, aucun appel.
 */

/** Les clés techniques d'un texte, telles quelles (minuscules) : `proj-123`, `!42`, `#17`, `2.14.1`. */
export function technicalKeys(text: string): string[] {
  const out = new Set<string>();
  const t = text ?? '';
  for (const m of t.matchAll(/\b[A-Za-z][A-Za-z0-9]{1,15}-\d{1,7}\b/g)) {
    out.add(m[0].toLowerCase());
  }
  for (const m of t.matchAll(/[!#]\d{1,7}\b/g)) {
    out.add(m[0]);
  }
  for (const m of t.matchAll(/\bv?\d{1,4}\.\d{1,4}(?:\.\d{1,6})?\b/g)) {
    out.add(m[0].toLowerCase().replace(/^v/, ''));
  }
  return [...out];
}

const ORDINALS: Record<string, number> = {
  premier: 1, premiere: 1, deuxieme: 2, second: 2, seconde: 2, troisieme: 3, quatrieme: 4, cinquieme: 5,
  sixieme: 6,
};

/** Un alias assez distinctif pour allumer une pancarte (prudence F-234). */
function matchable(alias: string): boolean {
  return alias.length >= 3 && !EXCLUDED_NAMES.has(alias) && !/^\d+$/.test(alias) && alias.split(' ').length <= 8;
}

/** L'index d'un catalogue : alias normalisé → pancartes, clé technique → pancartes. */
export class CardIndex {
  private readonly byAlias = new Map<string, Set<string>>();
  private readonly byKey = new Map<string, Set<string>>();
  readonly cards: ReadonlyMap<string, JarvisCard>;

  constructor(cards: readonly JarvisCard[]) {
    const map = new Map<string, JarvisCard>();
    for (const card of cards) {
      map.set(card.id, card);
      for (const raw of card.aliases ?? []) {
        const alias = normalizeName(raw ?? '');
        if (matchable(alias)) {
          this.add(this.byAlias, alias, card.id);
        }
      }
      // Les clés techniques des alias et du titre (« PROJ-123 — Migration SSO ») ; une fois par pancarte.
      for (const key of new Set([card.title, ...(card.aliases ?? [])].flatMap((raw) => technicalKeys(raw ?? '')))) {
        this.add(this.byKey, key, card.id);
      }
    }
    this.cards = map;
  }

  private add(index: Map<string, Set<string>>, key: string, id: string): void {
    const set = index.get(key) ?? new Set<string>();
    set.add(id);
    index.set(key, set);
  }

  /**
   * Les pancartes que nomme `sentence` (identifiants, dans l'ordre du catalogue). `deck` = le jeu affiché, pour les
   * ordinaux (« le deuxième »). Un alias ou une clé ambigus (deux pancartes) n'allument rien.
   */
  cited(sentence: string, deck: readonly string[] = []): string[] {
    const found = new Set<string>();
    const text = ` ${normalizeName(sentence)} `;
    for (const key of technicalKeys(sentence)) {
      const ids = this.byKey.get(key);
      if (ids?.size === 1) {
        found.add([...ids][0]);
      }
    }
    for (const [alias, ids] of this.byAlias) {
      if (ids.size === 1 && text.includes(` ${alias} `)) {
        found.add([...ids][0]);
      }
    }
    if (deck.length) {
      for (const m of text.matchAll(/\b(?:le|la|du|au)\s+(premier|premiere|deuxieme|second|seconde|troisieme|quatrieme|cinquieme|sixieme)\b/g)) {
        const id = deck[ORDINALS[m[1]] - 1];
        if (id) {
          found.add(id);
        }
      }
    }
    return [...this.cards.keys()].filter((id) => found.has(id));
  }
}

/** Pur, exporté pour les tests et le banc : les pancartes que nomme une phrase. */
export function cardsCited(sentence: string, catalogue: readonly JarvisCard[], deck: readonly string[] = []): string[] {
  return new CardIndex(catalogue).cited(sentence, deck);
}

/**
 * **Le jeu d'une réponse, au fil de la voix** (D2, D10, K2) : le catalogue reçu (`cards`), les phrases jouées, et ce qui
 * doit être montré. Lecture ciblée ⇒ tout, dès la **première phrase jouée** (jamais avant) ; lecture d'office ⇒ seules
 * les pancartes citées, à la phrase qui les nomme. Pur et sans horloge : l'écran lui donne les instants.
 */
export class CardShowing {
  private event: CardsEvent | null = null;
  private index: CardIndex | null = null;
  private readonly played: string[] = [];
  private readonly cited = new Set<string>();
  private firstPlayedAt: number | null = null;
  /** Le premier jeu montré (ms), pour la mesure `cards_lag_ms`. */
  shownAt: number | null = null;
  /** Les pancartes déjà montrées (chacune comptée une fois, `c_tgt` / `c_cit`). */
  readonly shownIds = new Set<string>();

  constructor(readonly turn: number) {}

  /** Le catalogue de la réponse (le dernier reçu remplace le précédent, même `responseId`). */
  receive(event: CardsEvent): void {
    this.event = event;
    this.index = new CardIndex(event.cards ?? []);
    for (const sentence of this.played) {
      this.index.cited(sentence).forEach((id) => this.cited.add(id));
    }
  }

  get responseId(): string | null {
    return this.event?.responseId ?? null;
  }

  /** F-248 / SF-248-07 — la mention du pied du jeu, ou `null`. */
  get offline(): string | null {
    return this.event?.offline ?? null;
  }

  get hasCatalogue(): boolean {
    return (this.event?.cards?.length ?? 0) > 0;
  }

  /** Une phrase commence à être jouée : rend les pancartes qu'elle nomme (identifiants). */
  playing(sentence: string, at: number, deck: readonly string[] = []): string[] {
    this.firstPlayedAt ??= at;
    this.played.push(sentence);
    const ids = this.index ? this.index.cited(sentence, deck) : [];
    ids.forEach((id) => this.cited.add(id));
    return ids;
  }

  /** Vrai dès que la première phrase a été jouée. */
  get started(): boolean {
    return this.firstPlayedAt != null;
  }

  /** Le délai première phrase jouée → maintenant (ms), ou `null`. */
  lag(at: number): number | null {
    return this.firstPlayedAt == null ? null : Math.max(0, Math.round(at - this.firstPlayedAt));
  }

  /** Ce qui doit être montré maintenant : rien avant la première phrase ; ciblées + citées ensuite. */
  visible(): JarvisCard[] {
    if (!this.event || !this.started) {
      return [];
    }
    return this.event.cards.filter((c) => c.targeted || this.cited.has(c.id));
  }

  /** Combien de ciblées / citées dans le jeu visible (mesure `c_tgt` / `c_cit`). */
  counts(): { targeted: number; cited: number } {
    const v = this.visible();
    return { targeted: v.filter((c) => c.targeted).length, cited: v.filter((c) => !c.targeted).length };
  }
}
