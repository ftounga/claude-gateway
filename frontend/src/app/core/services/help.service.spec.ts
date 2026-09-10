import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { HelpService } from './help.service';

describe('HelpService', () => {
  let service: HelpService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(HelpService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('poste la question sur /api/help/chat et rend la réponse', () => {
    let answer: string | undefined;
    service.chat('comment appairer ?').subscribe((response) => (answer = response.answer));

    const request = httpMock.expectOne('/api/help/chat');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ message: 'comment appairer ?' });

    request.flush({ answer: 'Depuis la Forge, bouton Connecter une machine.' });

    expect(answer).toBe('Depuis la Forge, bouton Connecter une machine.');
  });
});
