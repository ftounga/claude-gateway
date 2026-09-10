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
  activeProjects: number;
}

/** État d'application d'un paquet sur un projet. */
export type GovernanceActivationStatus = 'PENDING' | 'APPLIED';

/** Un paquet actif sur un projet. */
export interface GovernanceActivation {
  pkg: GovernancePackage;
  appliedVersion: number;
  /** Le paquet a été republié depuis : le projet applique une version antérieure. */
  outdated: boolean;
  status: GovernanceActivationStatus;
  appliedAt: string | null;
}

/** Ce qui s'applique à un projet, et ce qui pourrait s'y appliquer. */
export interface GovernanceProject {
  workspaceId: string;
  active: GovernanceActivation[];
  available: GovernancePackage[];
}

/** Ce qui arrivera — ou est arrivé — à un fichier. */
export type GovernanceDepositAction = 'CREATE' | 'KEEP' | 'UNKNOWN';

/** Une ligne de l'annonce : un fichier, et son sort. */
export interface GovernanceDepositEntry {
  path: string;
  kind: GovernanceFileKind;
  action: GovernanceDepositAction;
}

/**
 * L'annonce faite **avant** qu'un paquet n'écrive quoi que ce soit.
 *
 * C'est l'exigence centrale de F-51 : un paquet écrit sur la machine de l'utilisateur, donc l'écran
 * dit **quoi** et **où** avant. `readable` à faux signifie que le projet n'a pas pu être lu — chaque
 * ligne est alors indéterminée, et on ne le cache pas.
 */
export interface GovernanceDepositPlan {
  packageId: string;
  slug: string;
  version: number;
  readable: boolean;
  entries: GovernanceDepositEntry[];
  rules: boolean;
  controls: number;
}

/** Corps du geste « retenir » / « changer le drapeau ». */
export interface GovernanceSelectionRequest {
  defaultApplied: boolean;
}
