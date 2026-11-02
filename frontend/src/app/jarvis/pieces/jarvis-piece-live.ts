import { AtelierProofEvent } from '../../core/models/atelier.models';
import { JarvisUniverse } from '../scenes/jarvis-universe.models';
import { Piece } from './jarvis-piece.models';
import { ShowCommand } from './jarvis-piece-show';
import { ShowRequest } from '../scenes/jarvis-show';

/**
 * F-237 / SF-237-09 — **côté Opus** : la ligne `🖥 montrer:` étendue aux pièces, les captures de preuve qui
 * arrivent **au fil de l'eau** (événement SSE `proof`, transition T-G7) et la **preuve manquante** à la clôture
 * d'un chantier (§14 idée 6). Pur.
 */

/** La ligne `🖥 montrer: preuves chantier 3` (ou `entrees`, `pieces <ids>`) en ordre d'écran — demandé par Opus. */
export function pieceShowCommand(request: ShowRequest): ShowCommand | null {
  switch (request.filter) {
    case 'preuves':
    case 'entrees':
      return {
        action: 'galerie', reason: 'demande',
        scope: { chantier: request.chantier ?? null, category: request.filter },
      };
    case 'pieces': {
      const ids = request.ids ?? [];
      return ids.length ? { action: ids.length === 1 ? 'piece' : 'galerie', pieces: ids, reason: 'demande' } : null;
    }
    default:
      return null;
  }
}

/** L'identifiant d'une capture dans l'index des pièces (`proof:<hex>`, sans tirets). */
export function proofPieceId(captureId: string): string {
  return `proof:${captureId.replace(/-/g, '')}`;
}

/** Les cartes d'une session de preuve publiée pendant le tour : une par capture, chemins passerelle seulement. */
export function proofPieces(event: AtelierProofEvent, subjectId: string, chantier: number | null,
  at: string = new Date().toISOString()): Piece[] {
  const proof = event.proof;
  return (proof.captures ?? []).filter((c) => !!c.id).map((capture, i) => {
    const hex = capture.id.replace(/-/g, '');
    const ok = proof.commands?.filter((c) => c.status === 'done' && (c.exitCode ?? 0) === 0).length ?? 0;
    const total = proof.commands?.length ?? 0;
    const step = proof.step ? `${proof.step}. ` : '';
    return {
      id: proofPieceId(capture.id),
      category: 'PREUVE',
      kind: 'IMAGE',
      title: (proof.captures.length > 1 ? `${proof.title} (${i + 1}/${proof.captures.length})` : proof.title) || 'Preuve',
      caption: `${step}${total} commande${total > 1 ? 's' : ''}${total ? (ok === total ? ', toutes réussies' : `, ${total - ok} en échec`) : ''}`,
      captionSource: 'STEP',
      at,
      subjectId,
      chantier,
      chantierLink: chantier != null ? 'MESSAGE' : 'NONE',
      thumb: `/jarvis/pieces/proof:${hex}/thumb`,
      open: `/workspaces/${subjectId}/proofs/${hex}`,
      meta: { step: proof.step ?? undefined, width: capture.width ?? undefined, height: capture.height ?? undefined },
      available: 'ONLINE',
    } satisfies Piece;
  });
}

/** Un chantier clos depuis la dernière lecture de l'univers. */
export interface ClosedChantier {
  subjectId: string;
  subjectName: string;
  number: number;
  title: string;
}

/**
 * Les chantiers **disparus** de l'univers entre deux lectures, sur un sujet toujours présent : le tableau ne montre
 * que les chantiers ouverts, un chantier qui en sort vient d'être clos.
 */
export function closedChantiers(previous: JarvisUniverse | null, next: JarvisUniverse | null): ClosedChantier[] {
  if (!previous || !next) {
    return [];
  }
  const now = new Map<string, Set<number>>();
  for (const client of next.clients) {
    for (const subject of client.subjects) {
      now.set(subject.id, new Set(subject.chantiers.map((c) => c.number)));
    }
  }
  const closed: ClosedChantier[] = [];
  for (const client of previous.clients) {
    for (const subject of client.subjects) {
      const still = now.get(subject.id);
      if (!still) {
        continue; // sujet disparu (archivé, autre compte) : rien n'est déduit
      }
      for (const chantier of subject.chantiers) {
        if (!still.has(chantier.number)) {
          closed.push({ subjectId: subject.id, subjectName: subject.name, number: chantier.number, title: chantier.title });
        }
      }
    }
  }
  return closed;
}

/** La proposition dite quand un chantier se clôt sans aucune preuve en image (§14 idée 6). */
export function proofOfferPhrase(closed: ClosedChantier): string {
  return `Le chantier ${closed.number} de ${closed.subjectName} est clos sans preuve en image. `
    + 'Voulez-vous que je la génère, Monsieur ?';
}

/** La demande confiée à Opus sur un « oui » (chaîne §6.4) — outil existant `capturer_preuves`. */
export function proofDemand(chantier: number): string {
  return `Génère les captures de preuve du chantier ${chantier} avec capturer_preuves (les commandes de vérification `
    + 'de son plan), puis montre-les.';
}

/** Une proposition de preuve reste ouverte au plus autant de temps. */
export const PROOF_OFFER_MS = 2 * 60_000;
