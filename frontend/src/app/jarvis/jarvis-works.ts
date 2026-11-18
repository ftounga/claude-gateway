import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

import { ConfirmationAnswer, classifyConfirmation } from './jarvis-confirm';
import { normalize } from './jarvis-oral';

/**
 * **Les travaux de Jarvis en fond** (F-270) — fonctions pures et client de `/api/jarvis/works`.
 *
 * SF-270-02 (D1, D2) : un tour né à la voix part **en fond** (annoncé à la passerelle, puis ouvert comme une délégation)
 * et chaque « Vous confirmez ? » arme une **confirmation identifiée** (travail, sujet, chantier, nom) : un « oui » ne
 * part jamais sur un travail ambigu.
 */

/** Une confirmation attendue : le travail qui a posé la question, et le fil où la réponse doit aller. */
export interface ArmedConfirmation {
  /** Le travail (délégation, tour direct) qui a demandé « Vous confirmez ? », ou `null` (tour au premier plan). */
  workId: string | null;
  subjectId: string;
  chantier: number | null;
  /** Le nom dit pour la désigner (« LDIC-325 », « agenor »). */
  name: string;
  at: number;
}

/** F-271 §8 — une confirmation non reçue en 2 min expire. */
export const CONFIRM_TTL_MS = 120_000;

/** Ce que vaut une parole pour les confirmations armées. */
export type ConfirmationTarget =
  | { kind: 'none' }
  | { kind: 'one'; item: ArmedConfirmation }
  | { kind: 'ambiguous'; items: ArmedConfirmation[] };

/**
 * **Les confirmations armées** (D2) — une par travail ; la plus récente d'un même fil remplace l'ancienne. Un « oui »
 * qui ne nomme rien vise la seule armée ; s'il y en a plusieurs, Jarvis demande laquelle (jamais d'exécution sur une
 * ambiguïté).
 */
export class ArmedConfirmations {
  private items: ArmedConfirmation[] = [];

  constructor(private readonly now: () => number = () => Date.now()) {}

  arm(item: Omit<ArmedConfirmation, 'at'>): void {
    this.items = this.live().filter((i) => !(i.subjectId === item.subjectId && i.chantier === item.chantier));
    this.items.push({ ...item, at: this.now() });
  }

  disarm(item: ArmedConfirmation): void {
    this.items = this.items.filter((i) => i !== item);
  }

  clear(): void {
    this.items = [];
  }

  /** Les confirmations encore valables, de la plus ancienne à la plus récente. */
  live(): ArmedConfirmation[] {
    const limit = this.now() - CONFIRM_TTL_MS;
    this.items = this.items.filter((i) => i.at >= limit);
    return [...this.items];
  }

  get size(): number {
    return this.live().length;
  }

  /** La confirmation visée par cette parole : celle qu'elle nomme, sinon la seule armée, sinon l'ambiguïté. */
  target(text: string): ConfirmationTarget {
    const items = this.live();
    if (items.length === 0) {
      return { kind: 'none' };
    }
    const named = items.filter((i) => names(text, i.name));
    if (named.length === 1) {
      return { kind: 'one', item: named[0] };
    }
    if (items.length === 1) {
      return { kind: 'one', item: items[0] };
    }
    return { kind: 'ambiguous', items: named.length > 1 ? named : items };
  }
}

/** Vrai si la parole nomme ce travail (« LDIC-325 » ⇄ « ldic 325 », « agenor », « le chantier 4 »). */
export function names(text: string, name: string): boolean {
  const said = ` ${normalize(text)} `;
  const target = normalize(name);
  return target.length > 0 && said.includes(` ${target} `);
}

/** « LDIC-325 ou agenor ? », « LDIC-325, agenor ou chantier 4 ? » — jamais une phrase de plus. */
export function whichQuestion(items: ArmedConfirmation[]): string {
  const list = [...new Set(items.map((i) => i.name.trim()).filter(Boolean))];
  if (list.length < 2) {
    return 'Pour lequel, Monsieur ?';
  }
  return `${list.slice(0, -1).join(', ')} ou ${list[list.length - 1]} ?`;
}

/** Une confirmation dite en plusieurs mots qui n'est ni oui ni non est une autre demande, pas une réponse ambiguë. */
export function isOtherRequest(text: string): boolean {
  return normalize(text).split(' ').filter(Boolean).length >= 4;
}

/**
 * Oui, non ou doute, une fois retiré le nom du travail visé (« oui pour LDIC-325 », « non, pas agenor » reste un doute :
 * la négation garde la main).
 */
export function confirmationVerdict(text: string, items: ArmedConfirmation[]): ConfirmationAnswer {
  let said = ` ${normalize(text)} `;
  for (const item of items) {
    const name = normalize(item.name);
    if (name && said.includes(` ${name} `)) {
      said = said.replace(` ${name} `, ' ');
    }
  }
  said = said.replace(/ (pour|sur) (le |la |l )?(chantier |sujet )?$/, ' ').replace(/^ (pour|sur) /, ' ');
  return classifyConfirmation(said.trim());
}

/** Une clé de ticket (« LDIC-325 »). */
const TICKET = /\b([A-Z][A-Z0-9]{1,9}-\d{1,6})\b/;

/**
 * Le nom court d'un travail (même règle que la passerelle, SF-270-01) : clé de ticket, sinon « chantier N », sinon le
 * nom du sujet, sinon les premiers mots du libellé.
 */
export function shortName(label: string | null | undefined, chantier: number | null | undefined,
                          subjectName: string | null | undefined): string {
  const ticket = TICKET.exec(label ?? '');
  if (ticket) {
    return ticket[1];
  }
  if (chantier && chantier > 0) {
    return `chantier ${chantier}`;
  }
  const name = (subjectName ?? '').trim() || (label ?? '').trim().split(/\s+/).slice(0, 3).join(' ');
  return name.length <= 24 ? name : `${name.slice(0, 23)}…`;
}

/** Un travail tel que la passerelle le lit (SF-270-01). */
export interface JarvisWork {
  id: string;
  origin: 'DELEGATION' | 'DIRECT' | 'COURSE';
  subjectId: string;
  subjectName: string;
  chantier: number | null;
  name: string;
  label: string;
  phase: 'THINKING' | 'EXECUTING' | 'WAITING_YES' | 'QUEUED' | 'DONE' | 'FAILED' | 'CANCELLED';
  since: string;
  endedAt: string | null;
}

/** L'annonce d'un tour direct ne retarde jamais le tour au-delà de ce délai. */
export const ANNOUNCE_TIMEOUT_MS = 1_500;

@Injectable({ providedIn: 'root' })
export class JarvisWorksClient {
  private readonly http = inject(HttpClient);

  /** Annonce un tour direct né à la voix (best-effort : un échec n'empêche jamais le tour). */
  async announce(work: { id: string; subjectId: string; chantier: number | null; label: string }): Promise<boolean> {
    try {
      await firstValueFrom(this.http.post('/api/jarvis/works', work).pipe(timeout(ANNOUNCE_TIMEOUT_MS)));
      return true;
    } catch {
      return false;
    }
  }
}

/** Un identifiant de travail tiré par l'écran. */
export function newWorkId(): string {
  const c = globalThis.crypto as Crypto | undefined;
  if (c?.randomUUID) {
    return c.randomUUID();
  }
  const hex = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-a${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}
