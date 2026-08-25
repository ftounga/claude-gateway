import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';

/** Dépôt choisi par l'utilisateur. `branch` et `name` vides valent « valeur par défaut ». */
export interface PickedGitRepository {
  repoUrl: string;
  branch?: string;
  name?: string;
}

/** URL de dépôt GitHub acceptée — même forme que la validation du backend (SF-31-02). */
const REPO_URL_PATTERN = /^https:\/\/(?:www\.)?github\.com\/[A-Za-z0-9._-]{1,100}\/[A-Za-z0-9._-]{1,100}(?:\.git)?\/?$/;

/**
 * Dialogue d'ouverture d'un projet sur un dépôt GitHub (F-31 / SF-31-02).
 *
 * <p>La validation d'URL est **répliquée du backend** volontairement : coller une URL de page GitHub
 * (`.../tree/main`) est l'erreur la plus probable, et la signaler pendant la saisie évite un
 * aller-retour réseau pour l'apprendre. Le backend reste seul juge — le client ne fait qu'anticiper.</p>
 *
 * <p>Aucun secret n'est saisi ici : le jeton d'accès est enregistré séparément, dans les réglages.</p>
 */
@Component({
  selector: 'app-git-repo-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
  ],
  templateUrl: './git-repo-dialog.component.html',
  styleUrl: './git-repo-dialog.component.scss',
})
export class GitRepoDialogComponent {
  private readonly dialogRef =
    inject<MatDialogRef<GitRepoDialogComponent, PickedGitRepository>>(MatDialogRef);

  readonly repoUrl = signal('');
  readonly branch = signal('');
  readonly name = signal('');

  /** Vrai dès que la saisie ressemble à une URL de dépôt exploitable. */
  readonly urlValid = computed(() => REPO_URL_PATTERN.test(this.repoUrl().trim()));

  /** Message d'erreur affiché sous le champ, ou `null` tant qu'il n'y a rien à signaler. */
  readonly urlError = computed(() => {
    const value = this.repoUrl().trim();
    if (value.length === 0 || this.urlValid()) {
      return null;
    }
    return 'Attendu : https://github.com/proprietaire/depot';
  });

  cancel(): void {
    this.dialogRef.close();
  }

  confirm(): void {
    if (!this.urlValid()) {
      return;
    }
    const branch = this.branch().trim();
    const name = this.name().trim();
    this.dialogRef.close({
      repoUrl: this.repoUrl().trim(),
      branch: branch.length > 0 ? branch : undefined,
      name: name.length > 0 ? name : undefined,
    });
  }
}
