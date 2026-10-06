import {
  AtelierAction,
  AtelierEngine,
  AtelierRole,
  AtelierAgentStreamAction,
  AtelierPlanStep,
  AtelierStreamAction,
  AtelierTerminalBlock,
  AtelierQuestion,
  DepositedFileRef,
} from '../core/models/atelier.models';
import { AtelierFileDiffView } from './terminal/terminal-diff';

/**
 * Extensions texte/code acceptées à l'ajout d'un fichier depuis le PC (SF-28-13). Le workspace est
 * textuel (`readFile`/`writeFile` = String) : les binaires (PDF, image) passent par la bibliothèque
 * après OCR. Sert à la fois d'attribut `accept` et de garde-fou client anti-binaire.
 */
export const WORKSPACE_TEXT_EXTENSIONS: readonly string[] = [
  'txt', 'md', 'markdown', 'js', 'ts', 'tsx', 'jsx', 'java', 'py', 'json', 'html', 'htm', 'css',
  'scss', 'sass', 'less', 'xml', 'yml', 'yaml', 'sh', 'bash', 'go', 'rb', 'php', 'c', 'cpp', 'cc',
  'h', 'hpp', 'cs', 'kt', 'kts', 'rs', 'swift', 'sql', 'toml', 'ini', 'cfg', 'conf', 'properties',
  'env', 'gradle', 'csv', 'tsv', 'vue', 'svelte', 'pl', 'r', 'lua', 'dart', 'scala', 'gql',
  'graphql', 'proto', 'log', 'text',
];

/** Attribut `accept` du sélecteur de fichier PC, dérivé de {@link WORKSPACE_TEXT_EXTENSIONS}. */
export const WORKSPACE_TEXT_ACCEPT = WORKSPACE_TEXT_EXTENSIONS.map((e) => `.${e}`).join(',');

/** Élément du fil de conversation de l'Atelier : un tour (message + éventuelles actions fichier). */
export interface AtelierThreadItem {
  id: string;
  role: AtelierRole;
  content: string;
  actions: AtelierAction[];
  /** Chemins des fichiers modifiés par une session d'exécution (mode « Terminal », SF-28-11). */
  changedFiles?: string[];
  /**
   * Transcription terminal du tour d'exécution (F-30 SF-30-02) : commandes et sorties, conservées
   * dans le fil après la fin du run — sans quoi tout ce qu'on a vu défiler disparaît.
   */
  terminal?: AtelierTerminalBlock[];
  /**
   * Ce qu'a coûté le tour (F-30 SF-30-05) : durée écoulée et tokens consommés. Absent quand la
   * consommation n'a pas pu être relevée — mieux vaut ne rien dire qu'annoncer « 0 token ».
   */
  cost?: AtelierTurnCost;
  /**
   * Le tour s'est arrêté sur une demande d'interruption (F-32 SF-32-02). Il reste dans le fil — il a
   * réellement eu lieu et il est facturé — mais l'écran doit le dire.
   */
  interrupted?: boolean;
  /**
   * Le tour s'est arrêté sur le **plafond de dépense de ce run** (F-36 SF-36-04). Distinct du quota
   * mensuel épuisé : l'écran doit le dire, et proposer l'action qui débloque réellement.
   */
  budgetReached?: boolean;
  /**
   * Ce qui a changé dans les fichiers (F-37 SF-37-02) : le diff unifié calculé et borné par le
   * backend, replié par fichier. Absent d'un tour qui n'a rien modifié — et de tous les tours
   * antérieurs à F-37, qui restent lisibles tels quels.
   */
  diffs?: AtelierFileDiffView[];
  /**
   * Ce message est une **précision** envoyée pendant un tour (F-84 / SF-84-06), et où elle en est.
   * Absent d'un message ordinaire — et de toute précision relue de l'historique, qui a été prise en
   * compte puisqu'elle y figure.
   */
  steer?: AtelierSteerState;
  /**
   * Pièces jointes envoyées avec ce message (F-169 / SF-169-03) : chemin + taille, rendues **dans la
   * bulle** du message (nom court + taille). Posées à l'envoi depuis les puces du composer, et au
   * rechargement depuis le transcript persistant (`message.files`, SF-169-02). Absent sinon.
   */
  files?: DepositedFileRef[];
}

/** Où en est une précision envoyée pendant un tour (F-84 / SF-84-06). */
export interface AtelierSteerState {
  /** Identifiant rendu par la gateway ; `null` tant qu'elle ne l'a pas encore rendu. */
  steerId: string | null;
  /**
   * `pending` en attente de l'étape suivante · `applied` lue à l'étape `step` · `followup` ouvre
   * un tour de suite · `dropped` non prise en compte, le tour s'étant arrêté.
   */
  status: 'pending' | 'applied' | 'followup' | 'dropped';
  step?: number;
}

/** Coût d'un tour d'exécution affiché sous la transcription (F-30 SF-30-05). */
export interface AtelierTurnCost {
  elapsedSeconds: number;
  tokens: number;
  /**
   * Ce que le tour a coûté, déjà formaté en euros par la passerelle (F-133 / SF-133-02) —
   * « 0,42 € », « < 0,01 € ». **Absent pour qui n'est pas administrateur** : le montant ne quitte
   * alors pas le serveur, il n'est pas masqué ici.
   */
  amount?: string;
}

/** Tour assistant « en cours » pendant le streaming : étapes relayées + commentaire partiel. */
export interface AtelierStreamingItem {
  steps: AtelierStreamAction[];
  text: string;
}

/**
 * Demande d'autorisation affichée dans le flux (F-33 / SF-33-03) : la commande que l'agent veut
 * lancer, et l'état de la réponse. `answering` garde les actions inertes le temps que la décision
 * parte — répondre deux fois n'aurait pas de sens.
 */
export interface AtelierPendingConfirmation {
  toolUseId: string;
  tool: string;
  detail: string;
  /**
   * D'où vient la demande : `HOSTED_SANDBOX` = bac à sable hébergé (F-33), `LOCAL_MACHINE` =
   * machine connectée (F-38 / SF-38-08). Les deux moteurs posent la même question mais **la réponse
   * ne part pas au même endroit** — répondre au mauvais laisserait la commande en attente jusqu'à
   * son refus automatique.
   */
  source: AtelierEngine;
  answering: boolean;
  /** Champ de motif ouvert : le refus se fait en un clic, le motif est un second geste, facultatif. */
  denying: boolean;
  reason: string;
  /**
   * Instant (epoch ms) où la demande expirera, quand la gateway l'a annoncé (F-47 / SF-47-02).
   * `null` quand le délai n'est pas connu — le bac à sable ne le porte pas, et un ancien backend
   * non plus : l'écran n'affiche alors aucun compte à rebours plutôt qu'un chiffre inventé.
   */
  deadline: number | null;
  /** Durée totale annoncée, en millisecondes : sert à dire l'expiration (« dans les 2 minutes »). */
  timeoutMs: number | null;
  /**
   * La gateway propose « **toujours autoriser cette commande** » (F-121 / SF-121-02-FE) : une
   * règle de permission **persistante** pour ce projet, pas un raccourci de tour. Faux par défaut
   * — on ne propose jamais un geste durable que la gateway n'a pas annoncé. Optionnel : la tuile
   * de mosaïque en lecture seule (F-83) n'a aucun bouton à proposer.
   */
  allowAlwaysOffered?: boolean;
}

/**
 * Statut d'une question structurée affichée dans le flux (F-164 / SF-164-02) :
 * `awaiting` = interactive, en attente de réponse ; `answered` = tranchée (verrouillée, montre le
 * choix fait quand il vient d'ici) ; `expired` = le délai a expiré.
 */
export type AtelierQuestionStatus = 'awaiting' | 'answered' | 'expired';

/**
 * Question(s) structurée(s) affichée(s) dans le flux (F-164 / SF-164-02) : le lot posé par l'agent et
 * l'état de la réponse. Mirroir de {@link AtelierPendingConfirmation} pour la porte d'autorisation —
 * l'état du **tour** vit ici (dans le parent), le rendu et la saisie vivent dans la carte.
 * `answering` garde les contrôles inertes le temps que la réponse parte (répondre deux fois n'aurait
 * pas de sens).
 */
export interface AtelierPendingQuestion {
  /** Identifiant de corrélation à renvoyer pour répondre (celui de `question_request`). */
  callId: string;
  /** Le lot de questions à rendre (1 à 4). */
  questions: AtelierQuestion[];
  status: AtelierQuestionStatus;
  answering: boolean;
  /**
   * Vrai quand la réponse a été composée **ici** (on connaît le choix fait) ; faux quand la question a
   * été tranchée ailleurs ou par expiration (on montre alors « Répondu sur un autre appareil » / le
   * délai écoulé, sans inventer un choix).
   */
  answeredHere: boolean;
  /** Le compte rendu lisible du choix fait ici, affiché à l'état « répondu » ; vide sinon. */
  chosenSummary: string;
  /**
   * Instant (epoch ms) d'expiration, quand la gateway l'a annoncé (F-47 / SF-47-02). `null` sinon —
   * aucun compte à rebours plutôt qu'un chiffre inventé.
   */
  deadline: number | null;
  /** Durée totale annoncée, en millisecondes ; `null` quand elle n'est pas connue. */
  timeoutMs: number | null;
  /**
   * Choix retenus par défaut quand la question a expiré (F-164 / SF-164-06) : lignes lisibles
   * relayées par `question_resolved`. Absent hors expiration ou d'un backend antérieur.
   */
  defaults?: string[];
}

/**
 * Tour assistant « en cours » du mode « Terminal » (SF-28-11) : état de la session, transcription
 * terminal (commande + sortie, F-30 SF-30-02) relayée au fil de l'eau, et commentaire partiel.
 */
export interface AtelierExecStreamingItem {
  status: string;
  blocks: AtelierTerminalBlock[];
  text: string;
  /**
   * Tokens consommés depuis le début du tour (F-30 / SF-30-13), relevés pendant le run. `null` tant
   * qu'aucun relevé n'est arrivé — la ligne vivante omet alors le compteur plutôt que d'afficher un
   * zéro qui passerait pour une mesure.
   */
  tokens: number | null;

  /**
   * Plan de travail du tour (F-39 / SF-39-13). Il porte la liste **complète** : chaque mise à jour
   * la remplace, elle ne s'y ajoute pas.
   *
   * **Optionnel**, et c'est délibéré : un tour sans plan est le cas courant, et le rendre
   * obligatoire imposerait un `plan: []` à chaque construction — y compris dans les tests qui ne
   * parlent pas de plan du tout.
   */
  plan?: AtelierPlanStep[];

  /**
   * La gateway a **pris la demande en main** (F-84 / SF-84-04). Avant la première étape, la ligne
   * vivante le dit : sur un long contexte, le premier aller-retour du modèle dure des dizaines de
   * secondes, et « démarrage… » laissait croire que rien n'était parti. Optionnel : absent vaut non.
   */
  accepted?: boolean;

  /**
   * Une **compaction est en cours** (F-162 / SF-162-03) : l'écran montre une barre indéterminée
   * « Compaction de la conversation… », le temps de l'appel de synthèse. Passe à faux à la fin ; le
   * marqueur « Conversation compactée · N tours résumés » vit, lui, dans les blocs (comme une carte),
   * pour rester dans le flux. Optionnel : absent vaut « pas de compaction ».
   */
  compacting?: boolean;
}
