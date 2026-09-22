import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { RadarSubjectSummary } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { RadarDepositDialogComponent } from './radar-deposit-dialog.component';

/** Déposer un enregistrement — le dialogue (F-104 / SF-104-04). */
describe('RadarDepositDialogComponent', () => {
  let radar: jasmine.SpyObj<RadarService>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<RadarDepositDialogComponent>>;

  function build(): RadarDepositDialogComponent {
    radar = jasmine.createSpyObj<RadarService>('RadarService',
      ['openDeposit', 'sendDepositChunk', 'finishDeposit', 'abortDeposit', 'recordingProgress', 'subjects']);
    radar.subjects.and.returnValue(of([{ id: 's1', name: 'Migration CAGIP', state: 'ADVANCING',
      nextStep: null, dueDate: null, lastActivityAt: null, openCommitments: 0, awake: false }] as RadarSubjectSummary[]));
    radar.openDeposit.and.returnValue(of({ uploadId: 'u1', chunkBytes: 4, maxBytes: 500 * 1024 * 1024 }));
    radar.sendDepositChunk.and.callFake((_h, _u, offset, chunk) => of({ received: offset + chunk.size }));
    radar.finishDeposit.and.returnValue(of({ fileName: 'salle.m4a', title: 'Atelier sécurité',
      recordedAt: '2026-09-12T10:00:00+02:00', sizeBytes: 10, transcription: 'started', jobId: 'u1',
      phase: 'AUDIO', phaseLabel: "j'extrais le son de l'enregistrement", meetingId: 'm1' }));
    radar.recordingProgress.and.returnValue(of({ known: true, jobId: 'u1', phase: 'TERMINE',
      phaseLabel: 'terminé', over: true, failure: '' }));
    radar.abortDeposit.and.returnValue(of(undefined));
    dialogRef = jasmine.createSpyObj<MatDialogRef<RadarDepositDialogComponent>>('MatDialogRef', ['close']);
    TestBed.configureTestingModule({
      imports: [RadarDepositDialogComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
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
    // F-147 / SF-147-02 : sans sujet, rien ne part — c'est au geste qu'on sait de quel dossier il s'agit.
    expect(dialog.canSend()).toBeFalse();
    dialog.subjectId.set('s1');
    expect(dialog.canSend()).toBeTrue();

    dialog.choose(file('rapport.pdf', 10));
    expect(dialog.fileProblem()).toContain('audio ou vidéo');
    expect(dialog.canSend()).toBeFalse();
  });

  it('dépose : ouvre, envoie trois morceaux, termine ; la fin est dite et rendue à la fermeture', async () => {
    const dialog = build();
    dialog.choose(file('salle.m4a', 10));
    dialog.title.set('Atelier sécurité');
    dialog.subjectId.set('s1');
    await dialog.send();

    expect(radar.openDeposit).toHaveBeenCalledWith('h1', 'salle.m4a', 10, 'Atelier sécurité',
      jasmine.stringMatching(/^2026-09-12T10:00:00[+-]\d{2}:\d{2}$/), 's1');
    expect(radar.sendDepositChunk.calls.allArgs().map((args) => args[2])).toEqual([0, 4, 8]);
    expect(radar.finishDeposit).toHaveBeenCalledWith('h1', 'u1');
    // F-147 / SF-147-01 : le poste a commencé à transcrire — le dialogue le suit au lieu de dire « plus tard ».
    expect(dialog.phase()).toBe('transcribing');
    expect(dialog.phaseLabel()).toContain("j'extrais le son");
    expect(dialog.percent()).toBe(100);
    dialog.close();
    expect(dialogRef.close).toHaveBeenCalledWith(jasmine.objectContaining({ title: 'Atelier sécurité' }));
  });

  it('refus du poste : message, abandon envoyé, retour au formulaire', async () => {
    const dialog = build();
    radar.sendDepositChunk.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409,
      error: { message: 'Place insuffisante sur le poste.' } })));
    dialog.choose(file('salle.m4a', 10));
    dialog.subjectId.set('s1');
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
    dialog.subjectId.set('s1');
    await dialog.send();

    expect(radar.abortDeposit).toHaveBeenCalledWith('h1', 'u1');
    expect(radar.finishDeposit).not.toHaveBeenCalled();
    expect(dialog.phase()).toBe('form');
  });

  // ---------------------------------------------------------------- F-147 / SF-147-01 : la transcription suivie

  it('suit la transcription : la phrase du poste, puis la fin', fakeAsync(() => {
    const dialog = build();
    let asked = 0;
    radar.recordingProgress.and.callFake(() => {
      asked += 1;
      return asked === 1
        ? of({ known: true, jobId: 'u1', phase: 'TRANSCRIPTION', phaseLabel: 'je transcris, ici', over: false,
          failure: '' })
        : of({ known: true, jobId: 'u1', phase: 'TERMINE', phaseLabel: 'terminé', over: true, failure: '' });
    });
    dialog.choose(file('salle.m4a', 10));
    dialog.subjectId.set('s1');
    void dialog.send();
    tick();

    tick(RadarDepositDialogComponent.POLL_MS);
    expect(radar.recordingProgress).toHaveBeenCalledWith('h1', 'u1');
    expect(dialog.phase()).toBe('transcribing');
    expect(dialog.phaseLabel()).toBe('je transcris, ici');

    tick(RadarDepositDialogComponent.POLL_MS);
    expect(dialog.phase()).toBe('done');
    expect(dialog.outcome()).toContain('Transcription terminée');
    tick();
  }));

  it('poste qui ne sait pas transcrire : dit sans faire croire que c\'est en cours', async () => {
    const dialog = build();
    radar.finishDeposit.and.returnValue(of({ fileName: 'salle.m4a', title: 'Atelier sécurité',
      recordedAt: '2026-09-12T10:00:00+02:00', sizeBytes: 10, transcription: 'unavailable', jobId: '',
      phase: '', phaseLabel: '', meetingId: null }));
    dialog.choose(file('salle.m4a', 10));
    dialog.subjectId.set('s1');
    await dialog.send();

    expect(dialog.phase()).toBe('done');
    expect(dialog.outcome()).toContain('mettez le runner à jour');
    expect(radar.recordingProgress).not.toHaveBeenCalled();
  });

  it('poste muet : on cesse de demander au bout de trois essais, et on le dit', fakeAsync(() => {
    const dialog = build();
    radar.recordingProgress.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409 })));
    dialog.choose(file('salle.m4a', 10));
    dialog.subjectId.set('s1');
    void dialog.send();
    tick();

    for (let i = 0; i < RadarDepositDialogComponent.MAX_MISSES; i += 1) {
      tick(RadarDepositDialogComponent.POLL_MS);
    }
    expect(radar.recordingProgress).toHaveBeenCalledTimes(RadarDepositDialogComponent.MAX_MISSES);
    expect(dialog.phase()).toBe('done');
    expect(dialog.outcome()).toContain('ne répond plus');
    tick();
  }));
  it('la réunion créée est offerte à l\'ouverture', fakeAsync(() => {
    const dialog = build();
    dialog.choose(file('salle.m4a', 10));
    dialog.subjectId.set('s1');
    void dialog.send();
    tick();

    tick(RadarDepositDialogComponent.POLL_MS);
    expect(dialog.phase()).toBe('done');
    expect(dialog.done()?.meetingId).toBe('m1');
    expect(dialog.outcome()).toContain('rejoint la réunion');
    tick();
  }));
});
