/** L'espace de rangement d'une présentation (F-129), aligné sur les pages. */
export type PresentationSpace = 'FORGE' | 'VIGIE';

/**
 * Une présentation .pptx capturée depuis un terminal (F-129 / SF-129-02), telle que l'écran la lit.
 * `slideCount` est `null` tant que le rendu par slides (SF-129-03) n'a pas été produit.
 */
export interface Presentation {
  id: string;
  title: string;
  description: string | null;
  space: PresentationSpace;
  hostId: string | null;
  workspaceId: string | null;
  pptxBytes: number;
  slideCount: number | null;
  createdAt: string;
  updatedAt: string;
}
