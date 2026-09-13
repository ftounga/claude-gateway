import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { RadarCommitmentView, RadarCorrectionView, RadarEvidenceView } from '../../core/models/radar-subject.models';
import { RadarSubjectService } from '../../core/services/radar-subject.service';
import { subjectDetail } from './radar-subject.fixtures';
import { SplitSubjectDialogComponent, SplitSubjectDialogData } from './split-subject-dialog.component';

/** Séparer un sujet (F-99 / SF-99-06). */
describe('SplitSubjectDialogComponent', () => {
  let fixture: ComponentFixture<SplitSubjectDialogComponent>;
  let component: SplitSubjectDialogComponent;
  let subjects: jasmine.SpyObj<RadarSubjectService>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<SplitSubjectDialogComponent>>;

  const evidence = (id: string): RadarEvidenceView => ({
    id, source: 'TEAMS_MESSAGE', sourceRef: id, occurredAt: '2026-09-04T09:00:00Z', quote: `citation ${id}`,
    deepLink: null, authorPersonId: null,
  });

  function build(): HTMLElement {
    subjects = jasmine.createSpyObj<RadarSubjectService>('RadarSubjectService', ['split']);
    dialogRef = jasmine.createSpyObj<MatDialogRef<SplitSubjectDialogComponent>>('MatDialogRef', ['close']);
    const data: SplitSubjectDialogData = {
      hostId: 'h1',
      subject: subjectDetail({
        chronology: [evidence('p1'), evidence('p2'), evidence('p3')],
        commitments: [{ id: 'k1', description: 'Archiver le contrat' } as unknown as RadarCommitmentView],
      }),
    };
    TestBed.configureTestingModule({
      imports: [SplitSubjectDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: RadarSubjectService, useValue: subjects },
      ],
    });
    fixture = TestBed.createComponent(SplitSubjectDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const confirmButton = (root: HTMLElement) => root.querySelector('.split-subject__confirm') as HTMLButtonElement;

  it('rien de coché par défaut : Séparer attend un nom et une preuve', () => {
    const root = build();
    expect(root.querySelectorAll('.split-subject__evidence mat-checkbox').length).toBe(3);
    expect(root.querySelectorAll('.split-subject__commitments mat-checkbox').length).toBe(1);
    expect(confirmButton(root).disabled).toBeTrue();

    component.toggleEvidence('p2', true);
    fixture.detectChanges();
    expect(confirmButton(root).disabled).toBeTrue();
    expect(root.querySelector('.split-subject__name-missing')).not.toBeNull();

    component.name.set('Contrat Okta');
    fixture.detectChanges();
    expect(confirmButton(root).disabled).toBeFalse();
  });

  it('toutes les preuves cochées : refusé, et la raison est dite', () => {
    const root = build();
    component.name.set('Contrat Okta');
    ['p1', 'p2', 'p3'].forEach((id) => component.toggleEvidence(id, true));
    fixture.detectChanges();

    expect(confirmButton(root).disabled).toBeTrue();
    expect(root.querySelector('.split-subject__warning')).not.toBeNull();
  });

  it('envoie exactement ce qui est coché, dans l’ordre de la chronologie, et rend la correction', () => {
    build();
    const correction = { id: 'c7', action: 'SPLIT' } as unknown as RadarCorrectionView;
    subjects.split.and.returnValue(of(correction));
    component.name.set('  Contrat Okta ');
    component.toggleEvidence('p3', true);
    component.toggleEvidence('p1', true);
    component.toggleCommitment('k1', true);

    component.confirm();

    expect(subjects.split).toHaveBeenCalledOnceWith('h1', 's1',
      { name: 'Contrat Okta', evidenceIds: ['p1', 'p3'], commitmentIds: ['k1'] });
    expect(dialogRef.close).toHaveBeenCalledOnceWith({ correction, name: 'Contrat Okta' });
  });

  it('un refus de la gateway reste dans le dialogue, le choix est gardé', () => {
    const root = build();
    subjects.split.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: { error: 'radar_subject_merged', message: 'Ce sujet a été fusionné dans un autre.' } })));
    component.name.set('Contrat Okta');
    component.toggleEvidence('p1', true);

    component.confirm();
    fixture.detectChanges();

    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(root.querySelector('.split-subject__error')?.textContent).toContain('fusionné');
    expect(component.evidenceIds().has('p1')).toBeTrue();
  });
});
