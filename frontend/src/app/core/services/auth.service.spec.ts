import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';

import { AuthService } from './auth.service';
import { AuthResponse, UserProfile } from '../models/auth.models';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  const user: UserProfile = {
    id: 'u1',
    email: 'alice@example.com',
    emailVerified: false,
    provider: 'LOCAL',
    role: 'USER',
  };

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [AuthService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
  });

  it('is unauthenticated by default', () => {
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('stores the JWT on login', () => {
    const response: AuthResponse = { accessToken: 'jwt-123', tokenType: 'Bearer', user };

    let received: AuthResponse | undefined;
    service.login({ email: 'alice@example.com', password: 'password123' }).subscribe((r) => {
      received = r;
    });

    const req = httpMock.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    req.flush(response);

    expect(received).toEqual(response);
    expect(service.token()).toBe('jwt-123');
    expect(service.isAuthenticated()).toBeTrue();
  });

  it('clears the JWT on logout', () => {
    service.storeToken('jwt-123');

    service.logout().subscribe();
    httpMock.expectOne('/api/me/logout').flush({ message: 'ok' });

    expect(service.token()).toBeNull();
    expect(service.isAuthenticated()).toBeFalse();
  });

  it('clears the JWT on logout-all', () => {
    service.storeToken('jwt-123');

    service.logoutAll().subscribe();
    httpMock.expectOne('/api/me/logout-all').flush({ message: 'ok' });

    expect(service.token()).toBeNull();
  });

  it('registers via POST /api/auth/register', () => {
    service.register({ email: 'bob@example.com', password: 'password123' }).subscribe();
    const req = httpMock.expectOne('/api/auth/register');
    expect(req.request.method).toBe('POST');
    req.flush(user);
  });

  it('verifies an email token via GET with the token param', () => {
    service.verifyEmail('tok').subscribe();
    const req = httpMock.expectOne((r) => r.url === '/api/auth/verify');
    expect(req.request.params.get('token')).toBe('tok');
    req.flush({ verified: true, email: 'alice@example.com' });
  });

  it('requests a password reset link', () => {
    service.forgotPassword('alice@example.com').subscribe();
    httpMock.expectOne('/api/auth/password/forgot').flush({ message: 'ok' });
  });

  it('resets the password', () => {
    service.resetPassword('tok', 'newpass12').subscribe();
    const req = httpMock.expectOne('/api/auth/password/reset');
    expect(req.request.body).toEqual({ token: 'tok', password: 'newpass12' });
    req.flush({ message: 'ok' });
  });

  it('updates the profile email', () => {
    service.updateProfile({ email: 'new@example.com' }).subscribe();
    const req = httpMock.expectOne('/api/me');
    expect(req.request.method).toBe('PUT');
    req.flush({ ...user, email: 'new@example.com' });
  });
});
