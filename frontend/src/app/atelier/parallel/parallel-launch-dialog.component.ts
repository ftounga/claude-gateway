import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

import { ParallelChoice } from './parallel-launch';

/** Ce que l'overlay reçoit (F-221 / SF-221-04). */
export interface ParallelLaunchDialogData {
  number: number;
  title: string | null;
  choice: ParallelChoice;
  /** Une suite a proposé ce chantier : [Basculer ici] reste offert. */
  offerSwitch: boolean;
}

/** Le choix rendu : partir à côté, ouvrir à côté sans partir, basculer ici, ou rien (`undefined`). */
export type ParallelLaunchDecision = 'launch' | 'open' | 'switch';

/**
 * **« Lancer le chantier N en parallèle ? »** (F-221 / SF-221-04) — l'overlay demandé par le PO. Valider
 * ouvre le split et fait partir le chantier à droite, sans phrase dans le chat. Charte : `MatDialog`, jamais
 * `window.confirm` ; action principale en `mat-flat-button`.
 */
@Component({
  selector: 'app-parallel-launch-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>
      @if (data.choice.mode) {
        Lancer le chantier {{ data.number }} en parallèle ?
      } @else {
        Le chantier {{ data.number }} ne peut pas partir maintenant
      }
    </h2>
    <mat-dialog-content class="pl">
      @if (data.title) {
        <p class="pl__title">Chantier {{ data.number }} · {{ data.title }}</p>
      }
      @if (data.choice.mode === 'UNTIL_BLOCKED') {
        <p>Il avancera <strong>jusqu’au blocage</strong> dans un volet à droite : les étapes à la portée de
          l’outil, sans question, puis il s’arrête sur ce qui vous attend. Ce terminal-ci continue à gauche.</p>
      } @else if (data.choice.mode === 'STRICT') {
        <p>Il partira <strong>en autonome</strong> dans un volet à droite : toutes les étapes restantes, sans
          question. Ce terminal-ci continue à gauche.</p>
      } @else {
        <p>Vous pouvez l’ouvrir à droite pour voir ce qui l’empêche, et le débloquer.</p>
      }
      @if (data.choice.reasons.length > 0) {
        <ul class="pl__reasons" [attr.aria-label]="data.choice.mode ? 'Ce qui restera' : 'Ce qui l’empêche'">
          @for (reason of data.choice.reasons; track $index) {
            <li><mat-icon aria-hidden="true">{{ data.choice.mode ? 'schedule' : 'block' }}</mat-icon>{{ reason }}</li>
          }
        </ul>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" (click)="close()">Annuler</button>
      @if (data.offerSwitch) {
        <button mat-button type="button" (click)="close('switch')">Basculer ici</button>
      }
      @if (data.choice.mode) {
        <button mat-flat-button type="button" class="pl__go" (click)="close('launch')" cdkFocusInitial>
          <mat-icon>view_column</mat-icon>
          Lancer en parallèle
        </button>
      } @else {
        <button mat-flat-button type="button" class="pl__go" (click)="close('open')" cdkFocusInitial>
          <mat-icon>view_column</mat-icon>
          Ouvrir à côté
        </button>
      }
    </mat-dialog-actions>
  `,
  styles: [`
    .pl { max-width: 520px; }
    .pl__title { font-weight: 600; margin: 0 0 var(--cg-space-2); }
    .pl__reasons { list-style: none; margin: var(--cg-space-2) 0 0; padding: 0; display: grid; gap: var(--cg-space-1); }
    .pl__reasons li { display: flex; align-items: flex-start; gap: var(--cg-space-2); color: var(--cg-text-secondary); }
    .pl__reasons mat-icon { font-size: 16px; width: 16px; height: 16px; flex: 0 0 auto; }
    .pl__go mat-icon { margin-right: var(--cg-space-1); }
  `],
})
export class ParallelLaunchDialogComponent {
  private readonly ref = inject(MatDialogRef<ParallelLaunchDialogComponent, ParallelLaunchDecision>);
  readonly data = inject<ParallelLaunchDialogData>(MAT_DIALOG_DATA);

  close(decision?: ParallelLaunchDecision): void {
    this.ref.close(decision);
  }
}
