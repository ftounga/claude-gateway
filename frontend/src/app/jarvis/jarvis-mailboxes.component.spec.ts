import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { JarvisMailClient, MailAccount, mailErrorMessage, mailStatusLabel } from './jarvis-mail';
import { JarvisMailboxesComponent } from './jarvis-mailboxes.component';

function account(over: Partial<MailAccount> = {}): MailAccount {
  return {
    id: 'a1', address: 'ntounga@gmail.com', label: 'Perso', provider: 'GOOGLE', status: 'OK', secretLast4: 'mnop',
    lastOkAt: '2026-11-03T08:00:00Z', lastError: null, createdAt: '2026-11-03T08:00:00Z', ...over,
  };
}

describe('jarvis-mail (fonctions)', () => {
  it('libellés d’état et messages d’erreur', () => {
    expect(mailStatusLabel('OK')).toBe('Reliée');
    expect(mailStatusLabel('AUTH_FAILED')).toBe('Mot de passe refusé');
    expect(mailErrorMessage({ error: { message: 'Google refuse ce mot de passe' } })).toContain('Google refuse');
    expect(mailErrorMessage({ status: 503 })).toContain('ne répond pas');
    expect(mailErrorMessage(null)).toContain('pas pu');
  });
});

describe('JarvisMailboxesComponent (F-242 / SF-242-01)', () => {
  let client: jasmine.SpyObj<JarvisMailClient>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snack: jasmine.SpyObj<MatSnackBar>;
  let fixture: ComponentFixture<JarvisMailboxesComponent>;

  function create(list: MailAccount[] = []): HTMLElement {
    client.list.and.returnValue(of(list));
    fixture = TestBed.createComponent(JarvisMailboxesComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    client = jasmine.createSpyObj<JarvisMailClient>('JarvisMailClient', ['list', 'link', 'test', 'revoke']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    snack = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    snack.open.and.returnValue({ onAction: () => of() } as never);
    TestBed.configureTestingModule({
      imports: [JarvisMailboxesComponent],
      providers: [
        provideNoopAnimations(),
        { provide: JarvisMailClient, useValue: client },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snack },
      ],
    });
  });

  it('liste les boîtes avec état et …last4, jamais le secret ; le pas-à-pas pointe vers Google', () => {
    const el = create([account(), account({ id: 'a2', address: 'pro@ng-itconsulting.com', label: 'Pro',
      status: 'AUTH_FAILED', lastError: 'Mot de passe d’application refusé par Google.' })]);
    const items = el.querySelectorAll('.jm__account');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('…mnop');
    expect(items[0].textContent).toContain('Reliée');
    expect(items[1].querySelector('.jm__status--ko')?.textContent).toContain('Mot de passe refusé');
    expect(items[1].textContent).toContain('refusé par Google');
    // Deux boîtes : plus de formulaire.
    expect(el.querySelector('.jm__form')).toBeNull();
    expect(el.textContent).toContain('2 boîtes au plus');
  });

  it('le formulaire masque le mot de passe et le pas-à-pas mène à myaccount.google.com/apppasswords', () => {
    const el = create([]);
    const password = el.querySelector('.jm__password') as HTMLInputElement;
    expect(password.type).toBe('password');
    expect((el.querySelector('.jm__link') as HTMLAnchorElement).href).toContain('myaccount.google.com/apppasswords');
    expect(el.querySelectorAll('.jm__steps li').length).toBe(3);
  });

  it('relier : envoie adresse, libellé et mot de passe, vide le champ, recharge', () => {
    const el = create([]);
    client.link.and.returnValue(of(account()));
    const password = el.querySelector('.jm__password') as HTMLInputElement;
    password.value = 'abcd efgh ijkl mnop';
    client.list.and.returnValue(of([account()]));
    fixture.componentInstance.link('ntounga@gmail.com', 'Perso', password);
    fixture.detectChanges();
    expect(client.link).toHaveBeenCalledWith('ntounga@gmail.com', 'Perso', 'abcd efgh ijkl mnop');
    expect(password.value).toBe('');
    expect(el.querySelectorAll('.jm__account').length).toBe(1);
  });

  it('un refus de Google s’affiche clairement, et le champ est vidé', () => {
    const el = create([]);
    client.link.and.returnValue(throwError(() => ({ status: 400,
      error: { error: 'mail_auth_failed', message: 'Google refuse ce mot de passe d’application.' } })));
    const password = el.querySelector('.jm__password') as HTMLInputElement;
    password.value = 'faux';
    fixture.componentInstance.link('ntounga@gmail.com', '', password);
    fixture.detectChanges();
    expect(el.querySelector('.jm__error')?.textContent).toContain('Google refuse');
    expect(password.value).toBe('');
  });

  it('champs vides : rien n’est envoyé', () => {
    create([]);
    const password = document.createElement('input');
    fixture.componentInstance.link('  ', '', password);
    expect(client.link).not.toHaveBeenCalled();
  });

  it('révoquer passe par une confirmation', () => {
    create([account()]);
    client.revoke.and.returnValue(of(undefined));
    dialog.open.and.returnValue({ afterClosed: () => of(false) } as never);
    fixture.componentInstance.revoke(account());
    expect(client.revoke).not.toHaveBeenCalled();
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as never);
    fixture.componentInstance.revoke(account());
    expect(client.revoke).toHaveBeenCalledWith('a1');
    expect(fixture.componentInstance.accounts().length).toBe(0);
  });

  it('tester remplace la ligne par l’état rendu', () => {
    create([account()]);
    client.test.and.returnValue(of(account({ status: 'AUTH_FAILED', lastError: 'refusé' })));
    fixture.componentInstance.test(account());
    expect(fixture.componentInstance.accounts()[0].status).toBe('AUTH_FAILED');
  });
});
