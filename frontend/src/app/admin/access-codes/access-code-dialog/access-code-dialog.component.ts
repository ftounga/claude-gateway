import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

/** Ce que le dialogue renvoie quand l'admin valide : de quoi émettre un code. */
export interface AccessCodeDraft {
  label: string;
  assignedEmail?: string;
}

/**
 * Formulaire d'émission d'un code d'accès (F-62 / SF-62-03).
 *
 * <p>Deux champs seulement, et c'est délibéré : ni la <b>durée</b> (24 h) ni la <b>validité</b>
 * (30 jours) ne sont demandées. Ce sont des réglages de produit, figés dans la ligne à l'émission ;
 * les exposer ferait de chaque code une négociation, et de deux codes émis le même jour deux
 * promesses différentes.</p>
 *
 * <p>Le <b>libellé</b> est obligatoire parce qu'il est la seule façon de reconnaître un code
 * ensuite : le code lui-même n'est jamais relisible. L'<b>e-mail</b> est facultatif — le renseigner
 * rend le code nominatif, et lui seul pourra alors le consommer.</p>
 */
@Component({
  selector: 'app-access-code-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  templateUrl: './access-code-dialog.component.html',
  styleUrl: './access-code-dialog.component.scss',
})
export class AccessCodeDialogComponent {
  private readonly dialogRef =
    inject<MatDialogRef<AccessCodeDialogComponent, AccessCodeDraft>>(MatDialogRef);

  readonly label = signal('');
  readonly assignedEmail = signal('');

  /** Vrai tant que le libellé est vide : émettre un code anonyme le rendrait introuvable. */
  invalid(): boolean {
    return this.label().trim().length === 0;
  }

  submit(): void {
    if (this.invalid()) {
      return;
    }
    const email = this.assignedEmail().trim();
    this.dialogRef.close({
      label: this.label().trim(),
      ...(email ? { assignedEmail: email } : {}),
    });
  }
}
