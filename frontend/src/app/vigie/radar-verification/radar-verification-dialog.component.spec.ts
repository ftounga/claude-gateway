import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { Subject, of, throwError } from 'rxjs';

import { RadarVerification, RadarVerificationCheck } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { RUNNER_HOST_PLATFORM } from '../../atelier/runner/runner-pairing-dialog.component';
import { RadarVerificationDialogComponent } from './radar-verification-dialog.component';
import { VERIFICATION_POLL_MS } from './radar-verification';

/** La vérification guidée, le dialogue (F-100 / SF-100-06). */
describe('RadarVerificationDialogComponent', () => {
  let fixture: ComponentFixture<RadarVerificationDialogComponent>;
  let component: RadarVerificationDialogComponent;
  let radar: jasmine.SpyObj<RadarService>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<RadarVerificationDialogComponent>>;

  const check = (ok: boolean, state = '', sentence = ''): RadarVerificationCheck =>
    ({ ok, state, count: ok ? 1 : 0, sentence, okSince: null });

  const partial: RadarVerification = {
    complete: false, verifiedAt: '2026-09-13T10:00:00Z', session: check(true, 'LINKED', 'Session active.'),
    conversations: check(true, '', '1 conversation(s) vue(s).'), meetings: check(false), transcripts: check(false, 'NOT_SEEN'),
  };
  const complete: RadarVerification = {
    ...partial, complete: true, meetings: check(true), transcripts: check(true, 'SEEN'),
  };

  function build(): HTMLElement {
    radar = jasmine.createSpyObj<RadarService>('RadarService', ['verify', 'resetVerification']);
    dialogRef = jasmine.createSpyObj<MatDialogRef<RadarVerificationDialogComponent>>('MatDialogRef', ['close']);
    TestBed.configureTestingModule({
      imports: [RadarVerificationDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: { hostId: 'h1', hostName: 'EDENRED' } },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: RadarService, useValue: radar },
        { provide: RUNNER_HOST_PLATFORM, useValue: 'windows' },
      ],
    });
    fixture = TestBed.createComponent(RadarVerificationDialogComponent);
    component = fixture.componentInstance;
    return fixture.nativeElement as HTMLElement;
  }

  it('vérifie à l’ouverture, puis 5 s après chaque réponse, et s’arrête quand tout est vu', fakeAsync(() => {
    const root = build();
    radar.verify.and.returnValues(of(partial), of(complete));

    fixture.detectChanges();
    expect(radar.verify).toHaveBeenCalledOnceWith('h1');
    expect(root.querySelectorAll('.verification__line[data-state="seen"]').length).toBe(2);

    tick(VERIFICATION_POLL_MS - 1);
    expect(radar.verify).toHaveBeenCalledTimes(1);
    tick(1);
    fixture.detectChanges();
    expect(radar.verify).toHaveBeenCalledTimes(2);
    expect(root.querySelector('.verification__complete')?.textContent).toContain('voit tout');

    tick(VERIFICATION_POLL_MS * 3);
    expect(radar.verify).toHaveBeenCalledTimes(2);
  }));

  it('jamais deux appels à la fois : le suivant attend la fin du précédent', fakeAsync(() => {
    build();
    const pending = new Subject<RadarVerification>();
    radar.verify.and.returnValue(pending.asObservable());

    fixture.detectChanges();
    tick(VERIFICATION_POLL_MS * 4);
    expect(radar.verify).toHaveBeenCalledTimes(1);

    pending.next(partial);
    tick(VERIFICATION_POLL_MS);
    expect(radar.verify).toHaveBeenCalledTimes(2);
    component.close();
  }));

  it('fermer arrête tout : aucun appel ensuite', fakeAsync(() => {
    build();
    radar.verify.and.returnValue(of(partial));
    fixture.detectChanges();

    component.close();
    tick(VERIFICATION_POLL_MS * 3);

    expect(radar.verify).toHaveBeenCalledTimes(1);
    expect(dialogRef.close).toHaveBeenCalledWith(false);
  }));

  it('un refus (runner trop ancien) arrête les appels, donne le remède et Réessayer', fakeAsync(() => {
    const root = build();
    radar.verify.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: { error: 'radar_teams_disabled', message: 'Le volet Teams n’est pas actif sur ce poste.' } })));

    fixture.detectChanges();
    tick(VERIFICATION_POLL_MS * 3);
    fixture.detectChanges();

    expect(radar.verify).toHaveBeenCalledTimes(1);
    expect(root.querySelector('.verification__refusal')?.textContent).toContain('Mettez le runner à jour');
    expect(root.querySelectorAll('.verification__refusal app-copy-block').length).toBe(2);

    radar.verify.and.returnValue(of(complete));
    (root.querySelector('.verification__retry') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(radar.verify).toHaveBeenCalledTimes(2);
    expect(root.querySelector('.verification__refusal')).toBeNull();
  }));

  it('la liaison Chrome manquante montre la commande du runner ; Recommencer décoche puis reprend', fakeAsync(() => {
    const root = build();
    radar.verify.and.returnValue(of({ ...partial, conversations: check(false),
      session: check(false, 'BROWSER_NOT_DETECTED', 'chrome.exe --remote-debugging-port=9222') }));
    fixture.detectChanges();

    const session = root.querySelector('.verification__line[data-key="session"]');
    expect(session?.getAttribute('data-state')).toBe('remedy');
    expect(session?.querySelector('app-copy-block')?.textContent).toContain('--remote-debugging-port=9222');

    radar.resetVerification.and.returnValue(of({ ...partial, session: check(false), conversations: check(false) }));
    radar.verify.calls.reset();
    radar.verify.and.returnValue(of(complete));
    (root.querySelector('.verification__restart') as HTMLButtonElement).click();

    expect(radar.resetVerification).toHaveBeenCalledOnceWith('h1');
    expect(radar.verify).toHaveBeenCalledTimes(1);
    tick(VERIFICATION_POLL_MS);
  }));
});
