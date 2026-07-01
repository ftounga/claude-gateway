import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { ProfileComponent } from './profile.component';
import { AuthService } from '../../core/services/auth.service';
import { UserProfile } from '../../core/models/auth.models';

describe('ProfileComponent', () => {
  let fixture: ComponentFixture<ProfileComponent>;
  let component: ProfileComponent;
  let authService: jasmine.SpyObj<AuthService>;
  let router: Router;

  const profile: UserProfile = {
    id: 'u1',
    email: 'alice@example.com',
    emailVerified: true,
    provider: 'LOCAL',
    role: 'USER',
  };

  beforeEach(async () => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', [
      'me',
      'updateProfile',
      'logout',
      'logoutAll',
      'clearToken',
    ]);
    authService.me.and.returnValue(of(profile));

    await TestBed.configureTestingModule({
      imports: [ProfileComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: AuthService, useValue: authService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ProfileComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    fixture.detectChanges();
  });

  it('loads the current profile on init', () => {
    expect(authService.me).toHaveBeenCalled();
    expect(component.profile()).toEqual(profile);
    expect(component.form.controls.email.value).toBe('alice@example.com');
  });

  it('logs out and redirects to /login', () => {
    authService.logout.and.returnValue(of({ message: 'ok' }));
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);

    component.logout();

    expect(authService.logout).toHaveBeenCalled();
    expect(authService.clearToken).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/login']);
  });

  it('logs out all sessions and redirects to /login', () => {
    authService.logoutAll.and.returnValue(of({ message: 'ok' }));
    const navigateSpy = spyOn(router, 'navigate').and.resolveTo(true);

    component.logoutAll();

    expect(authService.logoutAll).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith(['/login']);
  });
});
