import { TestBed } from '@angular/core/testing';

import {
  WORKSTATION_NOTICE_DEFAULT_HOURS,
  WorkstationNoticeService,
} from './workstation-notice.service';

const STORAGE_KEY = 'cg_workstation_notice';
const HOUR = 60 * 60 * 1000;

/**
 * F-57 / SF-57-03 — quand le rappel de journalisation est dû, et ce qu'il retient.
 *
 * <p>Un service instancié par `TestBed.inject` lit `localStorage` à la construction : chaque cas
 * écrit donc son état AVANT d'injecter, comme le fait un vrai chargement de page.</p>
 */
describe('WorkstationNoticeService', () => {
  function store(state: unknown): void {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  }

  function service(): WorkstationNoticeService {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({});
    return TestBed.inject(WorkstationNoticeService);
  }

  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
  });

  afterEach(() => {
    localStorage.removeItem(STORAGE_KEY);
  });

  it('est dû quand aucun rappel n’a jamais été acquitté', () => {
    expect(service().isDue(Date.now())).toBeTrue();
  });

  it('n’est pas dû une heure après un acquittement, avec une périodicité de deux heures', () => {
    const now = Date.now();
    store({ version: 1, intervalHours: 2, acknowledgedAt: now - HOUR });

    expect(service().isDue(now)).toBeFalse();
  });

  it('redevient dû trois heures après un acquittement, avec une périodicité de deux heures', () => {
    const now = Date.now();
    store({ version: 1, intervalHours: 2, acknowledgedAt: now - 3 * HOUR });

    expect(service().isDue(now)).toBeTrue();
  });

  it('n’est jamais dû quand la périodicité est « jamais », même sans acquittement', () => {
    store({ version: 1, intervalHours: null, acknowledgedAt: null });

    const notice = service();
    expect(notice.intervalHours()).toBeNull();
    expect(notice.isDue(Date.now())).toBeFalse();
  });

  it('repart le compteur à l’acquittement', () => {
    const notice = service();
    const now = Date.now();

    notice.acknowledge(now);

    expect(notice.isDue(now)).toBeFalse();
    expect(notice.isDue(now + 3 * HOUR)).toBeTrue();
  });

  it('retient la périodicité d’une session à l’autre', () => {
    service().setIntervalHours(24, Date.now());

    expect(service().intervalHours()).toBe(24);
  });

  it('repart le compteur quand la périodicité change', () => {
    // Sans cela, passer de 24 h à 2 h ferait réapparaître le bandeau dans la seconde.
    const now = Date.now();
    store({ version: 1, intervalHours: 24, acknowledgedAt: now - 3 * HOUR });

    const notice = service();
    notice.setIntervalHours(2, now);

    expect(notice.isDue(now)).toBeFalse();
  });

  it('ramène une périodicité mémorisée invalide au défaut', () => {
    store({ version: 1, intervalHours: 7, acknowledgedAt: null });

    expect(service().intervalHours()).toBe(WORKSTATION_NOTICE_DEFAULT_HOURS);
  });

  it('relit un état d’une autre version comme un rappel neuf', () => {
    store({ version: 99, intervalHours: null, acknowledgedAt: Date.now() });

    const notice = service();
    expect(notice.intervalHours()).toBe(WORKSTATION_NOTICE_DEFAULT_HOURS);
    expect(notice.isDue(Date.now())).toBeTrue();
  });

  it('ne boucle pas sur un horodatage postérieur à l’instant présent', () => {
    // Horloge du poste reculée : l'écart devient négatif, et le rappel ne doit ni se figer, ni
    // clignoter. Il est traité comme « acquitté à l'instant ».
    const now = Date.now();
    store({ version: 1, intervalHours: 2, acknowledgedAt: now + 10 * HOUR });

    const notice = service();
    expect(notice.isDue(now)).toBeFalse();
    expect(notice.isDue(now + 3 * HOUR)).toBeTrue();
  });

  it('ne casse rien quand le stockage refuse la lecture et l’écriture', () => {
    const getItem = spyOn(Storage.prototype, 'getItem').and.throwError('stockage verrouillé');
    const setItem = spyOn(Storage.prototype, 'setItem').and.throwError('stockage verrouillé');

    const notice = service();

    expect(notice.intervalHours()).toBe(WORKSTATION_NOTICE_DEFAULT_HOURS);
    expect(notice.isDue(Date.now())).toBeTrue();
    expect(() => notice.acknowledge(Date.now())).not.toThrow();
    // L'acquittement vaut pour la session, même sans écriture possible.
    expect(notice.isDue(Date.now())).toBeFalse();
    expect(getItem).toHaveBeenCalled();
    expect(setItem).toHaveBeenCalled();
  });
});
