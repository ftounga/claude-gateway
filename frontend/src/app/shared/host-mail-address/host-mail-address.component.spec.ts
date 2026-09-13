import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { HostMailAddress } from '../../core/models/mail.models';
import { MailService } from '../../core/services/mail.service';
import { HostMailAddressComponent } from './host-mail-address.component';
import { HostMailAddressDialogComponent, HostMailAddressDialogData } from './host-mail-address-dialog.component';

/** L'adresse de réception : la ligne d'en-tête et son dialogue (F-110 / SF-110-01). */
describe('HostMailAddressComponent et HostMailAddressDialogComponent', () => {
  let mail: jasmine.SpyObj<MailService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const none: HostMailAddress = {
    address: null, verified: false, verifiedAt: null, codePending: false, codeExpiresAt: null,
    accountEmail: 'ntounga@gmail.com', recipient: 'ntounga@gmail.com', fallback: true, clientName: 'CAGIP',
  };
  const pending: HostMailAddress = { ...none, address: 'franck@cagip.fr', codePending: true };
  const verified: HostMailAddress = { ...none, address: 'franck@cagip.fr', verified: true,
    verifiedAt: '2026-09-13T10:00:00Z', recipient: 'franck@cagip.fr', fallback: false };

  beforeEach(() => {
    mail = jasmine.createSpyObj<MailService>('MailService', ['address', 'declare', 'verify', 'resend', 'remove']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
  });

  describe('la ligne', () => {
    let fixture: ComponentFixture<HostMailAddressComponent>;

    function build(view = of(none)): HTMLElement {
      mail.address.and.returnValue(view);
      TestBed.configureTestingModule({
        imports: [HostMailAddressComponent],
        providers: [
          provideNoopAnimations(),
          { provide: MailService, useValue: mail },
          { provide: MatDialog, useValue: dialog },
          { provide: MatSnackBar, useValue: snackBar },
        ],
      });
      fixture = TestBed.createComponent(HostMailAddressComponent);
      fixture.componentRef.setInput('hostId', 'h1');
      fixture.componentRef.setInput('hostName', 'CAGIP');
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    it("lit l'état du client, dit le repli, et Régler ouvre le dialogue ; une vérification est annoncée", () => {
      const root = build();
      expect(mail.address).toHaveBeenCalledOnceWith('h1');
      expect(root.textContent).toContain("envoi à l'adresse du compte ntounga@gmail.com");

      dialog.open.and.returnValue({ afterClosed: () => of(verified) } as MatDialogRef<unknown>);
      (root.querySelector('.host-mail__edit') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(dialog.open).toHaveBeenCalled();
      expect(root.textContent).toContain('Courriels : franck@cagip.fr');
      expect(snackBar.open.calls.mostRecent().args[0]).toContain('franck@cagip.fr');
    });

    it("se tait quand l'état est illisible", () => {
      const root = build(throwError(() => new HttpErrorResponse({ status: 403 })));
      expect(root.querySelector('.host-mail')).toBeNull();
    });
  });

  describe('le dialogue', () => {
    let fixture: ComponentFixture<HostMailAddressDialogComponent>;
    let ref: jasmine.SpyObj<MatDialogRef<HostMailAddressDialogComponent>>;

    function open(view: HostMailAddress): HTMLElement {
      ref = jasmine.createSpyObj('MatDialogRef', ['close']);
      const data: HostMailAddressDialogData = { hostId: 'h1', hostName: 'CAGIP', view };
      TestBed.configureTestingModule({
        imports: [HostMailAddressDialogComponent],
        providers: [
          provideNoopAnimations(),
          { provide: MailService, useValue: mail },
          { provide: MatDialogRef, useValue: ref },
          { provide: MAT_DIALOG_DATA, useValue: data },
        ],
      });
      fixture = TestBed.createComponent(HostMailAddressDialogComponent);
      fixture.detectChanges();
      return fixture.nativeElement as HTMLElement;
    }

    function type(root: HTMLElement, selector: string, value: string): void {
      const input = root.querySelector(selector) as HTMLInputElement;
      input.value = value;
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
    }

    it("enchaîne adresse → code → vérifiée, et se referme sur l'adresse vérifiée", () => {
      const root = open(none);
      const send = () => root.querySelector('.mail__declare') as HTMLButtonElement;
      expect(send().disabled).toBeTrue();

      mail.declare.and.returnValue(of(pending));
      type(root, '.mail__address-input', 'franck@cagip.fr');
      send().click();
      fixture.detectChanges();
      expect(mail.declare).toHaveBeenCalledOnceWith('h1', 'franck@cagip.fr');
      expect(root.textContent).toContain('Code envoyé à franck@cagip.fr');
      expect(root.textContent).toContain('courriers indésirables');

      mail.verify.and.returnValue(of(verified));
      type(root, '.mail__code-input', '123456');
      (root.querySelector('.mail__verify') as HTMLButtonElement).click();
      expect(mail.verify).toHaveBeenCalledOnceWith('h1', '123456');
      expect(ref.close).toHaveBeenCalledOnceWith(verified);
    });

    it('dit le refus de la gateway et reste ouvert', () => {
      const root = open(pending);
      mail.verify.and.returnValue(throwError(() => new HttpErrorResponse({ status: 400,
        error: { error: 'mail_code_invalid', message: 'Code incorrect : encore 4 essai(s).' } })));
      type(root, '.mail__code-input', '000000');
      (root.querySelector('.mail__verify') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(root.querySelector('.mail__error')?.textContent).toContain('encore 4 essai(s)');
      expect(ref.close).not.toHaveBeenCalled();
    });

    it("renvoie le code, retire l'adresse vérifiée", () => {
      let root = open(pending);
      mail.resend.and.returnValue(of(pending));
      (root.querySelector('.mail__resend') as HTMLButtonElement).click();
      expect(mail.resend).toHaveBeenCalledOnceWith('h1');

      TestBed.resetTestingModule();
      root = open(verified);
      expect(root.textContent).toContain('Adresse vérifiée : franck@cagip.fr');
      mail.remove.and.returnValue(of(none));
      (root.querySelector('.mail__remove') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(mail.remove).toHaveBeenCalledOnceWith('h1');
      expect(root.querySelector('.mail__address-input')).not.toBeNull();

      (root.querySelector('.mail__close') as HTMLButtonElement).click();
      expect(ref.close).toHaveBeenCalledOnceWith(none);
    });
  });
});
