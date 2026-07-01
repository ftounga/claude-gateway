import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { signal } from '@angular/core';

import { LandingComponent } from './landing.component';
import { AuthService } from '../core/services/auth.service';

describe('LandingComponent', () => {
  let fixture: ComponentFixture<LandingComponent>;
  let component: LandingComponent;
  const authenticated = signal(false);

  function setup(isAuth: boolean): void {
    authenticated.set(isAuth);
    TestBed.configureTestingModule({
      imports: [LandingComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AuthService, useValue: { isAuthenticated: authenticated } },
      ],
    });
    fixture = TestBed.createComponent(LandingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('se crée sans appel réseau (AuthService sans HttpClient)', () => {
    setup(false);
    expect(component).toBeTruthy();
  });

  it('non authentifié : propose l\'inscription (essai) et la connexion', () => {
    setup(false);
    const html = fixture.nativeElement as HTMLElement;
    const links = Array.from(html.querySelectorAll('a[href]')).map((a) => a.getAttribute('href'));
    expect(links).toContain('/register');
    expect(links).toContain('/login');
    expect(links).not.toContain('/chat');
  });

  it('authentifié : propose d\'ouvrir le chat plutôt que de s\'inscrire', () => {
    setup(true);
    const html = fixture.nativeElement as HTMLElement;
    const links = Array.from(html.querySelectorAll('a[href]')).map((a) => a.getAttribute('href'));
    expect(links).toContain('/chat');
    expect(links).not.toContain('/register');
  });

  it('affiche les trois bénéfices consultants', () => {
    setup(false);
    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll('.landing__benefit');
    expect(cards.length).toBe(3);
  });
});
