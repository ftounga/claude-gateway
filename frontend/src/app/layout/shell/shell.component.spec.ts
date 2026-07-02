import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { ShellComponent } from './shell.component';
import { AuthService } from '../../core/services/auth.service';

describe('ShellComponent', () => {
  let fixture: ComponentFixture<ShellComponent>;
  let authSpy: jasmine.SpyObj<AuthService>;
  let router: Router;

  beforeEach(async () => {
    authSpy = jasmine.createSpyObj<AuthService>('AuthService', ['logout']);
    authSpy.logout.and.returnValue(
      of({ message: 'ok' }) as unknown as ReturnType<AuthService['logout']>,
    );

    await TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: AuthService, useValue: authSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ShellComponent);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture.detectChanges();
  });

  it('affiche les liens de navigation vers les sections principales', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Chat');
    expect(text).toContain('Documents');
    expect(text).toContain('Templates');
  });

  it('se déconnecte et redirige vers /login', () => {
    fixture.componentInstance.logout();

    expect(authSpy.logout).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });
});
