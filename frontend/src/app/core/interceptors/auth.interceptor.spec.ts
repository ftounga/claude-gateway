import { TestBed } from '@angular/core/testing';
import {
  HttpClient,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from '../services/auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let authService: AuthService;
  let router: Router;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        AuthService,
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    authService = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('adds the Authorization header when a token is present', () => {
    authService.storeToken('jwt-abc');

    http.get('/api/me').subscribe();

    const req = httpMock.expectOne('/api/me');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jwt-abc');
    req.flush({});
  });

  it('does not add a header when no token', () => {
    http.get('/api/me').subscribe();
    const req = httpMock.expectOne('/api/me');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('clears token and redirects to /login on 401 for a protected route', () => {
    authService.storeToken('jwt-abc');
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);

    http.get('/api/me').subscribe({ error: () => undefined });

    httpMock.expectOne('/api/me').flush(
      { error: 'unauthorized', message: 'x' },
      { status: 401, statusText: 'Unauthorized' },
    );

    expect(authService.token()).toBeNull();
    expect(navigateSpy).toHaveBeenCalledWith(['/login']);
  });

  it('does not redirect on 401 from a public /api/auth call', () => {
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);

    http.post('/api/auth/login', {}).subscribe({ error: () => undefined });

    httpMock.expectOne('/api/auth/login').flush(
      { error: 'invalid_credentials', message: 'x' },
      { status: 401, statusText: 'Unauthorized' },
    );

    expect(navigateSpy).not.toHaveBeenCalled();
  });
});
