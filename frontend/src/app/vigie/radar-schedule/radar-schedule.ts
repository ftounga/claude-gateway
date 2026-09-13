import { HttpErrorResponse } from '@angular/common/http';

import { RadarSchedule, RadarScheduleRequest } from '../../core/models/radar.models';
import { httpErrorMessage } from '../../shared/http-error.util';

/**
 * **La synchro du soir, en fonctions pures** (F-100 / SF-100-07) : la phrase d'en-tête, les créneaux en mots,
 * la règle d'autorisation et le corps du réglage. Sans Angular ni HTTP.
 */

/** Fuseau de repli, celui de la gateway. */
export const DEFAULT_TIME_ZONE = 'Europe/Paris';

/** Heure par défaut, celle de la gateway. */
export const DEFAULT_SYNC_TIME = '22:00';

const pad = (n: number) => String(n).padStart(2, '0');

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** Un créneau en mots, à l'heure du navigateur : « ce soir 22:00 », « demain 22:00 », « 15 sept. 22:00 ». */
export function slotLabel(iso: string | null | undefined, now: Date = new Date()): string | null {
  if (!iso) {
    return null;
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  if (sameDay(date, now)) {
    return `${date.getHours() >= 17 ? 'ce soir' : "aujourd'hui"} ${time}`;
  }
  const tomorrow = new Date(now);
  tomorrow.setDate(now.getDate() + 1);
  if (sameDay(date, tomorrow)) {
    return `demain ${time}`;
  }
  return `${date.toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' })} ${time}`;
}

/** Ce que dit la ligne d'en-tête, et si elle réclame l'attention (§12). */
export interface ScheduleSentence {
  text: string;
  attention: boolean;
}

export function scheduleSentence(schedule: RadarSchedule, now: Date = new Date()): ScheduleSentence {
  if (!schedule.enabled) {
    return { text: 'Synchro du soir désactivée', attention: false };
  }
  const parts = [`Synchro du soir à ${schedule.syncTime}`];
  const next = slotLabel(schedule.nextSyncAt, now);
  if (next) {
    parts.push(`prochaine : ${next}`);
  }
  if (schedule.running) {
    parts.push('synchro en cours');
  }
  const missed = slotLabel(schedule.missedSlotAt, now);
  if (missed) {
    parts.push(`synchro de ${missed} manquée, rattrapée à la prochaine connexion`);
  }
  return { text: parts.join(' · '), attention: !!missed };
}

/** La première activation exige que l'utilisateur confirme l'autorisation de son client (§14). */
export function needsAuthorization(schedule: RadarSchedule | null, enabled: boolean): boolean {
  return enabled && !schedule?.clientAuthorizedAt;
}

/** `HH:mm`, de 00:00 à 23:59. */
export function isValidTime(value: string | null | undefined): boolean {
  return /^([01]\d|2[0-3]):[0-5]\d$/.test(value ?? '');
}

/** Le fuseau du navigateur, ou le repli. */
export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || DEFAULT_TIME_ZONE;
  } catch {
    return DEFAULT_TIME_ZONE;
  }
}

/**
 * Le fuseau à enregistrer : celui du réglage une fois l'autorisation donnée (un autre navigateur ne déplace
 * pas le créneau en silence), celui du navigateur tant que le Radar n'a jamais été activé.
 */
export function zoneFor(schedule: RadarSchedule | null, browserZone: string): string {
  return schedule?.clientAuthorizedAt ? schedule.timeZone || DEFAULT_TIME_ZONE : browserZone || DEFAULT_TIME_ZONE;
}

/** Le corps de `PUT …/schedule`. */
export function scheduleRequest(schedule: RadarSchedule | null, enabled: boolean, syncTime: string,
  authorizationConfirmed: boolean, browserZone: string): RadarScheduleRequest {
  return {
    enabled,
    syncTime,
    timeZone: zoneFor(schedule, browserZone),
    clientAuthorizationConfirmed: authorizationConfirmed,
  };
}

/** Ce que le dialogue dit d'un enregistrement refusé : le message de la gateway pour un réglage invalide. */
export function scheduleErrorOf(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 400) {
      return httpErrorMessage(err, 'Ce réglage est invalide.');
    }
    if (err.status === 403 || err.status === 404 || err.status === 409) {
      return "Le réglage n'est pas disponible pour ce client.";
    }
  }
  return "Le réglage n'a pas pu être enregistré. Rien n'a changé.";
}

/** Peut-on enregistrer ce réglage ? */
export function canSave(schedule: RadarSchedule | null, enabled: boolean, syncTime: string,
  authorizationConfirmed: boolean): boolean {
  return isValidTime(syncTime) && (!needsAuthorization(schedule, enabled) || authorizationConfirmed);
}
