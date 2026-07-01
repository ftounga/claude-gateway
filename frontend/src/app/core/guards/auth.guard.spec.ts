import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree } from '@angular/router';
import { provideRouter } from '@angular/router';

import { authGuard } from './auth.guard';
import { AuthService } from '../services/auth.service';

describe('authGuard', () => {
  const authServiceStub = {
    authenticated: false,
    isAuthenticated() {
      return this.authenticated;
    },
  };

  function runGuard(): boolean | UrlTree {
    return TestBed.runInInjectionContext(() =>
      authGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    ) as boolean | UrlTree;
  }

  beforeEach(() => {
    authServiceStub.authenticated = false;
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: AuthService, useValue: authServiceStub }],
    });
  });

  it('redirects to /login when unauthenticated', () => {
    const result = runGuard();
    expect(result instanceof UrlTree).toBeTrue();
    expect((result as UrlTree).toString()).toBe(TestBed.inject(Router).createUrlTree(['/login']).toString());
  });

  it('allows access when authenticated', () => {
    authServiceStub.authenticated = true;
    expect(runGuard()).toBeTrue();
  });
});
