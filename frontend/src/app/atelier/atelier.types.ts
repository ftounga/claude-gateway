import { AtelierAction, AtelierRole, AtelierAgentStreamAction, AtelierStreamAction, AtelierTerminalBlock } from '../core/models/atelier.models';

/**
 * Mode de l'agent. Valeurs techniques inchangées (F-30 SF-30-03) : `edit` = mode **Assistant**
 * (Phase 1, lecture/écriture), `exec` = mode **Terminal** (Phase 2, sandbox hébergé).
 */
export type AtelierAgentMode = 'edit' | 'exec';

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
}

/** Coût d'un tour d'exécution affiché sous la transcription (F-30 SF-30-05). */
export interface AtelierTurnCost {
  elapsedSeconds: number;
  tokens: number;
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
  answering: boolean;
  /** Champ de motif ouvert : le refus se fait en un clic, le motif est un second geste, facultatif. */
  denying: boolean;
  reason: string;
}

/**
 * Tour assistant « en cours » du mode « Terminal » (SF-28-11) : état de la session, transcription
 * terminal (commande + sortie, F-30 SF-30-02) relayée au fil de l'eau, et commentaire partiel.
 */
export interface AtelierExecStreamingItem {
  status: string;
  blocks: AtelierTerminalBlock[];
  text: string;
}
