import { HttpErrorResponse } from '@angular/common/http';

import { RadarSchedule } from '../../core/models/radar.models';
import {
  browserTimeZone,
  canSave,
  isValidTime,
  needsAuthorization,
  scheduleErrorOf,
  scheduleRequest,
  scheduleSentence,
  slotLabel,
  zoneFor,
} from './radar-schedule';

/** La synchro du soir, en fonctions pures (F-100 / SF-100-07). */
describe('radar-schedule', () => {
  const now = new Date(2026, 8, 13, 15, 0, 0);
  const at = (day: number, hour: number, minute = 0) => new Date(2026, 8, day, hour, minute).toISOString();

  const schedule = (extra: Partial<RadarSchedule> = {}): RadarSchedule => ({
    enabled: true, clientAuthorizedAt: '2026-09-10T08:00:00Z', syncTime: '22:00', timeZone: 'Europe/Paris',
    nextSyncAt: at(13, 22), missedSlotAt: null, running: null, ...extra,
  });

  it('dit un créneau en mots : ce soir, demain, une date', () => {
    expect(slotLabel(at(13, 22), now)).toBe('ce soir 22:00');
    expect(slotLabel(at(13, 9, 30), now)).toBe("aujourd'hui 09:30");
    expect(slotLabel(at(14, 22), now)).toBe('demain 22:00');
    expect(slotLabel(at(20, 21, 15), now)).toMatch(/^20 sept\.? 21:15$/);
    expect(slotLabel(null, now)).toBeNull();
    expect(slotLabel('pas une date', now)).toBeNull();
  });

  it("écrit la ligne d'en-tête : activée, prochaine, en cours, manquée (en attention) ; désactivée", () => {
    expect(scheduleSentence(schedule(), now)).toEqual({ text: 'Synchro du soir à 22:00 · prochaine : ce soir 22:00', attention: false });
    expect(scheduleSentence(schedule({ enabled: false }), now)).toEqual({ text: 'Synchro du soir désactivée', attention: false });

    const missed = scheduleSentence(schedule({
      missedSlotAt: at(12, 22), running: { syncId: 's', trigger: 'MANUAL', startedAt: null, heartbeatAt: null, phase: '', done: 0, total: 0 },
    }), now);
    expect(missed.text).toContain('synchro en cours');
    expect(missed.text).toContain('manquée, rattrapée à la prochaine connexion');
    expect(missed.attention).toBeTrue();
  });

  it("exige l'autorisation du client à la première activation seulement", () => {
    expect(needsAuthorization(schedule({ clientAuthorizedAt: null }), true)).toBeTrue();
    expect(needsAuthorization(schedule({ clientAuthorizedAt: null }), false)).toBeFalse();
    expect(needsAuthorization(null, true)).toBeTrue();
    expect(needsAuthorization(schedule(), true)).toBeFalse();

    expect(canSave(schedule({ clientAuthorizedAt: null }), true, '22:00', false)).toBeFalse();
    expect(canSave(schedule({ clientAuthorizedAt: null }), true, '22:00', true)).toBeTrue();
    expect(canSave(schedule(), false, '22:00', false)).toBeTrue();
    expect(canSave(schedule(), true, '24:00', false)).toBeFalse();
  });

  it('valide une heure HH:mm', () => {
    expect(['00:00', '09:05', '23:59'].every(isValidTime)).toBeTrue();
    expect(['24:00', '9:05', '', null, '22h00'].some((v) => isValidTime(v))).toBeFalse();
  });

  it('garde le fuseau enregistré une fois autorisé ; celui du navigateur avant', () => {
    expect(zoneFor(schedule({ timeZone: 'America/New_York' }), 'Europe/Paris')).toBe('America/New_York');
    expect(zoneFor(schedule({ clientAuthorizedAt: null }), 'Asia/Tokyo')).toBe('Asia/Tokyo');
    expect(zoneFor(null, '')).toBe('Europe/Paris');
    expect(browserTimeZone().length).toBeGreaterThan(0);

    expect(scheduleRequest(schedule({ clientAuthorizedAt: null }), true, '21:30', true, 'Europe/Paris'))
      .toEqual({ enabled: true, syncTime: '21:30', timeZone: 'Europe/Paris', clientAuthorizationConfirmed: true });
  });

  it('traduit un enregistrement refusé', () => {
    expect(scheduleErrorOf(new HttpErrorResponse({ status: 400, error: { error: 'radar_invalid', message: 'Fuseau horaire inconnu : X.' } })))
      .toBe('Fuseau horaire inconnu : X.');
    expect(scheduleErrorOf(new HttpErrorResponse({ status: 403 }))).toContain("n'est pas disponible");
    expect(scheduleErrorOf(new HttpErrorResponse({ status: 0 }))).toContain("Rien n'a changé");
  });
});
