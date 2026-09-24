/**
 * **Le diagnostic du produit** (F-156) : ce que la période dit de l'application elle-même.
 */

/** Ce qu'on conclut d'une capacité. */
export type CapabilityVerdict = 'ACTIVE' | 'DORMANTE' | 'DEBRANCHEE' | 'INDETERMINEE';

/** Une **hypothèse** tirée de la lecture du code — jamais un verdict (F-157). */
export interface SourceHypothesis {
  capabilityId: string;
  text: string;
  /** Vrai quand le code envoyé a été coupé. */
  truncated: boolean;
  model: string;
  inputTokens: number;
  outputTokens: number;
}

/** L'état d'une ligne de parité. Quatre, parce que trois mentiraient. */
export type ParityState = 'TENUE' | 'DORMANTE' | 'ABSENTE' | 'ECARTEE' | 'NON_OBSERVEE';

/** Un constat : ce qu'on conclut, et **où regarder**. */
export interface CapabilityFinding {
  capabilityId: string;
  name: string;
  verdict: CapabilityVerdict;
  /** Ce qui fonde le verdict, avec son chiffre. */
  why: string;
  /** Les fichiers où la capacité vit. */
  where: string[];
  /** La condition qu'elle attend, donc ce qu'il faut vérifier. */
  check: string | null;
  /** Le gain **calculé**, `null` quand il ne l'a pas été — jamais une estimation. */
  gainEur: number | null;
}

/** Une ligne de parité : présente ? déclenchée ? */
export interface ParityRow {
  referenceId: string;
  name: string;
  gives: string;
  state: ParityState;
  note: string;
}

/** Le rapport complet. */
export interface DiagnosticReport {
  from: string;
  to: string;
  /** Vrai quand la durée demandée a été ramenée aux bornes. */
  truncated: boolean;
  turns: number;
  projects: number;
  costEur: number;
  findings: CapabilityFinding[];
  /** Combien ont été écartés parce que leur gain calculé est sous le seuil. */
  discarded: number;
  /** Combien de capacités tournent — comptées, pas listées. */
  active: number;
  parity: ParityRow[];
  /** Les lignes prêtes à coller dans PRODUCT_SPEC.md, au statut `Candidate`. */
  specLines: string[];
  /** Vrai quand le code a été lu et les constats enrichis (F-157). */
  sourceRead: boolean;
  /** Ce qui s'est passé côté lecture — notamment le refus « ce projet n'est pas le dépôt ». */
  sourceNote: string | null;
}
