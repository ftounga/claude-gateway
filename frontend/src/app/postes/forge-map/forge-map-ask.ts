import { MapDeadline, MapNode, MapToMap } from '../../core/models/governance.models';
import { kindLabel } from './forge-map-levels';

/**
 * **Agir depuis la carte** (F-173 / SF-173-07, D7), en fonctions pures : les consignes que
 * « Demander à la Forge » dépose dans le terminal du poste — **pré-remplies, jamais envoyées**.
 * Chacune se termine par la relecture avant écriture : la carte ne change que sous les yeux de
 * l'utilisateur (F-174 D1).
 */

/** Longueur maximale d'un texte de la carte cité dans une consigne. */
export const QUOTE_MAX = 300;

const REVIEW = "Montre-moi les changements avant de les écrire dans la carte.";

export function quote(text: string | null | undefined): string {
  const clean = (text ?? '').replace(/\s+/g, ' ').trim().replace(/^[-*+]\s+/, '');
  return clean.length > QUOTE_MAX ? clean.slice(0, QUOTE_MAX - 1) + '…' : clean;
}

function where(path: string | null, heading: string | null): string {
  if (!path) {
    return '';
  }
  return ` (${path}${heading ? ' § ' + heading : ''})`;
}

function frDate(iso: string): string {
  const [y, m, d] = iso.slice(0, 10).split('-');
  return `${d}/${m}/${y}`;
}

/** La consigne pour une ressource. */
export function askAboutNode(node: MapNode): string {
  const parts = [
    `Sur la carte de ce poste, fais le point sur « ${quote(node.label)} » (${kindLabel(node.kind).toLowerCase()}) :`,
    'vérifie ce qui est encore vrai, complète ce qui manque et signale les pièges.',
  ];
  if (node.traps > 0) {
    parts.push(`La carte y note ${node.traps} piège${node.traps > 1 ? 's' : ''} : rappelle-les avant toute action.`);
  }
  if (node.stale) {
    parts.push('Ses faits sont périmés : revérifie-les sur le terrain avant de les confirmer.');
  }
  parts.push(REVIEW);
  return parts.join(' ');
}

/** La consigne pour une échéance. */
export function askAboutDeadline(deadline: MapDeadline): string {
  const subject = deadline.nodeLabel ? ` de « ${quote(deadline.nodeLabel)} »` : '';
  return `Sur la carte de ce poste, l'échéance du ${frDate(deadline.dueOn)}${subject} : « ${quote(deadline.text)} »`
    + `${where(deadline.path, deadline.heading)}. Dis-moi ce qu'il faut faire pour la renouveler ou la lever, `
    + `et mets la carte à jour une fois que c'est fait. ${REVIEW}`;
}

/** La consigne pour une chose « à cartographier ». */
export function askToMap(item: MapToMap): string {
  const subject = item.label ? `« ${quote(item.label)} »` : `ce que dit « ${quote(item.text)} »`;
  return `Sur la carte de ce poste, cartographie ${subject}${where(item.path, item.heading)} : `
    + `découvre-le avec les moyens du poste (lecture seule), puis propose ce qu'il faut ajouter à la carte. ${REVIEW}`;
}
