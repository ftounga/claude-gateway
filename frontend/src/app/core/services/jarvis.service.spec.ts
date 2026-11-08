import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';

import { AuthService } from './auth.service';
import { JarvisService } from './jarvis.service';

describe('JarvisService — mesure du rendu (F-253 / SF-253-08)', () => {
  const report = { mode: '3d' as const, render: { sec_high: 2 } };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { token: () => 'jeton' } }] });
  });

  it('à la fermeture : fetch keepalive authentifié, pas HttpClient', () => {
    const sent = spyOn(window, 'fetch').and.resolveTo(new Response(null, { status: 204 }));
    TestBed.inject(JarvisService).render(report, true);
    const [url, init] = sent.calls.mostRecent().args as [string, RequestInit];
    expect(url).toBe('/api/jarvis/usage');
    expect(init.keepalive).toBeTrue();
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer jeton');
    expect(JSON.parse(init.body as string)).toEqual({ event: 'RENDER', ...report });
    TestBed.inject(HttpTestingController).expectNone('/api/jarvis/usage');
  });

  it('un échec de fetch à la fermeture ne remonte pas', async () => {
    spyOn(window, 'fetch').and.rejectWith(new Error('coupé'));
    expect(() => TestBed.inject(JarvisService).render(report, true)).not.toThrow();
    await Promise.resolve();
  });

  it('sortie d’écran normale : HttpClient', () => {
    const sent = spyOn(window, 'fetch');
    TestBed.inject(JarvisService).render(report);
    TestBed.inject(HttpTestingController).expectOne('/api/jarvis/usage').flush(null);
    expect(sent).not.toHaveBeenCalled();
  });
});
