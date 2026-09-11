import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { SeatService } from './seat.service';
import { SeatsView } from '../models/seat.models';

/**
 * Le contrat de lecture des postes comptés (F-65 / SF-65-02).
 *
 * <p>Ce qui s'y vérifie tient en une ligne, et c'est la règle d'isolation : la requête n'emporte
 * <b>aucun identifiant d'utilisateur</b>. Le serveur le prend du jeton, et lui seul décide quels
 * postes existent.</p>
 */
describe('SeatService', () => {
  let service: SeatService;
  let http: HttpTestingController;

  const seats: SeatsView = {
    includedSeats: 1,
    countedSeats: 2,
    extraSeats: 1,
    grantedTokens: 300000,
    billed: false,
    displayPrice: '',
    periodStart: '2026-09-01',
    periodEnd: '2026-10-01',
    seats: [],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), SeatService],
    });
    service = TestBed.inject(SeatService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lit les postes comptés sans transmettre aucun identifiant', () => {
    let received: SeatsView | undefined;
    service.getSeats().subscribe((view) => (received = view));

    const request = http.expectOne('/api/billing/seats');
    expect(request.request.method).toBe('GET');
    expect(request.request.params.keys().length).toBe(0);
    request.flush(seats);

    expect(received).toEqual(seats);
  });
});
