/**
 * SLASH-COMMANDS DU COMPOSER (F-121 / SF-121-23) — parité Claude Code.
 *
 * Un parseur **déterministe** : `/nom` (éventuellement suivi d'arguments libres) est expansé en un
 * **prompt imposé** issu d'un catalogue FERMÉ, au lieu de laisser le seul modèle deviner quel
 * prompt/skill appliquer.
 *
 * Frontend PUR — aucune dépendance Angular, aucun appel serveur. Le prompt imposé devient un simple
 * **message utilisateur** (volatil) : il ne touche NI le préfixe système stable, NI le cache de
 * prompt (F-134), NI `AIProvider`. Le modèle reste seul moteur (Gateway-First / Provider-First).
 */

/** Une commande du catalogue. `name` est le jeton sans le `/`. `prompt` est le texte imposé. */
export interface SlashCommand {
  /** Jeton sans le `/`, en minuscules, `[a-z0-9-]+` (ex. `revue`). */
  readonly name: string;
  /** Libellé court pour le menu. */
  readonly title: string;
  /** Description d'une ligne, affichée dans le menu. */
  readonly description: string;
  /** Le prompt imposé, injecté tel quel dans le brouillon à l'envoi. */
  readonly prompt: string;
}

/**
 * Catalogue FERMÉ. Chaque `prompt` est une consigne autonome, en français, dans le ton produit
 * (concision, `chemin:ligne`, pas de préambule). Seule une commande présente ici est expansée ;
 * tout le reste part littéralement.
 */
export const SLASH_COMMANDS: readonly SlashCommand[] = [
  {
    name: 'revue',
    title: '/revue',
    description: 'Revue de code des modifications en cours',
    prompt:
      'Passe en revue les modifications en cours de ce projet. Signale les bugs, régressions et ' +
      'problèmes de qualité, chacun avec sa référence chemin:ligne, du plus grave au plus léger. ' +
      'Ne modifie rien sans me le dire.',
  },
  {
    name: 'tests',
    title: '/tests',
    description: 'Lancer les tests et réparer ce qui échoue',
    prompt:
      'Lance les tests du projet, montre-moi le résultat, puis répare ce qui échoue jusqu’à ce ' +
      'que la suite passe. Relance les tests pour prouver que c’est vert avant de conclure.',
  },
  {
    name: 'corrige',
    title: '/corrige',
    description: 'Diagnostiquer et corriger un bug',
    prompt:
      'Diagnostique la cause du problème décrit, corrige-le, puis exécute la commande ou le test ' +
      'qui prouve que c’est réparé. Cite les fichiers touchés en chemin:ligne.',
  },
  {
    name: 'explique',
    title: '/explique',
    description: 'Expliquer une partie du code',
    prompt:
      'Explique comment fonctionne la partie du code indiquée : son rôle, son flux principal et ses ' +
      'points d’attention. Cite les fichiers en chemin:ligne. N’écris aucun code.',
  },
  {
    name: 'documente',
    title: '/documente',
    description: 'Documenter le code ou les changements',
    prompt:
      'Documente la partie du code indiquée (ou, à défaut, les modifications en cours) : commentaires ' +
      'utiles et, si pertinent, mise à jour de la documentation du projet. Reste concis.',
  },
  {
    name: 'resume',
    title: '/resume',
    description: 'Résumer l’état du projet et des changements récents',
    prompt:
      'Résume l’état actuel du projet et les changements récents : ce qui est fait, ce qui est vérifié, ' +
      'et ce qui reste. Reste bref.',
  },
];

/** Un jeton en cours de frappe : `/` puis un nom partiel, SANS espace encore (`/rev`). */
const TYPING_TOKEN = /^\/([a-z0-9-]*)$/i;

/** Une commande complète : `/nom` puis, en option, des arguments libres. */
const FULL_COMMAND = /^\/([a-z0-9-]+)(?:\s+([\s\S]*))?$/i;

/** Retrouve une commande du catalogue par son nom (insensible à la casse). */
export function findSlashCommand(name: string): SlashCommand | undefined {
  const key = name.trim().toLowerCase();
  return SLASH_COMMANDS.find((command) => command.name === key);
}

/**
 * Les commandes à proposer pour le brouillon courant, filtrées en préfixe.
 *
 * Renvoie `[]` dès que le brouillon ne « tape pas une commande » : rien qui ne commence par `/`,
 * ou un jeton déjà suivi d'un espace (l'utilisateur a fini de nommer la commande). `/` seul renvoie
 * tout le catalogue.
 */
export function slashSuggestions(draft: string): SlashCommand[] {
  const match = TYPING_TOKEN.exec(draft ?? '');
  if (!match) {
    return [];
  }
  const prefix = match[1].toLowerCase();
  return SLASH_COMMANDS.filter((command) => command.name.startsWith(prefix));
}

/**
 * Expansion DÉTERMINISTE d'un brouillon en son prompt imposé, ou `null` si le brouillon n'est pas
 * une commande **connue**. Les arguments libres tapés après le nom sont ajoutés en contexte.
 */
export function expandSlashCommand(draft: string): string | null {
  const match = FULL_COMMAND.exec((draft ?? '').trim());
  if (!match) {
    return null;
  }
  const command = findSlashCommand(match[1]);
  if (!command) {
    return null;
  }
  const args = (match[2] ?? '').trim();
  return args ? `${command.prompt}\n\n${args}` : command.prompt;
}
