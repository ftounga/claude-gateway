import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { RadarService } from '../../core/services/radar.service';
import { RadarDepositDialogComponent } from './radar-deposit-dialog.component';

/** Déposer un enregistrement — le dialogue (F-104 / SF-104-04). */
describe('RadarDepositDialogComponent', () => {
  let radar: jasmine.SpyObj<RadarService>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<RadarDepositDialogComponent>>;

  function build(): RadarDepositDialogComponent {
    radar = jasmine.createSpyObj<RadarService>('RadarService',
      ['openDeposit', 'sendDepositChunk', 'finishDeposit', 'abortDeposit']);
    radar.openDeposit.and.returnValue(of({ uploadId: 'u1', chunkBytes: 4, maxBytes: 500 * 1024 * 1024 }));
    radar.sendDepositChunk.and.callFake((_h, _u, offset, chunk) => of({ received: offset + chunk.size }));
    radar.finishDeposit.and.returnValue(of({ fileName: 'salle.m4a', title: 'Atelier sécurité',
      recordedAt: '2026-09-12T10:00:00+02:00', sizeBytes: 10 }));
    radar.abortDeposit.and.returnValue(of(undefined));
    dialogRef = jasmine.createSpyObj<MatDialogRef<RadarDepositDialogComponent>>('MatDialogRef', ['close']);
    TestBed.configureTestingModule({
      imports: [RadarDepositDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: RadarService, useValue: radar },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: { hostId: 'h1' } },
      ],
    });
    const fixture = TestBed.createComponent(RadarDepositDialogComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  const file = (name: string, size: number) =>
    new File([new Uint8Array(size)], name, { lastModified: new Date(2026, 8, 12, 10, 0).getTime() });

  it('un fichier choisi propose son titre et sa date ; un PDF est refusé', () => {
    const dialog = build();
    dialog.choose(file('atelier_securite.m4a', 10));
    expect(dialog.title()).toBe('atelier securite');
    expect(dialog.recordedAt()).toBe('2026-09-12T10:00');
    expect(dialog.canSend()).toBeTrue();

    dialog.choose(file('rapport.pdf', 10));
    expect(dialog.fileProblem()).toContain('audio ou vidéo');
    expect(dialog.canSend()).toBeFalse();
  });

  it('dépose : ouvre, envoie trois morceaux, termine ; la fin est dite et rendue à la fermeture', async () => {
    const dialog = build();
    dialog.choose(file('salle.m4a', 10));
    dialog.title.set('Atelier sécurité');
    await dialog.send();

    expect(radar.openDeposit).toHaveBeenCalledWith('h1', 'salle.m4a', 10, 'Atelier sécurité',
      jasmine.stringMatching(/^2026-09-12T10:00:00[+-]\d{2}:\d{2}$/));
    expect(radar.sendDepositChunk.calls.allArgs().map((args) => args[2])).toEqual([0, 4, 8]);
    expect(radar.finishDeposit).toHaveBeenCalledWith('h1', 'u1');
    expect(dialog.phase()).toBe('done');
    expect(dialog.percent()).toBe(100);
    dialog.close();
    expect(dialogRef.close).toHaveBeenCalledWith(jasmine.objectContaining({ title: 'Atelier sécurité' }));
  });

  it('refus du poste : message, abandon envoyé, retour au formulaire', async () => {
    const dialog = build();
    radar.sendDepositChunk.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409,
      error: { message: 'Place insuffisante sur le poste.' } })));
    dialog.choose(file('salle.m4a', 10));
    await dialog.send();

    expect(dialog.error()).toBe('Place insuffisante sur le poste.');
    expect(radar.abortDeposit).toHaveBeenCalledWith('h1', 'u1');
    expect(dialog.phase()).toBe('form');
    expect(radar.finishDeposit).not.toHaveBeenCalled();
  });

  it('annuler pendant l\'envoi : abandon envoyé, rien n\'est terminé', async () => {
    const dialog = build();
    radar.sendDepositChunk.and.callFake((_h, _u, offset, chunk) => {
      dialog.cancel();
      return of({ received: offset + chunk.size });
    });
    dialog.choose(file('salle.m4a', 10));
    await dialog.send();

    expect(radar.abortDeposit).toHaveBeenCalledWith('h1', 'u1');
    expect(radar.finishDeposit).not.toHaveBeenCalled();
    expect(dialog.phase()).toBe('form');
  });
});
