import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * **Les boîtes mail de Jarvis, côté écran** (F-242 / SF-242-01).
 *
 * `/api/jarvis/mail/accounts` : relier une boîte Google par un mot de passe d'application (testé en IMAP et SMTP
 * avant d'être enregistré, chiffré côté passerelle), la tester, la révoquer. Le mot de passe n'est jamais relu : la
 * passerelle ne rend que ses 4 derniers caractères. Le volet n'apparaît jamais en présentation.
 */

export type MailAccountStatus = 'OK' | 'AUTH_FAILED' | 'PAUSED';

export interface MailFolders {
  drafts: string | null;
  trash: string | null;
  all: string | null;
  sent: string | null;
}

export interface MailAccount {
  id: string;
  address: string;
  label: string;
  provider: 'GOOGLE';
  status: MailAccountStatus;
  secretLast4: string;
  lastOkAt: string | null;
  lastError: string | null;
  createdAt: string;
  folders?: MailFolders | null;
}

/** Ce que dit la passerelle d'un refus (400 mot de passe refusé, 400 boîtes pleines, 503 injoignable). */
export interface MailError {
  error?: { error?: string; message?: string };
  status?: number;
}

/** Deux boîtes au plus (D18). */
export const MAX_MAIL_ACCOUNTS = 2;

/** La page Google où se crée — et se révoque — un mot de passe d'application. */
export const GOOGLE_APP_PASSWORDS_URL = 'https://myaccount.google.com/apppasswords';

/** Le libellé lisible d'un état. */
export function mailStatusLabel(status: MailAccountStatus): string {
  switch (status) {
    case 'OK':
      return 'Reliée';
    case 'AUTH_FAILED':
      return 'Mot de passe refusé';
    default:
      return 'En pause';
  }
}

/** Le message lisible d'un refus de la passerelle. */
export function mailErrorMessage(err: MailError | null | undefined): string {
  const message = err?.error?.message;
  if (message) {
    return message;
  }
  if (err?.status === 503) {
    return 'La boîte ne répond pas pour l’instant. Réessayez dans un moment.';
  }
  return 'La boîte n’a pas pu être reliée.';
}

@Injectable({ providedIn: 'root' })
export class JarvisMailClient {
  private readonly http = inject(HttpClient);

  list(): Observable<MailAccount[]> {
    return this.http.get<MailAccount[]>('/api/jarvis/mail/accounts');
  }

  link(address: string, label: string, password: string): Observable<MailAccount> {
    return this.http.post<MailAccount>('/api/jarvis/mail/accounts', { address, label, password });
  }

  test(id: string): Observable<MailAccount> {
    return this.http.post<MailAccount>(`/api/jarvis/mail/accounts/${id}/test`, {});
  }

  revoke(id: string): Observable<void> {
    return this.http.delete<void>(`/api/jarvis/mail/accounts/${id}`);
  }
}
