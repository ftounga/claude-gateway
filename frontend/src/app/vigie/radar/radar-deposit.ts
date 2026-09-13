import { HttpErrorResponse } from '@angular/common/http';
import { Observable, firstValueFrom } from 'rxjs';

import { httpErrorMessage } from '../../shared/http-error.util';

/** Extensions acceptées, alignées sur le dossier de dépôt du poste (SF-100-05). */
export const DEPOSIT_EXTENSIONS = ['.mp3', '.m4a', '.wav', '.ogg', '.aac', '.flac', '.mp4', '.mov', '.mkv', '.webm'];
/** Taille maximale d'un enregistrement déposé depuis l'écran. */
export const DEPOSIT_MAX_BYTES = 500 * 1024 * 1024;

/** Ce qui empêche de déposer ce fichier, ou `null`. */
export function depositFileProblem(file: { name: string; size: number } | null): string | null {
  if (!file) {
    return 'Choisissez un enregistrement.';
  }
  const lower = file.name.toLowerCase();
  if (!DEPOSIT_EXTENSIONS.some((ext) => lower.endsWith(ext))) {
    return `Un enregistrement est un fichier audio ou vidéo (${DEPOSIT_EXTENSIONS.join(' ')}).`;
  }
  if (file.size <= 0) {
    return 'Ce fichier est vide.';
  }
  if (file.size > DEPOSIT_MAX_BYTES) {
    return 'Un enregistrement déposé depuis l\'écran pèse au plus 500 Mio. Au-delà, copiez-le dans le dossier radar/depot du poste.';
  }
  return null;
}

/** Le titre proposé : le nom du fichier sans son extension. */
export function titleFromFileName(name: string): string {
  const dot = name.lastIndexOf('.');
  return (dot > 0 ? name.substring(0, dot) : name).replace(/[_]+/g, ' ').trim().substring(0, 200);
}

/** Valeur d'un champ `datetime-local` (heure locale du navigateur), depuis un instant. */
export function localInputValue(epochMs: number): string {
  const d = new Date(epochMs);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** Un champ `datetime-local` en ISO-8601 **avec son décalage** : la gateway exige le fuseau. */
export function isoWithOffset(localValue: string): string | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(localValue ?? '');
  if (!match) {
    return null;
  }
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), Number(match[4]), Number(match[5]));
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  const offset = -date.getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  const abs = Math.abs(offset);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${match[1]}-${match[2]}-${match[3]}T${match[4]}:${match[5]}:00${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}

/** Une erreur de réseau (ou un poste qui n'a pas répondu) : une nouvelle tentative a du sens. */
export function isRetryable(err: unknown): boolean {
  return err instanceof HttpErrorResponse && (err.status === 0 || err.status >= 500);
}

/**
 * **Envoie le fichier par morceaux, l'un après l'autre** (F-104 / SF-104-04). Chaque morceau est retenté une
 * fois sur une erreur de réseau ; `cancelled()` est consulté avant chaque envoi.
 *
 * @returns vrai si tout est parti, faux si l'envoi a été annulé
 */
export async function sendInChunks(
  file: Blob,
  chunkBytes: number,
  send: (offset: number, chunk: Blob) => Observable<unknown>,
  progress: (sent: number) => void,
  cancelled: () => boolean,
): Promise<boolean> {
  for (let offset = 0; offset < file.size; offset += chunkBytes) {
    if (cancelled()) {
      return false;
    }
    const chunk = file.slice(offset, Math.min(file.size, offset + chunkBytes));
    try {
      await firstValueFrom(send(offset, chunk));
    } catch (err) {
      if (!isRetryable(err) || cancelled()) {
        throw err;
      }
      await firstValueFrom(send(offset, chunk));
    }
    progress(Math.min(file.size, offset + chunkBytes));
  }
  return true;
}

/** Ce que le dialogue dit d'un dépôt en échec. */
export function depositErrorOf(err: unknown): string {
  if (err instanceof HttpErrorResponse && err.status === 0) {
    return 'La connexion a été coupée : le dépôt est interrompu. Recommencez.';
  }
  return httpErrorMessage(err, "Le dépôt n'a pas abouti. Recommencez.");
}
