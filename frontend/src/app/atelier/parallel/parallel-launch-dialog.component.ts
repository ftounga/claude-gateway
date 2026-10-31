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
  /**
   * Une suite a proposé ce chantier : [Afficher ici] est offert à côté de [Lancer à côté] (F-226 : « Basculer ici »
   * avant). Un chantier qui ne peut pas partir offre toujours [Afficher ici], seul.
   */
  offerSwitch: boolean;
}

/**
 * Le choix rendu : partir à côté, l'afficher ici, ou rien (`undefined`). F-226 / SF-226-03 : « Afficher à côté »
 * (ouvrir le volet sans rien lancer) a disparu — il se confondait avec [Lancer à côté].
 */
export type ParallelLaunchDecision = 'launch' | 'switch';

/**
 * **« Lancer le chantier N en parallèle ? »** (F-221 / SF-221-04) — l'overlay demandé par le PO. Valider
 * ouvre le split et fait partir le chantier à droite, sans phrase dans le chat. Charte : `MatDialog`, jamais
 * `window.confirm` ; action principale en `mat-flat-button`. **Deux boutons d'action au plus** (F-226 / SF-226-03) :
 * [Lancer à côté] et [Afficher ici] — un chantier qui ne peut pas partir n'offre que [Afficher ici], avec le pourquoi.
 */
@Component({
  selector: 'app-parallel-launch-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule],
  template: `
    <h2 mat-dialog-title>
      @if (data.choice.mode) {
        Lancer le chantier {{ data.number }} à côté ?
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
        <p>Affichez-le ici pour voir ce qui l’empêche et le débloquer.</p>
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
      @if (data.choice.mode) {
        @if (data.offerSwitch) {
          <button mat-button type="button" class="pl__switch" (click)="close('switch')">Afficher ici</button>
        }
        <button mat-flat-button type="button" class="pl__go" (click)="close('launch')" cdkFocusInitial>
          <mat-icon>view_column</mat-icon>
          Lancer à côté
        </button>
      } @else {
        <button mat-flat-button type="button" class="pl__switch pl__go" (click)="close('switch')" cdkFocusInitial>
          <mat-icon>center_focus_strong</mat-icon>
          Afficher ici
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
