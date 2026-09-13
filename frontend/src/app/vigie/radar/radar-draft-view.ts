import { HttpErrorResponse } from '@angular/common/http';

import { BoardCommitment, RadarDraftKind } from '../../core/models/radar.models';
import { httpErrorMessage } from '../../shared/http-error.util';

/**
 * **Quel brouillon un engagement propose** (F-104 / SF-104-05) : une relance pour ce qu'on attend des autres,
 * une présentation pour une mise en relation — seulement en cours, et jamais sur une question (`probable`).
 */
export function draftKindOf(item: BoardCommitment): RadarDraftKind | null {
  const c = item.commitment;
  if (item.question || c.disowned || (c.status !== 'OPEN' && c.status !== 'POSTPONED')) {
    return null;
  }
  if (c.direction === 'OTHER_TO_ME') {
    return 'FOLLOW_UP';
  }
  return c.direction === 'INTRODUCTION' ? 'INTRODUCTION' : null;
}

/** Le libellé du bouton. */
export function draftButtonLabel(kind: RadarDraftKind): string {
  return kind === 'FOLLOW_UP' ? 'Préparer la relance' : 'Préparer la présentation';
}

/** Ce que le dialogue dit d'une préparation en échec. */
export function draftErrorOf(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    switch (err.status) {
      case 402:
        return 'Votre quota de consommation est atteint : le brouillon ne peut pas être préparé.';
      case 503:
        return 'Le fournisseur est momentanément indisponible. Réessayez dans un instant.';
      case 502:
        return "Le brouillon n'a pas pu être préparé. Réessayez.";
      default:
        break;
    }
  }
  return httpErrorMessage(err, "Le brouillon n'a pas pu être préparé. Réessayez.");
}
