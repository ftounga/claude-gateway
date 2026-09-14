import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';

/** Données passées au dialogue de révocation (F-112 / SF-112-03). */
export interface RevokeTokenDialogData {
  tokenName: string;
}

/**
 * Confirmation de révocation d'un jeton personnel MCP. Action destructive → confirmée par MatDialog
 * (règle du design system : jamais `window.confirm`).
 */
@Component({
  selector: 'app-revoke-token-dialog',
  imports: [MatDialogModule, MatButtonModule],
  template: `
    <h2 mat-dialog-title>Révoquer le jeton</h2>
    <mat-dialog-content>
      <p>
        Le jeton « {{ data.tokenName }} » cessera immédiatement d'ouvrir le serveur MCP. Les IA qui
        l'utilisent perdront l'accès. Cette action est définitive.
      </p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button [mat-dialog-close]="false">Annuler</button>
      <button mat-flat-button color="warn" [mat-dialog-close]="true">Révoquer</button>
    </mat-dialog-actions>
  `,
})
export class RevokeTokenDialogComponent {
  readonly data = inject<RevokeTokenDialogData>(MAT_DIALOG_DATA);
  readonly dialogRef = inject(MatDialogRef<RevokeTokenDialogComponent>);
}
