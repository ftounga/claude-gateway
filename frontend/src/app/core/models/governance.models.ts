/**
 * Le catalogue de gouvernance, côté écran (F-51 / SF-51-05).
 *
 * Ces types suivent exactement ce que la gateway rend : ils ne réinterprètent rien. Le seul endroit
 * où l'écran ajoute du sens est l'annonce faite **avant** l'activation — voir `GovernanceDepositPlan`.
 */

/** Genre d'un fichier apporté par un paquet. Le mot sert à l'annonce, pas au mécanisme. */
export type GovernanceFileKind = 'SKILL' | 'TEMPLATE';

/** Un fichier qu'un paquet déposerait : **où**, et de quelle nature. Jamais son contenu. */
export interface GovernanceFile {
  path: string;
  kind: GovernanceFileKind;
}

/** Un contrôle qu'un paquet branche sur les points d'accroche de la boucle. */
export interface GovernanceControl {
  id: string;
  kind: string | null;
  description: string;
  /** Faux quand le produit ne fournit plus ce contrôle : le paquet promet un verrou qui n'existe pas. */
  known: boolean;
}

/** Un paquet publié, tel qu'un utilisateur le lit. */
export interface GovernancePackage {
  id: string;
  slug: string;
  name: string;
  summary: string | null;
  version: number;
  rules: string | null;
  controls: GovernanceControl[];
  files: GovernanceFile[];
}

/** Une entrée du catalogue personnel. */
export interface GovernanceSelection {
  pkg: GovernancePackage;
  defaultApplied: boolean;
  /** Nombre de mes **postes** où ce paquet est actif. */
  activeProjects: number;
}

/** État d'application d'un paquet sur un poste. */
export type GovernanceActivationStatus = 'PENDING' | 'APPLIED';

/** Un paquet actif sur un poste — et donc sur tous ses dossiers (F-75). */
export interface GovernanceActivation {
  pkg: GovernancePackage;
  appliedVersion: number;
  /** Le paquet a été republié depuis : le poste applique une version antérieure. */
  outdated: boolean;
  status: GovernanceActivationStatus;
  appliedAt: string | null;
}

/** Un dossier rangé sous un poste, vu depuis la gouvernance. */
export interface GovernanceHostProject {
  id: string;
  name: string;
  path: string | null;
}

/**
 * Un poste **gouvernable** (F-75 / SF-75-01).
 *
 * `ref` est ce qui s'écrit dans une URL : un identifiant, ou le mot réservé `hosted`. `id` reste
 * **nul** pour le poste « Hébergé » — décision F-71 : ce poste est une vue, il n'a pas
 * d'identifiant.
 */
export interface GovernanceHostSummary {
  ref: string;
  id: string | null;
  name: string;
  virtual: boolean;
  projects: number;
  active: number;
}

/** Ce qui s'applique à un poste, ce qui pourrait s'y appliquer, et les dossiers concernés. */
export interface GovernanceHost {
  ref: string;
  id: string | null;
  name: string;
  virtual: boolean;
  projects: GovernanceHostProject[];
  active: GovernanceActivation[];
  available: GovernancePackage[];
}

/** Ce qui arrivera — ou est arrivé — à un fichier. */
export type GovernanceDepositAction = 'CREATE' | 'KEEP' | 'UNKNOWN';

/** Une ligne de l'annonce : un fichier, et son sort dans un dossier donné. */
export interface GovernanceDepositEntry {
  path: string;
  kind: GovernanceFileKind;
  action: GovernanceDepositAction;
}

/** Ce qu'un paquet fera dans **un** dossier du poste. `readable` à faux : machine éteinte. */
export interface GovernanceProjectDepositPlan {
  workspaceId: string;
  name: string;
  path: string | null;
  readable: boolean;
  entries: GovernanceDepositEntry[];
}

/**
 * L'annonce faite **avant** qu'un paquet n'écrive quoi que ce soit.
 *
 * Depuis F-75, « où » est au pluriel : on active sur un **poste**, et les fichiers se posent dans
 * **chacun de ses dossiers**. L'annonce est donc faite dossier par dossier.
 */
export interface GovernanceDepositPlan {
  packageId: string;
  slug: string;
  version: number;
  hostRef: string;
  hostName: string;
  files: GovernanceFile[];
  projects: GovernanceProjectDepositPlan[];
  rules: boolean;
  controls: number;
}

/** Ce qu'un dossier porte **aujourd'hui** sous le chemin d'un fichier du paquet. */
export interface GovernanceProjectFile {
  workspaceId: string;
  name: string;
  readable: boolean;
  exists: boolean;
  identical: boolean;
  content: string | null;
  truncated: boolean;
}

/**
 * Un fichier du paquet, **ouvert avant d'accepter** (F-75 / SF-75-02).
 *
 * Le dépôt n'écrase jamais : quand un fichier existe déjà, c'est **lui** qui restera. Le
 * différentiel est calculé à l'écran, à partir de ces deux contenus.
 */
export interface GovernanceFileComparison {
  path: string;
  kind: GovernanceFileKind;
  content: string;
  truncated: boolean;
  projects: GovernanceProjectFile[];
  omitted: number;
}

/** Corps du geste « retenir » / « changer le drapeau ». */
export interface GovernanceSelectionRequest {
  defaultApplied: boolean;
}
