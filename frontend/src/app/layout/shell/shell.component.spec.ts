import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { NavigationEnd, provideRouter, Router } from '@angular/router';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, Subject } from 'rxjs';

import { signal } from '@angular/core';
import { By } from '@angular/platform-browser';
import { HelpChatWidgetComponent } from '../../help/help-chat-widget/help-chat-widget.component';

import { NAV_HIDE_DELAY_MS, NAV_PINNED_KEY, ShellComponent, isWorkspaceScreen } from './shell.component';
import { AuthService } from '../../core/services/auth.service';
import { CurrentClientService } from '../../core/services/current-client.service';
import { QuotaAlertService } from '../../core/services/quota-alert.service';
import { QuotaAlertView } from '../../core/models/quota-alert.models';

describe('ShellComponent', () => {
  let fixture: ComponentFixture<ShellComponent>;
  let authSpy: jasmine.SpyObj<AuthService>;
  let quotaAlertSpy: jasmine.SpyObj<QuotaAlertService>;
  let router: Router;

  /** Aucune alerte levée : la coquille doit se comporter exactement comme avant F-42. */
  const NO_ALERT: QuotaAlertView = {
    raised: false,
    usedTokens: 0,
    quotaTokens: 1000000,
    remainingTokens: 1000000,
    usedPercent: 0,
    thresholdPercent: 80,
    periodEnd: '2026-08-01',
    topUp: null,
  };

  beforeEach(async () => {
    localStorage.removeItem(NAV_PINNED_KEY);
    authSpy = jasmine.createSpyObj<AuthService>('AuthService', ['logout'], {
      isAdmin: signal(false),
    });
    authSpy.logout.and.returnValue(
      of({ message: 'ok' }) as unknown as ReturnType<AuthService['logout']>,
    );

    quotaAlertSpy = jasmine.createSpyObj<QuotaAlertService>('QuotaAlertService', [
      'getAlert',
      'dismissAlert',
    ]);
    quotaAlertSpy.getAlert.and.returnValue(of(NO_ALERT));
    quotaAlertSpy.dismissAlert.and.returnValue(of(undefined as unknown as void));

    await TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        // La bannière d'alerte de quota (F-42) que porte la coquille tire BillingService, donc
        // HttpClient : aucun appel n'est émis ici (le service d'alerte est bouchonné, et aucune
        // alerte n'est levée), mais l'injecteur doit pouvoir le construire.
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: authSpy },
        { provide: QuotaAlertService, useValue: quotaAlertSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ShellComponent);
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture.detectChanges();
  });

  /**
   * Simule l'arrivée sur une URL. La coquille n'écoute rien d'autre que les navigations terminées
   * du routeur : les rejouer ici évite d'avoir à déclarer toute la table de routes dans un test
   * qui ne parle que de la barre de navigation.
   */
  function arriveAt(url: string): void {
    (router.events as unknown as Subject<NavigationEnd>).next(new NavigationEnd(1, url, url));
    fixture.detectChanges();
  }

  it('affiche les liens de navigation vers les sections principales', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Chat');
    expect(text).toContain('Bibliothèque');
    expect(text).toContain('Templates');
  });

  it('se déconnecte et redirige vers /login', () => {
    fixture.componentInstance.logout();

    expect(authSpy.logout).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/login']);
  });

  // ---- F-42 SF-42-02 : la bannière d'alerte de quota vit dans la coquille ----
  it('porte la bannière d\'alerte de quota, sans rien rendre quand aucune alerte n\'est levée', () => {
    const shell = fixture.nativeElement as HTMLElement;

    expect(shell.querySelector('app-quota-alert-banner')).not.toBeNull();
    expect(quotaAlertSpy.getAlert).toHaveBeenCalled();
    // Aucune alerte : aucune bande, donc aucun décalage de mise en page sur les écrans enveloppés.
    expect(shell.querySelector('.quota-alert')).toBeNull();
  });

  it('conserve la navigation intacte au-dessus du contenu (non-régression F-42)', () => {
    const shell = fixture.nativeElement as HTMLElement;

    // La bannière s'intercale entre la barre de navigation et le contenu, sans les remplacer.
    expect(shell.querySelector('mat-toolbar.app-bar')).not.toBeNull();
    expect(shell.querySelector('main.app-content router-outlet')).not.toBeNull();
  });

  // ---- F-54 SF-54-02 : la bulle d'aide vit dans la coquille, donc côté authentifié ----
  it("porte la bulle d'aide produit, panneau fermé au départ", () => {
    const shell = fixture.nativeElement as HTMLElement;

    expect(shell.querySelector('app-help-chat-widget')).not.toBeNull();
    expect(shell.querySelector('.help-widget__bubble')).not.toBeNull();
    // Le panneau ne s'ouvre qu'au clic : la bulle n'encombre pas l'écran par défaut.
    expect(shell.querySelector('.help-panel')).toBeNull();
  });

  // ---- F-68 SF-68-01 : un seul onglet pour la Forge, et il mène aux missions ----

  it('ne propose plus d\'onglet « Postes » — la Forge s\'ouvre dessus', () => {
    const shell = fixture.nativeElement as HTMLElement;
    const nav = shell.querySelector('.app-nav') as HTMLElement;

    expect(nav.textContent).not.toContain('Postes');
    expect(nav.querySelector('a[href="/postes"]')).toBeNull();
  });

  it('fait mener l\'entrée « Forge » à l\'accueil de la Forge', () => {
    const forge = (fixture.nativeElement as HTMLElement)
      .querySelector('.app-nav a[href="/forge"]') as HTMLAnchorElement;

    expect(forge).not.toBeNull();
    expect(forge.textContent).toContain('Forge');
  });

  it('garde l\'entrée « Forge » allumée pendant qu\'on travaille dans un projet', () => {
    const forge = () => (fixture.nativeElement as HTMLElement)
      .querySelector('.app-nav a[href="/forge"]') as HTMLAnchorElement;

    // Hors de la Forge : éteinte.
    expect(forge().classList).not.toContain('active');

    // `/atelier/:id` est la route du terminal, conservée telle quelle par F-58 : y travailler,
    // c'est toujours être dans la Forge — l'onglet ne doit pas s'éteindre en route.
    arriveAt('/atelier/w1');
    expect(forge().classList).toContain('active');

    arriveAt('/forge#poste-h1');
    expect(forge().classList).toContain('active');

    arriveAt('/chat');
    expect(forge().classList).not.toContain('active');
  });

  // ---- F-106 / SF-106-02 : la Vigie, à côté de la Forge ----

  it('porte Forge et Vigie côte à côte, chacune allumée dans son espace', () => {
    const link = (href: string) => (fixture.nativeElement as HTMLElement)
      .querySelector(href === '/vigie' ? '.app-nav__vigie' : '.app-nav__forge') as HTMLAnchorElement;

    expect(link('/vigie')).not.toBeNull();
    expect(link('/vigie').textContent).toContain('Vigie');
    expect(link('/forge').nextElementSibling).toBe(link('/vigie'));

    arriveAt('/vigie/h1');
    expect(link('/vigie').classList).toContain('active');
    expect(link('/forge').classList).not.toContain('active');

    arriveAt('/forge/h1');
    expect(link('/vigie').classList).not.toContain('active');
    expect(link('/forge').classList).toContain('active');
  });

  // ---- F-106 / SF-106-04 : les passerelles gardent le client ouvert ----

  it('change d’espace sans perdre le client ouvert', () => {
    const href = (selector: string) => ((fixture.nativeElement as HTMLElement)
      .querySelector(selector) as HTMLAnchorElement).getAttribute('href');

    arriveAt('/forge/h1?onglet=carte');
    expect(href('.app-nav__vigie')).toBe('/vigie/h1');
    expect(href('.app-nav__forge')).toBe('/forge/h1');

    arriveAt('/vigie/h2/sujets/s1');
    expect(href('.app-nav__forge')).toBe('/forge/h2');
    expect((fixture.nativeElement as HTMLElement).querySelector('.app-nav__vigie')?.classList)
      .toContain('active');

    arriveAt('/forge/voir');
    expect(href('.app-nav__vigie')).toBe('/vigie');

    arriveAt('/chat');
    expect(href('.app-nav__forge')).toBe('/forge');
    expect(href('.app-nav__vigie')).toBe('/vigie');
  });

  // ---- F-199 / SF-199-01 : sur un terminal, la Forge suit le client courant ----

  it('sur un terminal, le lien Forge mène au client du terminal ouvert, pas au poste par défaut', () => {
    const href = (selector: string) => ((fixture.nativeElement as HTMLElement)
      .querySelector(selector) as HTMLAnchorElement).getAttribute('href');
    const current = TestBed.inject(CurrentClientService);

    arriveAt('/atelier/w1');
    expect(href('.app-nav__forge')).toBe('/forge');

    current.set('h-edenred');
    arriveAt('/atelier/w2');
    expect(href('.app-nav__forge')).toBe('/forge/h-edenred');
    expect(href('.app-nav__vigie')).toBe('/vigie/h-edenred');

    // L'adresse prime : dans la Forge sur un autre client, c'est lui qui est porté.
    arriveAt('/forge/h1');
    expect(href('.app-nav__vigie')).toBe('/vigie/h1');
    current.clear();
  });

  // ---- F-151 / SF-151-01 : la navigation se replie derrière un hamburger sous 819 px ----

  it('démarre menu replié fermé : bouton présent, aria-expanded=false, aucun voile', () => {
    const shell = fixture.nativeElement as HTMLElement;
    const toggle = shell.querySelector('.app-nav-toggle') as HTMLButtonElement;

    expect(toggle).not.toBeNull();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(toggle.getAttribute('aria-controls')).toBe('app-nav');
    expect(shell.querySelector('.app-nav-backdrop')).toBeNull();
    expect(shell.querySelector('.app-nav')?.classList).not.toContain('app-nav--open');
  });

  it('ouvre et referme le panneau au clic du hamburger (aria-expanded + voile suivent)', () => {
    const shell = fixture.nativeElement as HTMLElement;
    const toggle = () => shell.querySelector('.app-nav-toggle') as HTMLButtonElement;

    toggle().click();
    fixture.detectChanges();
    expect(toggle().getAttribute('aria-expanded')).toBe('true');
    expect(shell.querySelector('.app-nav')?.classList).toContain('app-nav--open');
    expect(shell.querySelector('.app-nav-backdrop')).not.toBeNull();

    toggle().click();
    fixture.detectChanges();
    expect(toggle().getAttribute('aria-expanded')).toBe('false');
    expect(shell.querySelector('.app-nav-backdrop')).toBeNull();
  });

  it('referme le panneau à la navigation terminée', () => {
    const shell = fixture.nativeElement as HTMLElement;
    (shell.querySelector('.app-nav-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(shell.querySelector('.app-nav')?.classList).toContain('app-nav--open');

    arriveAt('/documents');
    expect(shell.querySelector('.app-nav')?.classList).not.toContain('app-nav--open');
  });

  it('referme le panneau à la touche Échap', () => {
    const shell = fixture.nativeElement as HTMLElement;
    (shell.querySelector('.app-nav-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(shell.querySelector('.app-nav')?.classList).toContain('app-nav--open');

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(shell.querySelector('.app-nav')?.classList).not.toContain('app-nav--open');
  });

  it('referme le panneau au clic sur le voile', () => {
    const shell = fixture.nativeElement as HTMLElement;
    (shell.querySelector('.app-nav-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();

    (shell.querySelector('.app-nav-backdrop') as HTMLElement).click();
    fixture.detectChanges();
    expect(shell.querySelector('.app-nav')?.classList).not.toContain('app-nav--open');
  });

  it('conserve les 7 liens dans un seul jeu, toujours interrogeables (non-régression)', () => {
    const nav = (fixture.nativeElement as HTMLElement).querySelector('.app-nav') as HTMLElement;
    expect(nav.querySelector('a[href="/forge"]')).not.toBeNull();
    expect(nav.querySelectorAll('a').length).toBe(7);
  });

  // ---- F-151 / SF-151-04 : le menu mobile navigue enfin (correctif P0 du hamburger) ----

  it('tapoter un lien navigue (routerLink respecté) ET referme le panneau replié', () => {
    const shell = fixture.nativeElement as HTMLElement;
    // RouterLink appelle navigateByUrl : on l'observe pour prouver que la navigation part réellement
    // (avant le correctif, le voile captait le tap et la navigation n'avait jamais lieu).
    const navByUrl = spyOn(router, 'navigateByUrl').and.resolveTo(true);

    (shell.querySelector('.app-nav-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(shell.querySelector('.app-nav')?.classList).toContain('app-nav--open');

    // Un lien standard : `routerLink="/documents"` doit rendre le bon href (routerLink respecté).
    const link = shell.querySelector('.app-nav a[href="/documents"]') as HTMLAnchorElement;
    expect(link).not.toBeNull();

    link.click();
    fixture.detectChanges();

    // La navigation part (le lien est atteignable, plus volée par le voile)…
    expect(navByUrl).toHaveBeenCalled();
    // …et le panneau se referme derrière la navigation.
    expect(shell.querySelector('.app-nav')?.classList).not.toContain('app-nav--open');
  });

  it('referme le panneau au tap sur un lien même quand aucune navigation terminée ne survient', () => {
    // Cas du lien menant à la page courante : pas de NavigationEnd → la fermeture doit venir du
    // (click)="closeMenu()", pas seulement de l'effet sur NavigationEnd.
    const shell = fixture.nativeElement as HTMLElement;
    spyOn(router, 'navigateByUrl').and.resolveTo(true);

    (shell.querySelector('.app-nav-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(shell.querySelector('.app-nav')?.classList).toContain('app-nav--open');

    (shell.querySelector('.app-nav a[href="/forge"]') as HTMLAnchorElement).click();
    fixture.detectChanges(); // aucun arriveAt() : on ne simule PAS de NavigationEnd

    expect(shell.querySelector('.app-nav')?.classList).not.toContain('app-nav--open');
  });

  it('garde le voile fermant au clic extérieur après le correctif (non-régression)', () => {
    const shell = fixture.nativeElement as HTMLElement;
    (shell.querySelector('.app-nav-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();

    (shell.querySelector('.app-nav-backdrop') as HTMLElement).click();
    fixture.detectChanges();
    expect(shell.querySelector('.app-nav')?.classList).not.toContain('app-nav--open');
  });

  it('laisse les états actifs Forge/Vigie intacts après un tap qui referme le menu', () => {
    const shell = fixture.nativeElement as HTMLElement;
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    const forge = () => shell.querySelector('.app-nav a[href="/forge"]') as HTMLAnchorElement;

    arriveAt('/atelier/w1'); // on travaille dans la Forge : l'onglet reste allumé (F-68)
    expect(forge().classList).toContain('active');

    (shell.querySelector('.app-nav-toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    forge().click();
    fixture.detectChanges();

    // Le tap referme le menu mais ne casse pas le calcul d'état actif (toujours dans la Forge).
    expect(forge().classList).toContain('active');
  });

  // ---- F-29 SF-29-01 : garde-fou anti-régression sur la marque de la coquille ----
  it('affiche la marque « NG Portal » sans le terme « Proxy »', () => {
    const brand = (fixture.nativeElement as HTMLElement).querySelector('.brand');
    expect(brand?.textContent?.trim()).toBe('NG Portal');
    expect((fixture.nativeElement as HTMLElement).innerHTML).not.toMatch(/proxy/i);
  });

  // ---- F-207 / SF-207-01 : la barre se replie en lisière sur les écrans de travail ----
  describe('navigation escamotable (F-207 / SF-207-01)', () => {
    const shell = () => fixture.nativeElement as HTMLElement;
    const bar = () => shell().querySelector('mat-toolbar.app-bar') as HTMLElement;
    const lip = () => shell().querySelector('.app-lip') as HTMLElement | null;

    afterEach(() => localStorage.removeItem(NAV_PINNED_KEY));

    it('reconnaît les écrans de travail : terminal et mosaïque, rien d’autre (CA1)', () => {
      expect(isWorkspaceScreen('/atelier/abc')).toBeTrue();
      expect(isWorkspaceScreen('/atelier/abc?x=1')).toBeTrue();
      expect(isWorkspaceScreen('/atelier/abc#q3')).toBeTrue();
      expect(isWorkspaceScreen('/forge/mosaique')).toBeTrue();
      expect(isWorkspaceScreen('/atelier')).toBeFalse();
      expect(isWorkspaceScreen('/atelier/abc/fichiers')).toBeFalse();
      expect(isWorkspaceScreen('/chat')).toBeFalse();
      expect(isWorkspaceScreen('/forge')).toBeFalse();
      expect(isWorkspaceScreen('/vigie/x')).toBeFalse();
      expect(isWorkspaceScreen(null)).toBeFalse();
    });

    it('replie la barre sur un terminal, la garde fixe ailleurs (CA2, CA7)', () => {
      arriveAt('/chat');
      expect(shell().classList).not.toContain('shell--autohide');
      expect(lip()).toBeNull();
      expect(bar().classList).not.toContain('app-bar--autohide');

      arriveAt('/atelier/w1');
      expect(fixture.debugElement.nativeElement.classList).toContain('shell--autohide');
      expect(lip()).not.toBeNull();
      expect(bar().classList).toContain('app-bar--autohide');
      expect(bar().classList).not.toContain('app-bar--revealed');

      arriveAt('/gouvernance');
      expect(lip()).toBeNull();
      expect(bar().classList).not.toContain('app-bar--autohide');
    });

    it('déploie la barre au survol de la lisière et la replie après le délai (CA3, CA4)', fakeAsync(() => {
      arriveAt('/atelier/w1');
      lip()!.dispatchEvent(new MouseEvent('mouseenter'));
      fixture.detectChanges();
      expect(bar().classList).toContain('app-bar--revealed');

      // La souris passe de la lisière à la barre : pas de repli.
      lip()!.dispatchEvent(new MouseEvent('mouseleave'));
      bar().dispatchEvent(new MouseEvent('mouseenter'));
      tick(NAV_HIDE_DELAY_MS + 50);
      fixture.detectChanges();
      expect(bar().classList).toContain('app-bar--revealed');

      bar().dispatchEvent(new MouseEvent('mouseleave'));
      tick(NAV_HIDE_DELAY_MS - 50);
      fixture.detectChanges();
      expect(bar().classList).toContain('app-bar--revealed');
      tick(100);
      fixture.detectChanges();
      expect(bar().classList).not.toContain('app-bar--revealed');
    }));

    it('replie tout de suite à la touche Échap (CA4)', () => {
      arriveAt('/atelier/w1');
      lip()!.dispatchEvent(new MouseEvent('mouseenter'));
      fixture.detectChanges();
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      fixture.detectChanges();
      expect(bar().classList).not.toContain('app-bar--revealed');
    });

    it('déploie la barre au focus clavier de la poignée (CA5)', () => {
      arriveAt('/atelier/w1');
      const handle = shell().querySelector('.app-lip__handle') as HTMLButtonElement;
      expect(handle.getAttribute('aria-label')).toBe('Afficher la navigation');
      handle.dispatchEvent(new FocusEvent('focus'));
      fixture.detectChanges();
      expect(bar().classList).toContain('app-bar--revealed');
      expect(handle.getAttribute('aria-expanded')).toBe('true');
      // Les liens restent les mêmes éléments, atteignables.
      expect(bar().querySelectorAll('.app-nav a').length).toBe(7);
    });

    it('la punaise remet la barre dans le flux et s’en souvient (CA6)', () => {
      arriveAt('/atelier/w1');
      const pin = shell().querySelector('.app-pin') as HTMLButtonElement;
      expect(pin.getAttribute('aria-pressed')).toBe('false');
      pin.click();
      fixture.detectChanges();
      expect(lip()).toBeNull();
      expect(bar().classList).not.toContain('app-bar--autohide');
      expect(localStorage.getItem(NAV_PINNED_KEY)).toBe('1');

      // Rechargement : la préférence est relue.
      const again = TestBed.createComponent(ShellComponent);
      (router.events as unknown as Subject<NavigationEnd>).next(new NavigationEnd(2, '/atelier/w1', '/atelier/w1'));
      again.detectChanges();
      expect((again.nativeElement as HTMLElement).querySelector('.app-lip')).toBeNull();

      pin.click();
      fixture.detectChanges();
      expect(localStorage.getItem(NAV_PINNED_KEY)).toBeNull();
      expect(lip()).not.toBeNull();
    });

    it('sans stockage local, la punaise fonctionne sans erreur (CA6)', () => {
      spyOn(localStorage, 'getItem').and.throwError('bloqué');
      spyOn(localStorage, 'setItem').and.throwError('bloqué');
      const blind = TestBed.createComponent(ShellComponent);
      (router.events as unknown as Subject<NavigationEnd>).next(new NavigationEnd(3, '/atelier/w1', '/atelier/w1'));
      blind.detectChanges();
      const el = blind.nativeElement as HTMLElement;
      expect(el.querySelector('.app-lip')).not.toBeNull();
      expect(() => {
        (el.querySelector('.app-pin') as HTMLButtonElement).click();
        blind.detectChanges();
      }).not.toThrow();
      expect(el.querySelector('.app-lip')).toBeNull();
    });

    it('la punaise n’existe pas hors écran de travail', () => {
      arriveAt('/chat');
      expect(shell().querySelector('.app-pin')).toBeNull();
    });
  });

  describe('aide rangée dans la barre (F-207 / SF-207-05)', () => {
    const shell = () => fixture.nativeElement as HTMLElement;
    const widget = () => fixture.debugElement.query(By.directive(HelpChatWidgetComponent)).componentInstance as HelpChatWidgetComponent;

    it('sur un terminal, la barre porte le bouton d’aide et le widget est rangé (CA1, CA2)', () => {
      arriveAt('/atelier/w1');
      const help = shell().querySelector('.app-help') as HTMLButtonElement;
      expect(help).not.toBeNull();
      expect(shell().querySelector('.help-widget')!.classList).toContain('help-widget--docked');
      expect(help.getAttribute('aria-expanded')).toBe('false');

      help.click();
      fixture.detectChanges();
      expect(widget().panelOpen()).toBeTrue();
      expect(help.getAttribute('aria-expanded')).toBe('true');

      help.click();
      fixture.detectChanges();
      expect(widget().panelOpen()).toBeFalse();
    });

    it('ailleurs, ni bouton dans la barre ni widget rangé : la bulle reste (CA3)', () => {
      arriveAt('/chat');
      expect(shell().querySelector('.app-help')).toBeNull();
      expect(shell().querySelector('.help-widget')!.classList).not.toContain('help-widget--docked');
      expect(shell().querySelector('.help-widget__bubble')).not.toBeNull();
    });
  });
});
