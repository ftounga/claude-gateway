import { AtelierStreamAction, AtelierTerminalBlock } from '../../core/models/atelier.models';

/**
 * Convertit les étapes de la **boucle maison** (`chat/stream`, événements `action` et `output`) en
 * blocs de transcription terminal (F-39 / SF-39-08, décision D-L4-5).
 *
 * <p>Les deux flux de l'Atelier ne parlent pas la même langue : le flux d'agent (Managed Agents)
 * émet des commandes et des résultats déjà appariés par `toolUseId`, quand la boucle maison émet
 * des étapes typées dont la sortie s'accumule sur la dernière. Faire converger les deux côté backend
 * aurait touché deux contrats en service pour un gain d'écran ; l'adaptation tient ici, dans une
 * fonction pure et testable seule.</p>
 *
 * <p>Chaque étape devient un bloc. L'en-tête reprend l'argument de l'étape — pour un `bash`, c'est
 * la commande elle-même — et retombe sur le type quand il n'y a rien d'autre à montrer : un type
 * inconnu reste présentable plutôt que déguisé en lecture (le type est une chaîne libre, contrat de
 * messages runner §3).</p>
 */
export function chatStepsToBlocks(steps: AtelierStreamAction[]): AtelierTerminalBlock[] {
  return steps.map((step, index) => ({
    tool: step.type,
    command: stepCommand(step),
    // La boucle maison n'expose pas d'identifiant d'appel : le rang de l'étape suffit à distinguer
    // deux blocs dans une transcription, et rien d'autre ne s'y apparie.
    toolUseId: `chat-${index}`,
    threadId: null,
    output: step.output ?? '',
    // Une sortie vide n'est pas une absence de sortie : `read` n'en produit jamais, `bash` peut en
    // produire une vide. La distinction change ce qui s'affiche sous l'en-tête.
    hasOutput: step.output !== undefined,
    error: false,
    expanded: false,
  }));
}

/**
 * En-tête d'un bloc issu d'une étape. `path` porte le chemin lu ou écrit, et la **commande** pour
 * une étape `bash` (SF-38-07) : c'est donc lui qu'on montre, préfixé du geste quand il est utile.
 */
function stepCommand(step: AtelierStreamAction): string {
  switch (step.type) {
    case 'read':
      return step.path ? `lecture ${step.path}` : 'lecture';
    case 'write':
      return step.path ? `écriture ${step.path}` : 'écriture';
    case 'list':
      return 'liste des fichiers';
    case 'search':
      return step.path ? `recherche « ${step.path} »` : 'recherche';
    default:
      return step.path ?? step.type;
  }
}
