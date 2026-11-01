/**
 * **Les formules de Jarvis** (F-233 / SF-233-11) — un répertoire fermé de courtes phrases au vouvoiement, pré-synthétisées
 * en clips par la passerelle (`JarvisClipService`, mêmes noms, mêmes textes). Jarvis les varie : jamais la même deux
 * fois de suite ni parmi les {@link RECENT_WINDOW} dernières, « Monsieur » rarement, et au plus une formule d'attente
 * par échange — aucune si la réponse arrive vite.
 */

export type FormulaKind = 'wake' | 'filler';

export interface Formula {
  /** Le nom du clip (liste fermée côté passerelle). */
  id: string;
  kind: FormulaKind;
  text: string;
}

/** Le répertoire. Les textes doivent rester identiques à `JarvisClipService.CLIPS`. */
export const FORMULAS: readonly Formula[] = [
  { id: 'oui', kind: 'wake', text: 'Oui, Monsieur ?' },
  { id: 'oui-court', kind: 'wake', text: 'Oui ?' },
  { id: 'je-vous-ecoute', kind: 'wake', text: 'Je vous écoute.' },
  { id: 'dites-moi', kind: 'wake', text: 'Dites-moi.' },
  { id: 'allez-y', kind: 'wake', text: 'Allez-y.' },
  { id: 'a-votre-ecoute', kind: 'wake', text: 'À votre écoute.' },
  { id: 'je-regarde', kind: 'filler', text: 'Je regarde.' },
  { id: 'une-seconde', kind: 'filler', text: 'Une seconde.' },
  { id: 'un-instant', kind: 'filler', text: 'Un instant.' },
  { id: 'je-m-en-occupe', kind: 'filler', text: 'Je m’en occupe.' },
  { id: 'voyons', kind: 'filler', text: 'Voyons cela.' },
  { id: 'je-verifie', kind: 'filler', text: 'Je vérifie.' },
  { id: 'en-cours', kind: 'filler', text: 'C’est en cours.' },
  { id: 'laissez-moi', kind: 'filler', text: 'Laissez-moi regarder.' },
  { id: 'je-m-en-charge', kind: 'filler', text: 'Je m’en charge, Monsieur.' },
];

/** Tous les noms de clips, à précharger. */
export const FORMULA_CLIPS: readonly string[] = FORMULAS.map((f) => f.id);

/** Une formule n'est pas reprise parmi les dernières dites. */
export const RECENT_WINDOW = 5;
/** La chance de tirer une formule « Monsieur » quand elle est permise. */
export const MONSIEUR_RATE = 0.15;
/** L'historique des formules, mémorisé sur l'appareil. */
export const FORMULA_HISTORY_KEY = 'cg.jarvis.formulas.recent';
/**
 * Une formule d'attente n'est dite que si la première phrase de la réponse n'est pas prête ce délai après la fin de la
 * parole : une réponse rapide n'en a pas besoin.
 */
export const FILLER_DELAY_MS = 1_200;

/** La formule porte-t-elle « Monsieur » ? */
export function hasMonsieur(formula: Formula): boolean {
  return /monsieur/i.test(formula.text);
}

/** Un tutoiement dans un texte (« tu », « te », « t' », « toi », « ton », « ta », « tes »). */
export function tutoie(text: string): boolean {
  const words = text.toLowerCase().replace(/['’]/g, '\' ').split(/[^\p{L}']+/u);
  return words.some((w) => ['tu', 'te', 'toi', 'ton', 'ta', 'tes', 't\''].includes(w));
}

/**
 * Le tireur de formules : pur, hasard et mémoire injectables. Exclut la dernière formule toujours, et les
 * {@link RECENT_WINDOW} dernières tant qu'il en reste d'autres ; « Monsieur » au plus une fois sur la fenêtre.
 */
export class FormulaPicker {
  private recent: string[];

  constructor(private readonly random: () => number = Math.random,
              private readonly store: Pick<Storage, 'getItem' | 'setItem'> | null = safeStorage()) {
    this.recent = readRecent(store);
  }

  /**
   * Tire une formule du genre `kind` parmi celles que `available` accepte (clip chargé…), ou `null`.
   */
  pick(kind: FormulaKind, available: (id: string) => boolean = () => true): Formula | null {
    const pool = FORMULAS.filter((f) => f.kind === kind && available(f.id));
    if (pool.length === 0) {
      return null;
    }
    const last = this.recent[this.recent.length - 1];
    const window = this.recent.slice(-RECENT_WINDOW);
    const monsieurRecent = window.some((id) => FORMULAS.some((f) => f.id === id && hasMonsieur(f)));
    const allowMonsieur = !monsieurRecent && this.random() < MONSIEUR_RATE;
    const tiers = [
      pool.filter((f) => !window.includes(f.id) && (allowMonsieur || !hasMonsieur(f))),
      pool.filter((f) => !window.includes(f.id) && !hasMonsieur(f)),
      // Plutôt « Monsieur » (s'il n'a pas été dit récemment) qu'une formule récente.
      pool.filter((f) => !window.includes(f.id)),
      pool.filter((f) => f.id !== last),
    ];
    const candidates = tiers.find((tier) => tier.length > 0);
    if (!candidates) {
      return null;
    }
    const formula = candidates[Math.min(candidates.length - 1, Math.floor(this.random() * candidates.length))];
    this.recent = [...this.recent, formula.id].slice(-RECENT_WINDOW);
    try {
      this.store?.setItem(FORMULA_HISTORY_KEY, JSON.stringify(this.recent));
    } catch {
      // Stockage indisponible : la mémoire vaut pour cette ouverture.
    }
    return formula;
  }

  /** Les dernières formules dites (la plus récente en dernier). */
  get history(): readonly string[] {
    return this.recent;
  }
}

/**
 * L'attente d'un échange : au plus **une** formule, et **aucune** si la réponse est déjà là. `begin()` ouvre un
 * échange, `answered()` dit que la première phrase est prête, `take()` rend vrai au plus une fois par échange.
 */
export class ExchangeFiller {
  private used = false;
  private ready = false;

  begin(): void {
    this.used = false;
    this.ready = false;
  }

  answered(): void {
    this.ready = true;
  }

  take(): boolean {
    if (this.used || this.ready) {
      return false;
    }
    this.used = true;
    return true;
  }
}

function safeStorage(): Pick<Storage, 'getItem' | 'setItem'> | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

function readRecent(store: Pick<Storage, 'getItem'> | null): string[] {
  try {
    const parsed: unknown = JSON.parse(store?.getItem(FORMULA_HISTORY_KEY) ?? '[]');
    return Array.isArray(parsed)
      ? parsed.filter((id): id is string => typeof id === 'string' && FORMULAS.some((f) => f.id === id))
          .slice(-RECENT_WINDOW)
      : [];
  } catch {
    return [];
  }
}
