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

/** F-242 / SF-242-02 — un courrier du cache, tel que la passerelle le rend (en-têtes descellés, extrait borné). */
export interface MailItem {
  id: string;
  accountId: string;
  account: string | null;
  from: string;
  fromName: string;
  subject: string;
  snippet: string;
  receivedAt: string;
  triage: 'A_TRAITER' | 'A_LIRE' | 'A_IGNORER' | 'EN_ATTENTE';
  triageReason: string | null;
  hasCalendar: boolean;
}

/** F-242 / SF-242-04 — un courrier relu par la passerelle : texte seul (le HTML n'est jamais rendu). */
export interface MailBody {
  item: MailItem;
  text: string;
  truncated: boolean;
}

/** F-242 / SF-242-04 (D8) — le bref d'ouverture ; `text` vide : rien de neuf. */
export interface MailBrief {
  text: string;
  /** F-243 / SF-243-04 (D8) — un rendez-vous repéré à proposer (une phrase, une proposition à la fois), ou vide. */
  proposal?: string;
  items: { id: string; from: string; fromName: string; subject: string; reason: string | null }[];
}

/** F-242 / SF-242-04 (D16) — ce que montre la visionneuse de courriel. */
export interface MailCard {
  mode: 'highlight' | 'capture' | 'draft';
  id: string | null;
  from: string;
  fromName: string;
  to?: string;
  subject: string;
  receivedAt: string | null;
  account: string | null;
  text: string;
  truncated: boolean;
}

/** Le préfixe des courriels dans l'ordre d'écran `afficher` (F-242 / SF-242-04). */
export const MAIL_PIECE_PREFIX = 'courriel:';

/** Le premier identifiant de courriel d'un ordre d'écran, ou `null`. */
export function mailPieceId(pieces: readonly string[] | null | undefined): string | null {
  const hit = (pieces ?? []).find((p) => p.startsWith(MAIL_PIECE_PREFIX));
  const id = hit ? hit.slice(MAIL_PIECE_PREFIX.length) : '';
  return /^[0-9a-f-]{36}$/i.test(id) ? id : null;
}

/** La carte « mail mis en avant » d'un bref. */
export function briefCard(brief: MailBrief): MailCard | null {
  const first = brief.items[0];
  if (!brief.text || !first) {
    return null;
  }
  return { mode: 'highlight', id: first.id, from: first.from, fromName: first.fromName, subject: first.subject,
    receivedAt: null, account: null, text: first.reason ?? '', truncated: false };
}

/** La carte « capture du mail » d'un corps relu. */
export function bodyCard(body: MailBody): MailCard {
  const i = body.item;
  return { mode: 'capture', id: i.id, from: i.from, fromName: i.fromName, subject: i.subject, receivedAt: i.receivedAt,
    account: i.account, text: body.text, truncated: body.truncated };
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

  /** F-242 / SF-242-04 — le bref d'ouverture (vide en présentation ou sans neuf). */
  brief(presentation: boolean): Observable<MailBrief> {
    return this.http.get<MailBrief>(`/api/jarvis/mail/brief?presentation=${presentation}`);
  }

  /** F-242 / SF-242-04 — le corps d'un courrier, relu par la passerelle, en texte seul. */
  body(id: string): Observable<MailBody> {
    return this.http.get<MailBody>(`/api/jarvis/mail/items/${id}`);
  }
}
