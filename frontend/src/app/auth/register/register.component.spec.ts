import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { RegisterComponent } from './register.component';
import { AuthService } from '../../core/services/auth.service';

describe('RegisterComponent', () => {
  let fixture: ComponentFixture<RegisterComponent>;
  let component: RegisterComponent;
  let authService: jasmine.SpyObj<AuthService>;

  beforeEach(async () => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['register']);

    await TestBed.configureTestingModule({
      imports: [RegisterComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: AuthService, useValue: authService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(RegisterComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('shows the confirmation state after a successful registration', () => {
    authService.register.and.returnValue(
      of({ id: '1', email: 'new@example.com', emailVerified: false, provider: 'LOCAL', role: 'USER' }),
    );
    component.form.setValue({ email: 'new@example.com', password: 'password123' });

    component.submit();

    expect(authService.register).toHaveBeenCalled();
    expect(component.registeredEmail()).toBe('new@example.com');
  });

  it('rejects a password shorter than 8 characters', () => {
    component.form.setValue({ email: 'new@example.com', password: 'short' });
    expect(component.form.controls.password.hasError('minlength')).toBeTrue();
  });
});
