/**
 * COMMANDES SLASH « À NOTRE SAUCE » DU COMPOSER (F-165 / SF-165-01) — le SOCLE.
 *
 * Deux familles de `/`-commandes coexistent dans le composer du terminal :
 *
 *  - les **macros de prompt** (F-121, `slash-commands.ts`) : `/revue`… sont **expansées en un prompt
 *    imposé puis ENVOYÉES au modèle** — elles consomment un tour ;
 *  - les **commandes vue/action** (F-165, CE fichier) : `/aide`, puis `/cout`, `/contexte`… sont
 *    **dispatchées côté client** et **affichent un panneau**, **sans AUCUN tour modèle**. *Vérifier
 *    son coût ne doit rien coûter* (règle fondatrice du cadrage F-165).
 *
 * Ce module est **frontend PUR** — aucune dépendance Angular, aucun appel réseau. Il décrit le
 * **registre extensible** que les SF-165-02 → 06 viennent enrichir, et fournit les fonctions
 * d'interception/complétion et le modèle de panneau rendu dans le fil.
 *
 * ### Étendre le registre (SF-165-02 → 06)
 * Pour ajouter une commande :
 *  1. ajouter une entrée à {@link SLASH_PANEL_COMMANDS} (nom, titre, description, {@link SlashPanelKind},
 *     `takesArgument`, `icon`, `panelKind`) ;
 *  2. ajouter une branche de **dispatch** dans `AtelierTerminalComponent.dispatchPanelCommand`
 *     (une VUE y interroge des endpoints REST en lecture — jamais la boucle modèle) ;
 *  3. ajouter un `@case (panelKind)` de **corps de panneau** dans le gabarit du terminal (un composant
 *     de corps dédié, comme `AtelierSlashHelpComponent` pour `/aide`).
 */

/** Famille d'une commande slash F-165, pour le badge du panneau et de l'autocomplétion. */
export type SlashPanelKind = 'view' | 'action' | 'meta';

/** Libellé lisible d'une famille (jamais du jargon). */
export function slashPanelKindLabel(kind: SlashPanelKind): string {
  switch (kind) {
    case 'view':
      return 'Vue';
    case 'action':
      return 'Action';
    case 'meta':
      return 'Aide';
  }
}

/**
 * Une commande du registre F-165. `name` est le jeton sans le `/`. `panelKind` identifie le **corps de
 * panneau** à rendre (le « composant/handler de rendu » déclaré par la commande).
 */
export interface SlashPanelCommand {
  /** Jeton sans le `/`, en minuscules, `[a-z0-9-]+` (ex. `aide`). */
  readonly name: string;
  /** Libellé court pour le menu (ex. `/aide`). */
  readonly title: string;
  /** Description d'une ligne, affichée dans le menu et l'aide. */
  readonly description: string;
  /** Vue (lecture seule, gratuite), Action (rappelle un endpoint existant) ou Meta (`/aide`). */
  readonly kind: SlashPanelKind;
  /** Vrai si la commande attend un argument libre (ex. `/rappel <terme>`, SF-165-06). */
  readonly takesArgument: boolean;
  /** Indice d'argument pour l'autocomplétion (ex. `<terme>`), si `takesArgument`. */
  readonly argHint?: string;
  /** Icône Material de l'en-tête du panneau. */
  readonly icon: string;
  /** Identifiant du corps de panneau à rendre (`@switch` du gabarit). Ex. `help`. */
  readonly panelKind: string;
}

/**
 * Registre FERMÉ des commandes slash F-165. SF-165-01 n'en pose qu'une : `/aide`. Les SF suivantes y
 * ajoutent `/cout`, `/contexte`, `/quota`, `/budget`, `/poste`, `/sujet`, `/compacter`, `/nouveau`,
 * `/rappel`.
 */
export const SLASH_PANEL_COMMANDS: readonly SlashPanelCommand[] = [
  {
    name: 'cout',
    title: '/cout',
    description: 'L’économie du fil : coût, cache, contexte, tendance',
    kind: 'view',
    takesArgument: false,
    icon: 'savings',
    panelKind: 'cost',
  },
  {
    name: 'contexte',
    title: '/contexte',
    description: 'L’état mémoire : contexte vivant, seuil, résumé ancré, rappel',
    kind: 'view',
    takesArgument: false,
    icon: 'memory',
    panelKind: 'context',
  },
  {
    name: 'aide',
    title: '/aide',
    description: 'Liste les commandes slash disponibles',
    kind: 'meta',
    takesArgument: false,
    icon: 'help_outline',
    panelKind: 'help',
  },
];

/** Un jeton en cours de frappe : `/` puis un nom partiel, SANS espace encore (`/ai`). */
const TYPING_TOKEN = /^\/([a-z0-9-]*)$/i;

/** Une commande complète : `/nom` puis, en option, des arguments libres. */
const FULL_COMMAND = /^\/([a-z0-9-]+)(?:\s+([\s\S]*))?$/i;

/** Retrouve une commande F-165 du registre par son nom (insensible à la casse). */
export function findPanelCommand(name: string): SlashPanelCommand | undefined {
  const key = name.trim().toLowerCase();
  return SLASH_PANEL_COMMANDS.find((command) => command.name === key);
}

/**
 * Les commandes F-165 à proposer pour le brouillon courant, filtrées en préfixe.
 *
 * Renvoie `[]` dès que le brouillon ne « tape pas une commande » : rien qui ne commence par `/`, ou un
 * jeton déjà suivi d'un espace. `/` seul renvoie tout le registre.
 */
export function panelCommandSuggestions(draft: string): SlashPanelCommand[] {
  const match = TYPING_TOKEN.exec(draft ?? '');
  if (!match) {
    return [];
  }
  const prefix = match[1].toLowerCase();
  return SLASH_PANEL_COMMANDS.filter((command) => command.name.startsWith(prefix));
}

/** Le résultat d'une interception : la commande F-165 reconnue et son argument libre (éventuel). */
export interface ParsedPanelCommand {
  readonly command: SlashPanelCommand;
  readonly arg: string;
}

/**
 * Interception à l'envoi : si le brouillon EST une commande F-165 **connue**, renvoie la commande et
 * son argument ; sinon `null` (le brouillon suit alors le chemin habituel — macro F-121 ou message).
 *
 * C'est le point qui **garantit qu'une vue/action ne passe jamais par la boucle modèle** : appelé au
 * tout début de `submit()`, un résultat non nul court-circuite `send`.
 */
export function parsePanelCommand(draft: string): ParsedPanelCommand | null {
  const match = FULL_COMMAND.exec((draft ?? '').trim());
  if (!match) {
    return null;
  }
  const command = findPanelCommand(match[1]);
  if (!command) {
    return null;
  }
  return { command, arg: (match[2] ?? '').trim() };
}

// ------------------------------------------------------------------ Modèle de panneau (rendu du fil)

/** Une entrée de l'aide : une commande décrite pour le panneau `/aide`. */
export interface SlashPanelHelpEntry {
  /** Jeton complet, `/aide`. */
  readonly command: string;
  /** Libellé de famille (« Vue » / « Action » / « Aide »). */
  readonly kindLabel: string;
  /** Description d'une ligne. */
  readonly description: string;
}

/**
 * État de chargement d'un panneau qui interroge la gateway (F-165 / SF-165-02) : une **VUE** appelle un
 * endpoint REST de **lecture** (jamais la boucle modèle), et le panneau vit ces trois états.
 */
export type CostPanelState = 'loading' | 'ready' | 'error';

/** La décomposition d'un fil en trois postes réels (F-165 / SF-165-02). Somme des parts = 100 %. */
export interface ThreadCostBreakdown {
  /** Coût d'écriture (entrée neuve + écriture de cache), dans la devise du fil. */
  readonly writeEur: number;
  /** Part d'écriture (%). */
  readonly writePercent: number;
  /** Coût de lecture de cache. */
  readonly readEur: number;
  /** Part de lecture (%). */
  readonly readPercent: number;
  /** Coût de sortie. */
  readonly outputEur: number;
  /** Part de sortie (%). */
  readonly outputPercent: number;
}

/**
 * L'**économie du fil courant** (F-165 / SF-165-02) telle que le panneau `/cout` la rend. Reflet exact
 * du DTO backend `ThreadCostSummaryResponse` : des volumes et des montants, **jamais** un contenu.
 */
export interface ThreadCostSummary {
  /** Devise d'affichage (ex. `EUR`). */
  readonly currency: string;
  /** Coût cumulé du fil. */
  readonly cumulativeEur: number;
  /** Coût du dernier tour. */
  readonly lastTurnEur: number;
  /** Nombre de tours facturés du fil. */
  readonly turnCount: number;
  /** Décomposition écriture / lecture / sortie. */
  readonly breakdown: ThreadCostBreakdown;
  /** Part de l'entrée servie à chaud depuis le cache (%). */
  readonly hotCachePercent: number;
  /** Taille du contexte vivant (tokens). */
  readonly contextTokens: number;
  /** Ce contexte en « pages » (langage classeur). */
  readonly contextPages: number;
  /** Tours vivants (rejouables). */
  readonly liveTurns: number;
  /** Tours rangés (repliés). */
  readonly foldedTurns: number;
  /** Coût par tour des derniers tours (mini-tendance, du plus ancien au plus récent). */
  readonly trendEur: readonly number[];
}

/** Le budget applicable au fil, quand il est déjà lisible (réutilise `WeeklyBudgetService`, admin). */
export interface ThreadCostBudget {
  /** Nom du client (poste), pour situer le budget. */
  readonly hostName: string | null;
  /** Plafond de la semaine. */
  readonly budgetEur: number;
  /** Dépensé sur la semaine. */
  readonly spentEur: number;
}

/**
 * État de chargement du panneau `/contexte` (F-165 / SF-165-03) : une **VUE** appelle un endpoint REST de
 * **lecture** (jamais la boucle modèle), et le panneau vit ces trois états.
 */
export type ContextPanelState = 'loading' | 'ready' | 'error';

/**
 * L'**état mémoire du fil courant** (F-165 / SF-165-03) tel que le panneau `/contexte` le rend. Reflet
 * exact du DTO backend `ThreadContextSummaryResponse` : des volumes, des drapeaux et un seuil, **jamais**
 * un contenu (on sait que le fil a un résumé ancré, jamais le résumé lui-même).
 */
export interface ThreadContextSummary {
  /** Taille du contexte vivant (tokens). */
  readonly contextTokens: number;
  /** Ce contexte en « pages » (langage classeur). */
  readonly contextPages: number;
  /** Tours vivants (rejouables). */
  readonly liveTurns: number;
  /** Tours rangés (repliés). */
  readonly foldedTurns: number;
  /** Le fil a-t-il un résumé ancré (mémoire longue de la compaction douce) ? */
  readonly hasAnchoredSummary: boolean;
  /** La compaction automatique est-elle active ? */
  readonly compactionEnabled: boolean;
  /** Seuil de tokens au-delà duquel la compaction se déclenche. */
  readonly triggerTokens: number;
  /** Ce seuil en « pages ». */
  readonly triggerPages: number;
  /** Progression du contexte vivant vers le seuil (0–100 %). */
  readonly fillPercent: number;
  /** Nombre de messages récents gardés entiers au rejeu. */
  readonly keepRecentTurns: number;
  /** Le rappel sémantique (embeddings) est-il actif ? Le rappel par mot-clé couvre toujours tout le fil. */
  readonly recallSemantic: boolean;
}

/**
 * Un panneau **local** rendu dans le fil du terminal (résultat d'une commande slash F-165). Purement
 * de l'affichage : jamais dans l'historique envoyé au modèle.
 */
export interface SlashPanel {
  /** Identifiant local unique (pour le suivi de la liste et la fermeture). */
  readonly id: string;
  /** Jeton complet à afficher dans l'en-tête (`/aide`). */
  readonly command: string;
  /** Titre du panneau. */
  readonly title: string;
  /** Icône Material de l'en-tête. */
  readonly icon: string;
  /** Famille (badge). */
  readonly kind: SlashPanelKind;
  /** Corps à rendre (`@switch`). Ex. `help`, `cost`. */
  readonly panelKind: string;
  /** Argument libre saisi (éventuel), pour les commandes qui en prennent. */
  readonly arg?: string;
  /** Entrées d'aide, présentes uniquement pour `panelKind === 'help'`. */
  readonly help?: readonly SlashPanelHelpEntry[];
  /** État de la lecture, pour `panelKind === 'cost'` : `loading` → `ready`/`error`. */
  readonly costState?: CostPanelState;
  /** L'économie du fil, présente pour `panelKind === 'cost'` en état `ready`. */
  readonly cost?: ThreadCostSummary;
  /** État de la lecture, pour `panelKind === 'context'` : `loading` → `ready`/`error`. */
  readonly contextState?: ContextPanelState;
  /** L'état mémoire du fil, présent pour `panelKind === 'context'` en état `ready`. */
  readonly context?: ThreadContextSummary;
}

/** Construit les entrées d'aide à partir du registre (toutes les commandes F-165 disponibles). */
export function buildHelpEntries(): SlashPanelHelpEntry[] {
  return SLASH_PANEL_COMMANDS.map((command) => ({
    command: command.title,
    kindLabel: slashPanelKindLabel(command.kind),
    description: command.description,
  }));
}

/**
 * Construit le panneau à afficher pour une commande. SF-165-01 ne rend que `/aide` (`panelKind`
 * `help`) ; les SF suivantes enrichiront leur propre `panelKind` avec leurs données de lecture.
 */
export function buildPanel(command: SlashPanelCommand, arg: string, id: string): SlashPanel {
  const base: SlashPanel = {
    id,
    command: `/${command.name}`,
    title: command.title,
    icon: command.icon,
    kind: command.kind,
    panelKind: command.panelKind,
    ...(arg ? { arg } : {}),
  };
  if (command.panelKind === 'help') {
    return { ...base, title: 'Commandes disponibles', help: buildHelpEntries() };
  }
  if (command.panelKind === 'cost') {
    // La donnée arrive d'un appel REST de lecture (jamais du modèle) : le panneau naît en chargement,
    // et le dispatch le fera passer en `ready`/`error`. *Vérifier son coût ne doit rien coûter.*
    return { ...base, title: 'Économie du fil', costState: 'loading' };
  }
  if (command.panelKind === 'context') {
    // Même règle : la donnée arrive d'un GET de lecture (jamais du modèle). *Vérifier sa mémoire ne
    // doit rien coûter.*
    return { ...base, title: 'État mémoire du fil', contextState: 'loading' };
  }
  return base;
}
