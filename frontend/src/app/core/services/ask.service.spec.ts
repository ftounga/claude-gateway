import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { AskService } from './ask.service';
import { AskResponse } from '../models/ask.models';

describe('AskService', () => {
  let service: AskService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AskService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('posts the question body to /api/ask', () => {
    const expected: AskResponse = {
      answer: 'La confidentialité dure 5 ans.',
      model: 'claude-opus-4-8',
      grounded: true,
      citations: [
        {
          documentId: 'doc-1',
          filename: 'contrat.pdf',
          page: null,
          chunkIndex: 0,
          snippet: 'Clause de confidentialité.',
        },
      ],
    };

    let received: AskResponse | undefined;
    service.ask({ question: 'Quelle confidentialité ?' }).subscribe((res) => (received = res));

    const req = httpMock.expectOne('/api/ask');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ question: 'Quelle confidentialité ?' });

    req.flush(expected);
    expect(received).toEqual(expected);
  });
});
