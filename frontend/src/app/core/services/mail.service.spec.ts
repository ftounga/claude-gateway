import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { MailService } from './mail.service';

/** Le courriel du client : URLs, méthodes, corps (F-110 / SF-110-01). Aucun destinataire n'est jamais envoyé. */
describe('MailService', () => {
  let service: MailService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(MailService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it("appelle l'adresse de réception du poste", () => {
    const base = '/api/runner-hosts/h1/mail-address';
    service.address('h1').subscribe();
    expect(http.expectOne({ method: 'GET', url: base }).request.body).toBeNull();

    service.declare('h1', 'franck@cagip.fr').subscribe();
    expect(http.expectOne({ method: 'PUT', url: base }).request.body).toEqual({ address: 'franck@cagip.fr' });

    service.verify('h1', '123456').subscribe();
    expect(http.expectOne({ method: 'POST', url: `${base}/verify` }).request.body).toEqual({ code: '123456' });

    service.resend('h1').subscribe();
    http.expectOne({ method: 'POST', url: `${base}/code` });

    service.remove('h1').subscribe();
    http.expectOne({ method: 'DELETE', url: base });

    service.email('e1').subscribe();
    http.expectOne({ method: 'GET', url: '/api/client-emails/e1' });
  });
});
