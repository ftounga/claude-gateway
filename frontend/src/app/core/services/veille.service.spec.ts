import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { VeilleService } from './veille.service';

/** **Les veilles d'un sujet** (F-230) : les routes du contrat, sans identifiant de compte. */
describe('VeilleService (F-230)', () => {
  let service: VeilleService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(VeilleService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('appelle les routes du contrat', () => {
    service.list('w-1').subscribe();
    http.expectOne({ method: 'GET', url: '/api/workspaces/w-1/watches' }).flush([]);
    service.test('w-1', 'v-1').subscribe();
    http.expectOne({ method: 'POST', url: '/api/workspaces/w-1/watches/v-1/test' }).flush({});
    service.accept('w-1', 'v-1').subscribe();
    http.expectOne({ method: 'POST', url: '/api/workspaces/w-1/watches/v-1/accept' }).flush({});
    service.runNow('w-1', 'v-1').subscribe();
    http.expectOne({ method: 'POST', url: '/api/workspaces/w-1/watches/v-1/run' }).flush({});
    service.history('w-1', 'v-1').subscribe();
    http.expectOne({ method: 'GET', url: '/api/workspaces/w-1/watches/v-1/runs' }).flush([]);
    service.alert('a-1').subscribe();
    http.expectOne({ method: 'GET', url: '/api/jarvis/alerts/a-1' }).flush({});
    service.openAlert('a-1').subscribe();
    http.expectOne({ method: 'POST', url: '/api/jarvis/alerts/a-1/open' }).flush(null);
    service.update('w-1', 'v-1', { active: false }).subscribe();
    http.expectOne({ method: 'PUT', url: '/api/workspaces/w-1/watches/v-1' }).flush({});
    service.listForAccount('h-1').subscribe();
    http.expectOne((r) => r.method === 'GET' && r.url === '/api/jarvis/watches' && r.params.get('hostId') === 'h-1')
      .flush([]);
    service.remove('w-1', 'v-1').subscribe();
    http.expectOne({ method: 'DELETE', url: '/api/workspaces/w-1/watches/v-1' }).flush(null);
  });
});
