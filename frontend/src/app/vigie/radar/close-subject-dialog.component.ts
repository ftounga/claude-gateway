import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

export interface CloseSubjectDialogData {
  subjectName: string;
  /** Les engagements encore ouverts du sujet clos. */
  commitments: string[];
}

/** Ce que l'utilisateur fait des engagements encore ouverts. */
export type CloseSubjectDialogResult = 'DONE' | 'ABANDON' | 'KEEP';

/**
 * **Un sujet clos garde des engagements ouverts** (F-102 / SF-102-02, cadrage §6) : « 1 engagement encore
 * ouvert : le fermer aussi ? ». Fermer ne se présume pas : faits, abandonnés, ou laissés ouverts.
 */
@Component({
  selector: 'app-close-subject-dialog',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>« {{ data.subjectName }} » est clos</h2>
    <mat-dialog-content>
      <p class="close-subject__lead">
        {{ data.commitments.length === 1 ? '1 engagement encore ouvert' : data.commitments.length + ' engagements encore ouverts' }} :
        {{ data.commitments.length === 1 ? 'le fermer aussi ?' : 'les fermer aussi ?' }}
      </p>
      <ul class="close-subject__list">
        @for (item of data.commitments; track $index) {
          <li>{{ item }}</li>
        }
      </ul>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" class="close-subject__keep" (click)="close('KEEP')">
        {{ data.commitments.length === 1 ? 'Le laisser ouvert' : 'Les laisser ouverts' }}
      </button>
      <button mat-stroked-button type="button" class="close-subject__abandon" (click)="close('ABANDON')">
        {{ data.commitments.length === 1 ? "L'abandonner" : 'Les abandonner' }}
      </button>
      <button mat-flat-button color="primary" type="button" class="close-subject__done" (click)="close('DONE')">
        {{ data.commitments.length === 1 ? 'Le marquer fait' : 'Les marquer faits' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .close-subject__lead {
      margin: 0 0 8px;
    }

    .close-subject__list {
      margin: 0;
      padding-left: 24px;
      color: var(--cg-text-secondary);
      font-size: 14px;
    }
  `,
})
export class CloseSubjectDialogComponent {
  readonly data = inject<CloseSubjectDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<CloseSubjectDialogComponent, CloseSubjectDialogResult>);

  close(result: CloseSubjectDialogResult): void {
    this.dialogRef.close(result);
  }
}
