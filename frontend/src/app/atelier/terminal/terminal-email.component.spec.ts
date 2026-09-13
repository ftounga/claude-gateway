import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { AtelierTerminalEmail } from '../../core/models/atelier.models';
import { ClientEmailView } from '../../core/models/mail.models';
import { MailService } from '../../core/services/mail.service';
import { EMAIL_POLL_MAX, EMAIL_POLL_MS, TerminalEmailComponent } from './terminal-email.component';

/** Le bloc « Courriel envoyé » suit l'état de remise (F-110 / SF-110-02). */
describe('TerminalEmailComponent', () => {
  let mail: jasmine.SpyObj<MailService>;
  let fixture: ComponentFixture<TerminalEmailComponent>;

  const receipt: AtelierTerminalEmail = {
    emailId: 'e1', recipient: 'franck@cagip.fr', recipientVerified: true, clientName: 'CAGIP',
    subject: 'Compte rendu', attachmentCount: 0, status: 'PENDING',
  };
  const view = (status: string, failureReason: string | null = null): ClientEmailView => ({
    id: 'e1', recipient: 'franck@cagip.fr', recipientVerified: true, clientName: 'CAGIP', subject: 'Compte rendu',
    attachmentCount: 0, sizeBytes: 120, status, failureReason, createdAt: '2026-09-13T10:00:00Z', sentAt: null,
  });

  function build(email: AtelierTerminalEmail = receipt): HTMLElement {
    mail = mail ?? jasmine.createSpyObj<MailService>('MailService', ['email']);
    TestBed.configureTestingModule({
      imports: [TerminalEmailComponent],
      providers: [{ provide: MailService, useValue: mail }],
    });
    fixture = TestBed.createComponent(TerminalEmailComponent);
    fixture.componentRef.setInput('receipt', email);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    mail = jasmine.createSpyObj<MailService>('MailService', ['email']);
  });

  it("relit l'état tant que l'envoi n'est pas terminé, puis s'arrête", fakeAsync(() => {
    mail.email.and.returnValues(of(view('PENDING')), of(view('SENT')));
    const root = build();
    expect(root.textContent).toContain('Courriel envoyé à franck@cagip.fr — Compte rendu');
    expect(root.textContent).toContain('courriers indésirables');

    tick(0);
    fixture.detectChanges();
    expect(root.textContent).toContain("en cours d'envoi");

    tick(EMAIL_POLL_MS);
    fixture.detectChanges();
    expect(root.textContent).toContain('accepté par le relais');
    expect(root.querySelector('.terminal-email')?.getAttribute('data-status')).toBe('SENT');

    tick(EMAIL_POLL_MS * 3);
    expect(mail.email).toHaveBeenCalledTimes(2);
  }));

  it('dit le refus et son motif, et le repli sur le compte', fakeAsync(() => {
    mail.email.and.returnValue(of(view('FAILED', 'adresse refusée par le relais')));
    const root = build({ ...receipt, recipientVerified: false });

    tick(0);
    fixture.detectChanges();
    expect(root.textContent).toContain('non remis : adresse refusée par le relais');
    expect(root.textContent).toContain("Aucune adresse vérifiée pour CAGIP : envoyé à l'adresse du compte.");
  }));

  it('cesse de relire quand la lecture échoue, et au plus EMAIL_POLL_MAX fois', fakeAsync(() => {
    mail.email.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    build();
    tick(EMAIL_POLL_MS * 5);
    expect(mail.email).toHaveBeenCalledTimes(1);

    TestBed.resetTestingModule();
    mail = jasmine.createSpyObj<MailService>('MailService', ['email']);
    mail.email.and.returnValue(of(view('PENDING')));
    build();
    tick(EMAIL_POLL_MS * (EMAIL_POLL_MAX + 5));
    expect(mail.email).toHaveBeenCalledTimes(EMAIL_POLL_MAX);
    discardPeriodicTasks();
  }));
});
