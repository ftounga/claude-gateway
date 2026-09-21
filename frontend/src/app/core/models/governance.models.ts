/**
 * Le catalogue de gouvernance, côté écran (F-51 / SF-51-05).
 *
 * Ces types suivent exactement ce que la gateway rend : ils ne réinterprètent rien. Le seul endroit
 * où l'écran ajoute du sens est l'annonce faite **avant** l'activation — voir `GovernanceDepositPlan`.
 */

/**
 * Genre d'un fichier apporté par un paquet.
 *
 * Depuis F-92, le genre décide **où** le fichier se pose : `SKILL` et `TEMPLATE` dans **chaque
 * projet** du poste, `MAP` **une seule fois, à la racine du poste** — là où vit la carte.
 */
export type GovernanceFileKind = 'SKILL' | 'TEMPLATE' | 'MAP';

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
/** L'état de la mémoire d'un poste (F-135 / SF-135-01) : apprend-il, ou pas ? */
export type HostMemoryState = 'ABSENT' | 'PENDING' | 'ACTIVE' | 'UNSUPPORTED';

export interface GovernanceHostSummary {
  ref: string;
  id: string | null;
  name: string;
  virtual: boolean;
  projects: number;
  active: number;
  /**
   * Apprend-il ? `ABSENT` : rien n'est activé. `PENDING` : activé, mais les fichiers ne sont pas
   * posés — l'état le plus trompeur, le poste semble gouverné de l'extérieur. `ACTIVE` : la carte
   * existe et grossit. `UNSUPPORTED` : poste « Hébergé », sans racine donc sans carte.
   */
  memory: HostMemoryState;
  /** Faits déjà accumulés sur sa carte — zéro tant qu'elle n'a pas été lue. */
  facts: number;
  /**
   * Nombre de paquets actifs sur ce poste dont une **version plus récente** existe (F-96).
   *
   * Rien ne se met à jour tout seul : ce compte est ce qui permet de **dire qu'une mise à jour
   * attend**, sur la liste des postes — sans lui, on ne le verrait que sur le poste déjà ouvert, et
   * personne n'irait voir les autres. Absent d'une gateway antérieure : traité comme `0`.
   */
  outdated?: number;
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

/**
 * Ce qui arrivera — ou est arrivé — à un fichier.
 *
 * Depuis F-96, il y a **cinq** issues et non trois, et les deux ajoutées sont exactement les deux
 * choses que le produit ne pouvait pas dire : `UPDATE` (artefact généré resté intact → **mis à
 * jour**) et `KEEP_LOCAL` (**modifié localement → conservé**). Un fichier conservé parce qu'il a
 * été modifié n'est pas la même chose qu'un fichier conservé parce qu'il était déjà bon : sans la
 * distinction, on ne sait jamais si sa correction est arrivée.
 */
export type GovernanceDepositAction = 'CREATE' | 'UPDATE' | 'KEEP' | 'KEEP_LOCAL' | 'UNKNOWN';

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
  /** Ce qui arrivera **à la racine du poste** — la carte (F-92). Absent des versions antérieures. */
  root?: GovernanceRootDepositPlan | null;
  projects: GovernanceProjectDepositPlan[];
  rules: boolean;
  controls: number;
}

/**
 * Ce qu'un paquet fera **à la racine du poste** — la carte (F-92 / SF-92-01).
 *
 * Distincte des dossiers parce que l'endroit l'est : les gabarits se posent dans **chaque** projet,
 * la carte **une fois**, à côté d'eux. `supported` à faux : ce poste n'est pas une machine, il n'a
 * pas de racine. `readable` à faux : la machine n'a pas répondu — **rien n'est écrit**.
 */
export interface GovernanceRootDepositPlan {
  supported: boolean;
  readable: boolean;
  message: string | null;
  entries: GovernanceDepositEntry[];
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

/** Une section d'un fichier de carte, et le nombre de **faits** qu'elle porte (F-92 / SF-92-02). */
export interface GovernanceMapSection {
  title: string;
  facts: number;
}

/**
 * Un fichier de carte, tel que l'écran le montre **sans ouvrir un terminal**.
 *
 * `present` et `readable` ne disent pas la même chose : un fichier absent est à reposer, un fichier
 * illisible est une machine à réparer. Une machine éteinte n'est jamais rendue comme une carte vide.
 */
export interface GovernanceMapFile {
  path: string;
  title: string;
  present: boolean;
  readable: boolean;
  sections: GovernanceMapSection[];
  facts: number;
  truncated: boolean;
  message: string | null;
}

/**
 * Un **gain** constaté sur un fichier de la carte (F-93 / SF-93-02).
 *
 * Volontairement pauvre : un fichier, un nombre, une date. « acces.md +3, il y a 2 j » se constate ;
 * une courbe se contemple.
 */
export interface GovernanceMapGain {
  path: string;
  title: string;
  gained: number;
  gainedAt: string;
}

/**
 * **Ce que la carte a gagné** (F-93 / SF-93-02).
 *
 * C'est la réponse mesurée à la phrase du PO — « à chaque projet, la connaissance de l'infra
 * augmente ». Le bloc est **absent** tant qu'il n'y a rien à dire : un « +0 » affiché chaque jour
 * serait pire que rien, il apprendrait qu'on ne gagne rien.
 */
export interface GovernanceMapGrowth {
  since: string | null;
  sinceFacts: number;
  gained: number;
  recent: GovernanceMapGain[];
}

/**
 * **Ce que la machine sait** — le relevé de la carte d'un poste (F-92 / SF-92-02).
 *
 * Trois « non » différents, parce que ce sont trois gestes différents : `supported` à faux (ce poste
 * n'est pas une machine), `governed` à faux (rien n'est activé), `readable` à faux (la machine n'a
 * pas répondu). `message` porte le geste correspondant.
 */
export interface GovernanceMap {
  hostRef: string;
  hostId: string | null;
  hostName: string;
  supported: boolean;
  governed: boolean;
  readable: boolean;
  message: string | null;
  files: GovernanceMapFile[];
  filesExpected: number;
  filesPresent: number;
  sections: number;
  facts: number;
  /** Ce que la carte a gagné depuis la première lecture (F-93 / SF-93-02) ; absent s'il n'y a rien à dire. */
  growth?: GovernanceMapGrowth | null;
}

/** Le **contenu exact** d'un fichier de carte, lu sur la machine (F-92 / SF-92-02). */
export interface GovernanceMapFileContent {
  path: string;
  title: string;
  present: boolean;
  content: string;
  truncated: boolean;
  message: string | null;
}

/**
 * Un constat d'**intégrité du poste** (F-95 / SF-95-03).
 *
 * `message` est repris **tel quel** de la gateway : il porte déjà son action corrective, et le
 * réécrire ici le ferait diverger au premier correctif.
 */
export interface GovernanceIntegriteConstat {
  /** L'identifiant stable de la règle — c'est par lui qu'un constat se retrouve. */
  rule: string;
  /** Ce sur quoi il porte : un fichier de carte, un projet, un dépôt. */
  target: string;
  /** Le constat **et** le geste. */
  message: string;
}

/**
 * **L'intégrité d'un poste** (F-95 / SF-95-03) — les deux niveaux, déjà séparés par la gateway.
 *
 * L'écran n'a donc rien à trier, et ne peut pas présenter un avertissement comme un refus.
 *
 * `inspected` à faux n'est **pas** « tout va bien » : c'est un poste dont on n'a rien lu — machine
 * muette, poste sans gouvernance, poste sans racine.
 */
export interface GovernanceIntegrite {
  hostRef: string;
  hostId: string | null;
  inspected: boolean;
  /** Ce qui empêche la gouvernance de fonctionner — bloquant côté modèle. */
  errors: GovernanceIntegriteConstat[];
  /** Ce qui la fait vieillir mal — informatif, jamais bloquant. */
  warnings: GovernanceIntegriteConstat[];
}
