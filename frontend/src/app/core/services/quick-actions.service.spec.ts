import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import {
  QuickAction,
  QuickActionsService,
  isSuggestedNow,
  lastRunLabel,
  suggestionLabel,
} from './quick-actions.service';

function quickAction(over: Partial<QuickAction> = {}): QuickAction {
  return {
    key: 'c:1',
    source: 'PERSO',
    id: '1',
    label: 'Point hebdo',
    phrase: 'point hebdo',
    skill: null,
    scope: 'POSTE',
    targetWorkspaceId: null,
    targetName: null,
    targetMissing: false,
    clickMode: 'DEPOSER',
    suggestDays: [],
    suggestMoment: null,
    lastRunAt: null,
    ...over,
  };
}

/** Lundi 23 novembre 2026, 9 h et 15 h (heure locale). */
const MONDAY_9 = new Date(2026, 10, 23, 9, 0);
const MONDAY_15 = new Date(2026, 10, 23, 15, 0);

describe('F-275 — actions rapides : suggestion et dernière exécution', () => {
  it('suggère une action de son jour et de son moment, jamais sans jour', () => {
    const matin = quickAction({ suggestDays: [1], suggestMoment: 'MATIN' });
    expect(isSuggestedNow(matin, MONDAY_9)).toBeTrue();
    expect(isSuggestedNow(matin, MONDAY_15)).toBeFalse();
    expect(isSuggestedNow(quickAction({ suggestDays: [1], suggestMoment: 'APRES_MIDI' }), MONDAY_15)).toBeTrue();
    expect(isSuggestedNow(quickAction({ suggestDays: [1], suggestMoment: 'JOURNEE' }), MONDAY_15)).toBeTrue();
    expect(isSuggestedNow(quickAction({ suggestDays: [2], suggestMoment: 'MATIN' }), MONDAY_9)).toBeFalse();
    expect(isSuggestedNow(quickAction(), MONDAY_9)).toBeFalse();
  });

  it('ne suggère plus une action déjà lancée aujourd\'hui', () => {
    const done = quickAction({ suggestDays: [1], suggestMoment: 'MATIN',
      lastRunAt: new Date(2026, 10, 23, 8, 30).toISOString() });
    expect(isSuggestedNow(done, MONDAY_9)).toBeFalse();
    const lastWeek = quickAction({ suggestDays: [1], suggestMoment: 'MATIN',
      lastRunAt: new Date(2026, 10, 16, 8, 30).toISOString() });
    expect(isSuggestedNow(lastWeek, MONDAY_9)).toBeTrue();
  });

  it('dit la dernière exécution en clair', () => {
    expect(lastRunLabel(null, MONDAY_9)).toBe('jamais lancée');
    expect(lastRunLabel(new Date(2026, 10, 23, 8, 50).toISOString(), MONDAY_9)).toBe('dernière : il y a 10 min');
    expect(lastRunLabel(new Date(2026, 10, 23, 6, 0).toISOString(), MONDAY_9)).toBe('dernière : il y a 3 h');
    expect(lastRunLabel(new Date(2026, 10, 21, 9, 0).toISOString(), MONDAY_9)).toBe('dernière : il y a 2 j');
  });

  it('dit la suggestion en clair', () => {
    expect(suggestionLabel(quickAction())).toBe('');
    expect(suggestionLabel(quickAction({ suggestDays: [1], suggestMoment: 'MATIN' }))).toBe('suggérée lun. le matin');
    expect(suggestionLabel(quickAction({ suggestDays: [1, 2, 3, 4, 5], suggestMoment: 'JOURNEE' })))
      .toBe('suggérée jours ouvrés');
  });
});

describe('F-275 — QuickActionsService', () => {
  let service: QuickActionsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(QuickActionsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lit la liste légère sans skills, puis la complète', () => {
    service.list('w1', false).subscribe();
    http.expectOne('/api/workspaces/w1/quick-actions?skills=false').flush([]);
    service.list('w1').subscribe();
    http.expectOne('/api/workspaces/w1/quick-actions?skills=true').flush([]);
  });

  it('enregistre un lancement par sa clé', () => {
    service.recordLaunch('w1', 'c:1').subscribe();
    const req = http.expectOne('/api/workspaces/w1/quick-actions/launches');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ key: 'c:1' });
    req.flush({ key: 'c:1', lastRunAt: '2026-11-23T08:00:00Z' });
  });
});
