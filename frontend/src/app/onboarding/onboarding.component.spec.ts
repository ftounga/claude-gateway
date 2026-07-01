import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { OnboardingComponent } from './onboarding.component';
import { AuthService } from '../core/services/auth.service';
import { OnboardingService } from '../core/services/onboarding.service';
import { UserProfile } from '../core/models/auth.models';

describe('OnboardingComponent', () => {
  let fixture: ComponentFixture<OnboardingComponent>;
  let component: OnboardingComponent;
  let authService: jasmine.SpyObj<AuthService>;
  let onboarding: jasmine.SpyObj<OnboardingService>;
  let router: Router;

  const profile: UserProfile = {
    id: '1',
    email: 'consultant@ng.com',
    emailVerified: false,
    provider: 'LOCAL',
    role: 'USER',
  };

  function setup(meFails = false): void {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['me']);
    authService.me.and.returnValue(
      meFails ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(profile),
    );
    onboarding = jasmine.createSpyObj<OnboardingService>('OnboardingService', ['complete']);

    TestBed.configureTestingModule({
      imports: [OnboardingComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: OnboardingService, useValue: onboarding },
      ],
    });
    fixture = TestBed.createComponent(OnboardingComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    fixture.detectChanges();
  }

  it("charge l'e-mail du compte à l'étape 1", () => {
    setup();
    expect(component.email()).toBe('consultant@ng.com');
    expect(component.emailVerified()).toBeFalse();
  });

  it('gère une erreur de chargement du profil sans casser le parcours', () => {
    setup(true);
    expect(component.email()).toBeNull();
    expect(component).toBeTruthy();
  });

  it('Hosted : complète en HOSTED et route vers /chat', () => {
    setup();
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);
    component.selectMode('HOSTED');
    component.finish();
    expect(onboarding.complete).toHaveBeenCalledWith('HOSTED');
    expect(navigateSpy).toHaveBeenCalledWith(['/chat']);
  });

  it('BYOK : complète en BYOK et route vers /billing', () => {
    setup();
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);
    component.selectMode('BYOK');
    component.finish();
    expect(onboarding.complete).toHaveBeenCalledWith('BYOK');
    expect(navigateSpy).toHaveBeenCalledWith(['/billing']);
  });

  it('ne navigue pas si aucun mode sélectionné', () => {
    setup();
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);
    component.finish();
    expect(onboarding.complete).not.toHaveBeenCalled();
    expect(navigateSpy).not.toHaveBeenCalled();
  });

  it('« passer » : complète en HOSTED et route vers /chat', () => {
    setup();
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);
    component.skip();
    expect(onboarding.complete).toHaveBeenCalledWith('HOSTED');
    expect(navigateSpy).toHaveBeenCalledWith(['/chat']);
  });
});
