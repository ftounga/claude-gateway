import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { JoinAndCaptureDialogComponent } from './join-and-capture-dialog.component';

/** Dialogue « Rejoindre & capturer » (F-128 / SF-128-01). */
describe('JoinAndCaptureDialogComponent', () => {
  let fixture: ComponentFixture<JoinAndCaptureDialogComponent>;
  let component: JoinAndCaptureDialogComponent;
  let dialogRef: jasmine.SpyObj<MatDialogRef<JoinAndCaptureDialogComponent>>;

  beforeEach(() => {
    dialogRef = jasmine.createSpyObj<MatDialogRef<JoinAndCaptureDialogComponent>>('MatDialogRef', ['close']);
    TestBed.configureTestingModule({
      imports: [JoinAndCaptureDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: { hostName: 'EDENRED' } },
      ],
    });
    fixture = TestBed.createComponent(JoinAndCaptureDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('se crée', () => {
    expect(component).toBeTruthy();
  });

  it('le consentement est obligatoire : sans lui, le formulaire est invalide et on ne ferme pas', () => {
    component.form.patchValue({ meetingUrl: 'https://teams.microsoft.com/x', consent: false });
    component.submit();
    expect(component.form.invalid).toBeTrue();
    expect(dialogRef.close).not.toHaveBeenCalled();
  });

  it('refuse une URL non http(s)', () => {
    component.form.patchValue({ meetingUrl: 'nope', consent: true });
    expect(component.form.controls.meetingUrl.hasError('pattern')).toBeTrue();
  });

  it('rend la demande complète quand tout est valide', () => {
    component.form.patchValue({
      meetingUrl: 'https://teams.microsoft.com/l/meetup-join/abc',
      title: 'Comité',
      retentionDays: 15,
      consent: true,
    });
    component.submit();
    expect(dialogRef.close).toHaveBeenCalledWith({
      meetingUrl: 'https://teams.microsoft.com/l/meetup-join/abc',
      title: 'Comité',
      consentAcknowledged: true,
      retentionDays: 15,
    });
  });
});
