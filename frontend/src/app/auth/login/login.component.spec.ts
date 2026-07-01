import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { LoginComponent } from './login.component';
import { AuthService } from '../../core/services/auth.service';
import { OnboardingService } from '../../core/services/onboarding.service';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let authService: jasmine.SpyObj<AuthService>;
  let onboarding: jasmine.SpyObj<OnboardingService>;
  let router: Router;

  beforeEach(async () => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['login'], {
      googleLoginUrl: '/api/oauth2/authorization/google',
    });
    onboarding = jasmine.createSpyObj<OnboardingService>('OnboardingService', ['postLoginPath']);
    onboarding.postLoginPath.and.returnValue('/onboarding');

    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: AuthService, useValue: authService },
        { provide: OnboardingService, useValue: onboarding },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    fixture.detectChanges();
  });

  it('does not call the service when the form is invalid', () => {
    component.submit();
    expect(authService.login).not.toHaveBeenCalled();
  });

  it('logs in and navigates via the onboarding-aware redirect on success', () => {
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);
    authService.login.and.returnValue(
      of({
        accessToken: 't',
        tokenType: 'Bearer',
        user: { id: '1', email: 'a@b.c', emailVerified: true, provider: 'LOCAL', role: 'USER' },
      }),
    );
    component.form.setValue({ email: 'a@b.c', password: 'password123' });

    component.submit();

    expect(authService.login).toHaveBeenCalled();
    expect(onboarding.postLoginPath).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/onboarding']);
  });

  it('navigates to /profile when onboarding is already completed', () => {
    onboarding.postLoginPath.and.returnValue('/profile');
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);
    authService.login.and.returnValue(
      of({
        accessToken: 't',
        tokenType: 'Bearer',
        user: { id: '1', email: 'a@b.c', emailVerified: true, provider: 'LOCAL', role: 'USER' },
      }),
    );
    component.form.setValue({ email: 'a@b.c', password: 'password123' });

    component.submit();

    expect(navigateSpy).toHaveBeenCalledWith(['/profile']);
  });

  it('resets submitting state on error', () => {
    authService.login.and.returnValue(throwError(() => new Error('401')));
    component.form.setValue({ email: 'a@b.c', password: 'bad' });

    component.submit();

    expect(component.submitting()).toBeFalse();
  });
});
