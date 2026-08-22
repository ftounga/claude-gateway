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

  // ---- F-29 SF-29-01 : garde-fous anti-régression sur l'identité publique ----
  // Le terme « Proxy » et le registre lexical du contournement font classer le domaine
  // en catégorie « anonymizer / proxy avoidance » par les filtres d'entreprise.

  it("ne contient nulle part le terme « Proxy » (classification anonymizer)", () => {
    setup(false);
    const markup = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(markup).not.toMatch(/proxy/i);
  });

  it("n'emploie pas le registre lexical du contournement", () => {
    setup(false);
    const markup = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(markup).not.toMatch(/unrestricted|no limits|bypass|unblock|anonymous/i);
  });

  it('référence le logo sous son nouveau nom de fichier', () => {
    setup(false);
    const logos = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('img'),
    ).map((img) => img.getAttribute('src'));
    expect(logos.length).toBeGreaterThan(0);
    logos.forEach((src) => expect(src).toBe('claude-portal-logo.png'));
  });

  it('affiche le nom de marque « Claude Portal »', () => {
    setup(false);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Claude Portal');
  });
});
