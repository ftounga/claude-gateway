import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { RoutineService } from './routine.service';

/** F-183 — le service des routines appelle les routes du sujet, sans identifiant de compte. */
describe('RoutineService (F-183)', () => {
  let service: RoutineService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(RoutineService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('liste, crée, modifie, supprime et valide', () => {
    service.list('w-1').subscribe();
    http.expectOne({ method: 'GET', url: '/api/workspaces/w-1/routines' }).flush([]);
    service.create('w-1', { name: 'n' }).subscribe();
    http.expectOne({ method: 'POST', url: '/api/workspaces/w-1/routines' }).flush({});
    service.update('w-1', 'r-1', { active: false }).subscribe();
    http.expectOne({ method: 'PUT', url: '/api/workspaces/w-1/routines/r-1' }).flush({});
    service.remove('w-1', 'r-1').subscribe();
    http.expectOne({ method: 'DELETE', url: '/api/workspaces/w-1/routines/r-1' }).flush(null);
    service.runNow('w-1', 'r-1').subscribe();
    http.expectOne({ method: 'POST', url: '/api/workspaces/w-1/routines/r-1/run' }).flush({});
    service.history('w-1', 'r-1').subscribe();
    http.expectOne({ method: 'GET', url: '/api/workspaces/w-1/routines/r-1/runs' }).flush([]);
    service.accept('w-1', 'r-1').subscribe();
    http.expectOne({ method: 'POST', url: '/api/workspaces/w-1/routines/r-1/accept' }).flush({});
  });
});
