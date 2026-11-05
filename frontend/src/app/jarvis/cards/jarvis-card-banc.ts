import { JarvisPieceStore } from '../pieces/jarvis-piece-store';
import { JarvisCardDetail } from './jarvis-card-client';
import { JarvisCard } from './jarvis-card.models';

/**
 * F-248 / SF-248-10 — **les pancartes du banc de captures** (`/jarvis-banc?banc=pancartes-…`, développement seulement) :
 * des faits fictifs (aucun réseau) pour la planche de référence de F-248. Heures relatives à l'instant du banc.
 */

const at = (minutesAgo: number) => new Date(Date.now() - minutesAgo * 60_000).toISOString();

export const BANC_CARDS: JarvisCard[] = [
  { id: 'fait:etat:banc.1', type: 'TICKET', title: 'PROJ-123 — Migration SSO', group: 'CAGIP', source: 'tour',
    status: { word: 'En revue', tone: 'normal' }, observedAt: at(95), aliases: ['PROJ-123'], targeted: true,
    fields: [{ label: 'Statut', value: 'En revue' }, { label: 'Assigné', value: 'Rémi Martin' },
      { label: 'Mis à jour', value: 'hier 17:42' }],
    link: 'https://jira.example.com/browse/PROJ-123' },
  { id: 'fait:etat:banc.2', type: 'MR', title: 'MR projet!42 — Correctif 502', group: 'CAGIP', source: 'tour',
    status: { word: 'pipeline échoué', tone: 'alerte' }, observedAt: at(40), aliases: ['projet!42'], targeted: true,
    fields: [{ label: 'État', value: 'opened' }, { label: 'Pipeline', value: 'failed' },
      { label: 'Branche', value: 'fix/502' }],
    link: 'https://gitlab.example.com/projet/-/merge_requests/42' },
  { id: 'fait:etat:banc.3', type: 'DEPLOIEMENT', title: 'Déploiement prod/api', group: 'CAGIP', source: 'releve',
    status: { word: 'sain', tone: 'ok' }, observedAt: at(8), aliases: ['prod/api'], targeted: true,
    fields: [{ label: 'Version', value: '2.14.1' }, { label: 'Image', value: 'api:2.14.1' },
      { label: 'Réplicas', value: '3/3' }] },
  { id: 'fait:attente:banc.4', type: 'ATTENTE', title: 'Obtenir l’accès VPN', group: 'CAGIP', source: 'direct',
    status: { word: 'demandé — attend la réponse', tone: 'attend' }, observedAt: at(300), aliases: ['Rémi'],
    targeted: true, fields: [{ label: 'Depuis', value: '5/11 13:10' }, { label: 'Chantier', value: 'Chantier 3' },
      { label: 'Qui', value: 'Rémi Martin' }], link: '/atelier/banc?chantier=3' },
  { id: 'fait:acces:banc.5', type: 'ACCES', title: 'Jeton du poste CAGIP', group: 'CAGIP', source: 'direct',
    status: { word: 'expire dans 3 j', tone: 'attend' }, observedAt: at(2), aliases: ['CAGIP'], targeted: true,
    fields: [{ label: 'Compte', value: 'CAGIP' }, { label: 'Type', value: 'jeton du runner' },
      { label: 'Validité', value: '8/11/2026' }] },
  { id: 'fait:courriel:banc.6', type: 'COURRIEL', title: 'Budget Q4', group: 'Edenred', source: 'direct',
    status: { word: 'à répondre', tone: 'attend' }, observedAt: at(55), aliases: ['Budget Q4'], targeted: true,
    fields: [{ label: 'De', value: 'Zahi' }, { label: 'Reçu', value: '5/11 17:55' }, { label: 'Objet', value: 'Budget Q4' }] },
  { id: 'fait:etat:banc.7', type: 'PIPELINE', title: 'Pipeline main #7781', group: 'Edenred', source: 'tour',
    status: { word: 'réussi', tone: 'ok' }, observedAt: at(1600), aliases: ['7781'], targeted: true,
    fields: [{ label: 'État', value: 'success' }, { label: 'Branche', value: 'main' }, { label: 'Durée', value: '6 min' }] },
  { id: 'fait:personne:banc.8', type: 'PERSONNE', title: 'Zahi', group: 'Edenred', source: 'direct',
    status: null, observedAt: at(1), aliases: ['Zahi'], targeted: true,
    fields: [{ label: 'Rôle', value: 'chef de projet' }, { label: 'Client', value: 'Edenred' },
      { label: 'Ce qui attend d’elle', value: 'chantier 2 — Recette' }] },
  { id: 'fait:etat:banc.9', type: 'TICKET', title: 'EDN-88 — Recette paiement', group: 'Edenred', source: 'tour',
    status: { word: 'Bloqué', tone: 'attend' }, observedAt: at(200), aliases: ['EDN-88'], targeted: true,
    fields: [{ label: 'Statut', value: 'Bloqué' }, { label: 'Bloqué par', value: 'EDN-80' }] },
];

/** Le détail fictif du plein écran (aucun réseau). */
export const BANC_DETAIL: JarvisCardDetail = {
  card: BANC_CARDS[0], subjectName: 'Migration SSO', chantier: 3,
  previous: { value: 'En cours', at: at(1500) },
  fields: [{ label: 'Statut', value: 'En revue' }, { label: 'Titre', value: 'Migration SSO' },
    { label: 'Assigné', value: 'Rémi Martin' }, { label: 'Priorité', value: 'haute' },
    { label: 'Mis à jour', value: 'hier 17:42' }, { label: 'Client', value: 'CAGIP' }],
};

/** Le nombre de pancartes de chaque scène. */
const COUNTS: Record<string, number> = { 'pancartes-un': 1, 'pancartes-eventail': 4, 'pancartes-mur': 9,
  'pancartes-plein-ecran': 4, 'pancartes-comparer': 4, 'pancartes-eteint': 3, 'pancartes-plat': 4 };

/** Pose la scène des pancartes du banc sur le magasin. */
export function stageBancCards(store: JarvisPieceStore, scene: string): void {
  const n = COUNTS[scene] ?? 4;
  store.showCards(`banc:${scene}`, BANC_CARDS.slice(0, n), {
    note: scene === 'pancartes-eteint' ? 'Poste CAGIP éteint depuis 18 h 05' : null,
  });
}

/** Le plein écran posé par une scène, ou `null`. */
export function bancCardDetail(scene: string): { card: JarvisCard; detail: JarvisCardDetail | null;
  other: JarvisCard | null } | null {
  if (scene === 'pancartes-plein-ecran') {
    return { card: BANC_CARDS[0], detail: BANC_DETAIL, other: null };
  }
  if (scene === 'pancartes-comparer') {
    return { card: BANC_CARDS[0], detail: null, other: { ...BANC_CARDS[8] } };
  }
  return null;
}
