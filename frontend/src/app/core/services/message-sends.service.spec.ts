import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { MessageSendsService } from './message-sends.service';

describe('MessageSendsService (F-211 / SF-211-02)', () => {
  let service: MessageSendsService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(MessageSendsService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('relit les envois du terminal', () => {
    service.list('w1').subscribe();
    const req = http.expectOne('/api/workspaces/w1/messages/sends');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('marque et annule un envoi par message et rang', () => {
    service.mark('w1', 'm1', 2).subscribe();
    const post = http.expectOne('/api/workspaces/w1/messages/m1/sends/2');
    expect(post.request.method).toBe('POST');
    post.flush({});

    service.cancel('w1', 'm1', 2).subscribe();
    const del = http.expectOne('/api/workspaces/w1/messages/m1/sends/2');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
  });
});
