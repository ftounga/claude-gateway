import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Une action rapide d'un terminal (F-275) : action personnalisée ou déclencheur d'un skill. */
export interface QuickAction {
  /** Clé stable des lancements : `c:<uuid>` ou `s:<ancre>:<skill>:<déclencheur>`. */
  key: string;
  source: 'PERSO' | 'SKILL';
  id: string | null;
  label: string;
  phrase: string;
  skill: string | null;
  scope: 'SUJET' | 'POSTE' | 'COMPTE' | null;
  /** Le sujet où lancer ; `null` = le terminal où l'on clique. */
  targetWorkspaceId: string | null;
  targetName: string | null;
  targetMissing: boolean;
  clickMode: 'DEPOSER' | 'ENVOYER';
  /** Jours ISO de la suggestion (1 = lundi … 7 = dimanche). */
  suggestDays: number[];
  suggestMoment: 'MATIN' | 'APRES_MIDI' | 'JOURNEE' | null;
  lastRunAt: string | null;
}

/** Le lancement enregistré. */
export interface QuickActionLaunch {
  key: string;
  lastRunAt: string;
}

/** Jour ISO d'une date (1 = lundi … 7 = dimanche). */
function isoDay(date: Date): number {
  const d = date.getDay();
  return d === 0 ? 7 : d;
}

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/**
 * **Vrai si l'action est suggérée maintenant** (F-275, D6) : son jour est aujourd'hui, son moment correspond à l'heure
 * (matin avant midi, après-midi ensuite, journée toujours), et elle n'a pas déjà été lancée aujourd'hui. Rien n'est
 * exécuté : la suggestion remonte l'action en tête du menu.
 */
export function isSuggestedNow(action: QuickAction, now: Date = new Date()): boolean {
  if (!action.suggestDays?.length || !action.suggestDays.includes(isoDay(now))) {
    return false;
  }
  const hour = now.getHours();
  if (action.suggestMoment === 'MATIN' && hour >= 12) {
    return false;
  }
  if (action.suggestMoment === 'APRES_MIDI' && hour < 12) {
    return false;
  }
  if (action.lastRunAt) {
    const last = new Date(action.lastRunAt);
    if (!Number.isNaN(last.getTime()) && sameDay(last, now)) {
      return false;
    }
  }
  return true;
}

/** « dernière : il y a 2 j » — ou « jamais lancée ». */
export function lastRunLabel(lastRunAt: string | null, now: Date = new Date()): string {
  if (!lastRunAt) {
    return 'jamais lancée';
  }
  const at = new Date(lastRunAt);
  if (Number.isNaN(at.getTime())) {
    return 'jamais lancée';
  }
  const minutes = Math.max(0, Math.round((now.getTime() - at.getTime()) / 60000));
  if (minutes < 1) {
    return 'dernière : à l\'instant';
  }
  if (minutes < 60) {
    return `dernière : il y a ${minutes} min`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `dernière : il y a ${hours} h`;
  }
  return `dernière : il y a ${Math.round(hours / 24)} j`;
}

const DAY_SHORT = ['', 'lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.', 'dim.'];
const MOMENT_LABEL: Record<string, string> = { MATIN: 'le matin', APRES_MIDI: 'l\'après-midi', JOURNEE: '' };

/** « suggérée lun. le matin » — ou vide. */
export function suggestionLabel(action: QuickAction): string {
  if (!action.suggestDays?.length) {
    return '';
  }
  const days = action.suggestDays.length === 5 && [1, 2, 3, 4, 5].every((d) => action.suggestDays.includes(d))
    ? 'jours ouvrés'
    : action.suggestDays.map((d) => DAY_SHORT[d] ?? '').filter(Boolean).join(' ');
  const moment = MOMENT_LABEL[action.suggestMoment ?? 'JOURNEE'] ?? '';
  return `suggérée ${days}${moment ? ' ' + moment : ''}`;
}

/**
 * **Les actions rapides d'un terminal** (F-275 / SF-275-01). `skills=false` : les actions personnalisées seules, sans
 * lecture du poste (suggestion au chargement du terminal) ; la liste complète se lit à l'ouverture du menu.
 */
@Injectable({ providedIn: 'root' })
export class QuickActionsService {
  private readonly http = inject(HttpClient);

  list(workspaceId: string, withSkills = true): Observable<QuickAction[]> {
    const params = new HttpParams().set('skills', String(withSkills));
    return this.http.get<QuickAction[]>(`/api/workspaces/${workspaceId}/quick-actions`, { params });
  }

  recordLaunch(workspaceId: string, key: string): Observable<QuickActionLaunch> {
    return this.http.post<QuickActionLaunch>(`/api/workspaces/${workspaceId}/quick-actions/launches`, { key });
  }
}
