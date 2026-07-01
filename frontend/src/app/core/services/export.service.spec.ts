import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { HttpResponse, provideHttpClient } from '@angular/common/http';

import { ExportService } from './export.service';
import { AnswerExportRequest } from '../models/export.models';

describe('ExportService', () => {
  let service: ExportService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ExportService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('requests a conversation export as a blob with the format param', () => {
    service.exportConversation('c-1', 'markdown').subscribe();

    const req = httpMock.expectOne((r) => r.url === '/api/conversations/c-1/export');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('format')).toBe('markdown');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['# export']));
  });

  it('posts an answer export as a blob with the format param and body', () => {
    const body: AnswerExportRequest = {
      question: 'Q ?',
      answer: 'R.',
      model: 'claude-opus-4-8',
      grounded: true,
      citations: [],
    };
    service.exportAnswer(body, 'pdf').subscribe();

    const req = httpMock.expectOne((r) => r.url === '/api/export/answer');
    expect(req.request.method).toBe('POST');
    expect(req.request.params.get('format')).toBe('pdf');
    expect(req.request.responseType).toBe('blob');
    expect(req.request.body).toEqual(body);
    req.flush(new Blob(['%PDF-']));
  });

  it('extracts the filename from Content-Disposition when triggering a download', () => {
    const anchor = document.createElement('a');
    const clickSpy = spyOn(anchor, 'click');
    spyOn(document, 'createElement').and.returnValue(anchor);
    spyOn(document.body, 'appendChild').and.callThrough();
    spyOn(document.body, 'removeChild').and.callThrough();
    spyOn(URL, 'createObjectURL').and.returnValue('blob:fake');
    spyOn(URL, 'revokeObjectURL');

    const response = new HttpResponse<Blob>({
      body: new Blob(['data']),
      headers: undefined,
    });
    // Inject a Content-Disposition header via a clone.
    const withHeader = response.clone({
      headers: response.headers.set('Content-Disposition', 'attachment; filename="conversation-c-1.md"'),
    });

    service.triggerDownload(withHeader, 'fallback.md');

    expect(anchor.download).toBe('conversation-c-1.md');
    expect(clickSpy).toHaveBeenCalled();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:fake');
  });

  it('falls back to the provided name when no Content-Disposition is present', () => {
    const anchor = document.createElement('a');
    spyOn(anchor, 'click');
    spyOn(document, 'createElement').and.returnValue(anchor);
    spyOn(URL, 'createObjectURL').and.returnValue('blob:fake');
    spyOn(URL, 'revokeObjectURL');

    const response = new HttpResponse<Blob>({ body: new Blob(['data']) });
    service.triggerDownload(response, 'reponse.pdf');

    expect(anchor.download).toBe('reponse.pdf');
  });
});
