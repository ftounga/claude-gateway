import { GovernanceControl, GovernanceFileKind } from '../core/models/governance.models';

/**
 * Le catalogue vu par l'**admin** (F-51 / SF-51-06) : brouillons compris, et **contenu des fichiers
 * compris** — c'est lui qui les rédige.
 */

/** Un fichier apporté, avec son contenu. Réservé à l'admin. */
export interface GovernanceFileDetail {
  path: string;
  kind: GovernanceFileKind;
  content: string;
  /**
   * Vrai si le paquet revendique ce fichier comme un **artefact généré** (F-96 / SF-96-01) : il est
   * **mis à jour** sur les postes où il est resté exactement celui qui y a été déposé.
   *
   * Un fichier que l'utilisateur a touché redevient du contenu utilisateur : il n'est plus jamais
   * écrasé, quelle que soit cette déclaration. Absent, le drapeau vaut **vrai** — un paquet publie
   * des artefacts.
   */
  generated?: boolean;
}

/** Un paquet tel que l'admin le lit : tout, y compris ce qui n'est pas publié. */
export interface GovernancePackageAdmin {
  id: string;
  slug: string;
  name: string;
  summary: string | null;
  rules: string | null;
  controls: GovernanceControl[];
  files: GovernanceFileDetail[];
  version: number;
  published: boolean;
  publishedAt: string | null;
  updatedAt: string;
}

/**
 * Le contenu soumis à la création ou à la modification.
 *
 * À la modification, ce corps **remplace intégralement** le paquet, fichiers compris, et le `slug`
 * est ignoré : il est immuable.
 */
export interface GovernancePackageDraft {
  slug: string;
  name: string;
  summary: string | null;
  rules: string | null;
  controlIds: string[];
  files: GovernanceFileDetail[];
}
