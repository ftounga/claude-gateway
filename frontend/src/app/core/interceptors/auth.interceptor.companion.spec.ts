import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, provideRouter } from '@angular/router';

import { authInterceptor } from './auth.interceptor';
import { authGuard } from '../guards/auth.guard';
import { AuthService } from '../services/auth.service';
import { CompanionNative } from '../services/companion-bridge.service';

/** F-232 / SF-232-07 — dans le compagnon Android : un 401 redemande une session, jamais `/login`. */
describe('authInterceptor / authGuard dans le compagnon (F-232 / SF-232-07)', () => {
  let native: jasmine.SpyObj<CompanionNative>;
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let router: Router;
  let answer: { token: string; locked: boolean } | null;

  beforeEach(() => {
    answer = { token: 'jwt-neuf', locked: true };
    native = jasmine.createSpyObj<CompanionNative>('native',
      ['requestSession', 'isLocked', 'requestUnlock', 'keepAlive', 'close']);
    native.requestSession.and.callFake(() => window.jarvisCompanionSession?.(answer === null ? null : {
      token: answer.token, locked: answer.locked, scope: 'jarvis-compagnon',
      expiresAt: new Date(Date.now() + 30 * 60_000).toISOString(),
    }));
    window.JarvisCompanion = native;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
  });

  afterEach(() => {
    httpMock.verify();
    TestBed.inject(AuthService).clearToken();
    delete window.JarvisCompanion;
    delete window.jarvisCompanionSession;
    delete window.jarvisCompanionUnlocked;
  });

  it('le garde demande la session au pont et laisse passer', async () => {
    const ok = await TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot)) as unknown as Promise<boolean>;
    expect(ok).toBeTrue();
    expect(native.requestSession).toHaveBeenCalledWith('OPEN');
    expect(TestBed.inject(AuthService).token()).toBe('jwt-neuf');
  });

  it('session refusée : le garde refuse et ferme l’écran du compagnon', async () => {
    answer = null;
    const ok = await TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot)) as unknown as Promise<boolean>;
    expect(ok).toBeFalse();
    expect(native.close).toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('un 401 renouvelle la session au lieu d’aller à /login', async () => {
    TestBed.inject(AuthService).storeToken('jwt-vieux');
    http.get('/api/jarvis/preferences').subscribe({ error: () => undefined });
    const req = httpMock.expectOne('/api/jarvis/preferences');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jwt-vieux');
    req.flush({}, { status: 401, statusText: 'Unauthorized' });
    await Promise.resolve();
    expect(native.requestSession).toHaveBeenCalledWith('RENEW');
    expect(router.navigate).not.toHaveBeenCalled();
    expect(TestBed.inject(AuthService).token()).toBe('jwt-neuf');
  });

  it('un 403 de portée reste une erreur ordinaire (pas de renouvellement)', () => {
    TestBed.inject(AuthService).storeToken('jwt');
    http.get('/api/me').subscribe({ error: () => undefined });
    httpMock.expectOne('/api/me').flush({ error: 'jarvis_companion_scope' }, { status: 403, statusText: 'Forbidden' });
    expect(native.requestSession).not.toHaveBeenCalled();
    expect(native.close).not.toHaveBeenCalled();
  });
});
