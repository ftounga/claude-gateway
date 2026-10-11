import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { PlumeService } from './plume.service';

describe('PlumeService (F-193 / SF-193-02)', () => {
  it('POST /api/workspaces/{id}/enrich-prompt avec le texte saisi', () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const service = TestBed.inject(PlumeService);
    const http = TestBed.inject(HttpTestingController);
    let prompt = '';

    service.enrich('ws-9', 'ajoute X').subscribe((r) => (prompt = r.enrichedPrompt));
    const req = http.expectOne('/api/workspaces/ws-9/enrich-prompt');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ text: 'ajoute X' });
    req.flush({ enrichedPrompt: 'Objectif : X', toConfirm: [], sources: [] });

    expect(prompt).toBe('Objectif : X');
    http.verify();
  });
});
