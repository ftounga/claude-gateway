/**
 * **Paliers de l'en-tête du terminal** (F-200 / SF-200-01).
 *
 * <p>Sur l'écran d'un portable, l'en-tête du terminal portait à plat le fil d'Ariane, cinq pastilles
 * d'état et jusqu'à neuf boutons texte : tout se chevauchait. La barre se met désormais en PALIERS,
 * décidés par la LARGEUR RÉELLE de l'en-tête (le terminal peut vivre dans une grille ou une
 * mosaïque : la fenêtre ne suffit pas) :</p>
 *
 * <ul>
 *   <li>`full` — grand écran : tout est visible, comme avant ;</li>
 *   <li>`compact` — les actions PRIMAIRES restent en clair (Interrompre, « N à faire »,
 *       Validation) ; les SECONDAIRES (instructions, Fichiers, Publier, Réinitialiser, Compacter,
 *       Nouveau départ, Quitter) passent dans le menu ⋯ ; le dossier de la machine ne garde que son
 *       icône (chemin dans l'infobulle) ;</li>
 *   <li>`tight` — écran de portable étroit : comme `compact`, et les pastilles d'état et les
 *       actions primaires ne gardent que leur icône (l'état reste dit par l'icône et la couleur, le
 *       libellé par l'infobulle et le nom accessible) ;</li>
 *   <li>`narrow` — l'en-tête téléphone de F-158 / SF-158-12, inchangé : tout passe dans le menu ⋯.</li>
 * </ul>
 *
 * <p>Fonction pure, sans DOM : le composant lui passe la largeur relevée par un `ResizeObserver`.
 * Une largeur inconnue (0 : élément pas encore mis en page, tests sans layout) laisse le palier
 * complet — le rendu historique.</p>
 *
 * <p>Seuils MESURÉS en rendu réel (ChromeHeadless, `terminal-en-tete-paliers.spec.ts`) avec
 * l'en-tête le plus chargé qui puisse exister : chaque palier tient sur une ligne dès son seuil.</p>
 */
export type TerminalBarTier = 'full' | 'compact' | 'tight' | 'narrow';

/** Jusqu'à cette largeur d'en-tête, c'est l'en-tête téléphone (point de rupture 819 px de la charte). */
export const TERMINAL_BAR_NARROW_MAX = 819;

/** À partir de cette largeur, les libellés des pastilles et des actions primaires reviennent. */
export const TERMINAL_BAR_COMPACT_MIN = 1680;

/** À partir de cette largeur, tout le bandeau d'actions tient en clair. */
export const TERMINAL_BAR_FULL_MIN = 2880;

export function terminalBarTier(width: number, viewportNarrow: boolean): TerminalBarTier {
  if (viewportNarrow) {
    return 'narrow';
  }
  if (!(width > 0)) {
    return 'full';
  }
  if (width <= TERMINAL_BAR_NARROW_MAX) {
    return 'narrow';
  }
  if (width < TERMINAL_BAR_COMPACT_MIN) {
    return 'tight';
  }
  return width >= TERMINAL_BAR_FULL_MIN ? 'full' : 'compact';
}
