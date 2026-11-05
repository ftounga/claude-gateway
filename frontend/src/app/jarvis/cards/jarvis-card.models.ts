/**
 * F-248 — **les pancartes**. Contrat importé de SF-248-02 (événement SSE `cards` de `POST /api/jarvis/converse`,
 * cadrage §4.1-§4.2) : la passerelle fabrique les fiches des faits **réellement lus** ; l'écran ne fait que les montrer.
 */

export type CardType = 'TICKET' | 'MR' | 'PIPELINE' | 'DEPLOIEMENT' | 'RESULTAT' | 'RENDEZ_VOUS' | 'COURRIEL'
  | 'PERSONNE' | 'ACCES' | 'ATTENTE' | 'GENERIQUE';

/** Tous les types, dans l'ordre — la garde de couverture des gabarits les parcourt. */
export const CARD_TYPES: readonly CardType[] = [
  'TICKET', 'MR', 'PIPELINE', 'DEPLOIEMENT', 'RESULTAT', 'RENDEZ_VOUS', 'COURRIEL', 'PERSONNE', 'ACCES', 'ATTENTE',
  'GENERIQUE',
];

/** Le ton d'un statut (DESIGN_SYSTEM §23) : le mot est toujours écrit, la couleur ne porte jamais seule l'information. */
export type CardTone = 'normal' | 'ok' | 'attend' | 'alerte' | 'eteint';

export interface CardStatus {
  word: string;
  tone: CardTone;
}

export interface CardField {
  label: string;
  value: string;
}

/** Une pancarte, telle que la passerelle la fabrique (liste blanche de champs, jamais de secret). */
export interface JarvisCard {
  /** `fait:<famille>:<ref>`. */
  id: string;
  type: CardType | string;
  title: string;
  status?: CardStatus | null;
  fields: CardField[];
  /** URL relevée, ou route de l'application (`/atelier/…`) ; jamais devinée. */
  link?: string | null;
  /** Heure d'observation, posée par la passerelle (ISO). */
  observedAt?: string | null;
  source?: string | null;
  /** Le client, pour regrouper. */
  group?: string | null;
  /** Les formes sous lesquelles l'élément peut être prononcé (citation, SF-248-05). */
  aliases: string[];
  /** Lecture ciblée : montrée d'office ; sinon seulement si elle est citée (D2). */
  targeted: boolean;
}

/** L'événement `cards` : le jeu complet d'une réponse (il remplace le précédent du même `responseId`). */
export interface CardsEvent {
  responseId: string;
  targeted: boolean;
  cards: JarvisCard[];
  /** F-248 / SF-248-07 — « Poste CAGIP éteint depuis 18 h 05 », une fois en pied du jeu (posé par la passerelle). */
  offline?: string | null;
}
