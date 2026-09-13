/**
 * **Les pages** (F-109) : des documents HTML rendus par l'agent, rangés et servis par la gateway.
 */

/** L'espace où une page est rangée. */
export type PageSpace = 'FORGE' | 'VIGIE';

/** Une page telle que l'API la rend (`GET /api/pages/{id}`). */
export interface PageSummary {
  id: string;
  title: string;
  description: string | null;
  space: PageSpace;
  hostId: string | null;
  workspaceId: string | null;
  currentVersion: number;
  createdAt: string;
  updatedAt: string;
  /**
   * Adresse de lecture de la version courante pour une `iframe` : un **ticket court** sur `/api/p/…`,
   * jamais le jeton de l'application. À redemander à chaque ouverture — il expire.
   */
  viewUrl: string;
}

/** Une version conservée d'une page (`GET /api/pages/{id}/versions`, F-109 / SF-109-04). */
export interface PageVersionSummary {
  version: number;
  sizeBytes: number;
  attachmentCount: number;
  createdAt: string;
}
