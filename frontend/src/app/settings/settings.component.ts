import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AccountService } from '../core/services/account.service';
import { ApiKeyService } from '../core/services/api-key.service';
import { AuthService } from '../core/services/auth.service';
import { GitTokenService } from '../core/services/git-token.service';
import { AccountExport } from '../core/models/account.models';
import { ApiKeyStatus, ProviderMode } from '../core/models/api-key.models';
import { GitTokenStatus } from '../core/models/git-token.models';
import { UserProfile } from '../core/models/auth.models';
import {
  DeleteAccountDialogComponent,
  DeleteAccountDialogData,
} from './delete-account-dialog/delete-account-dialog.component';
import { RemoveApiKeyDialogComponent } from './remove-api-key-dialog/remove-api-key-dialog.component';
import { RemoveGitTokenDialogComponent } from './remove-git-token-dialog/remove-git-token-dialog.component';

/**
 * Écran « Paramètres du compte » : récapitulatif du compte, gestion RGPD des données (F-11),
 * gestion de la clé API personnelle BYOK (F-03 : ajout masqué, statut, suppression, bascule
 * Hosted/BYOK) et gestion du jeton GitHub de l'Atelier (F-31 : ajout, remplacement, retrait, jeton
 * masqué). Conforme au design system (MatCard, MatSnackBar, confirmations via MatDialog,
 * mat-form-field outline).
 */
@Component({
  selector: 'app-settings',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    DatePipe,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
  ],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.scss',
})
export class SettingsComponent implements OnInit {
  private readonly accountService = inject(AccountService);
  private readonly apiKeyService = inject(ApiKeyService);
  private readonly gitTokenService = inject(GitTokenService);
  private readonly authService = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);

  readonly profile = signal<UserProfile | null>(null);
  readonly exporting = signal(false);
  readonly deleting = signal(false);

  // --- BYOK (F-03) ---
  readonly apiKeyStatus = signal<ApiKeyStatus | null>(null);
  readonly apiKeyControl = new FormControl('', { nonNullable: true, validators: [Validators.required] });
  readonly savingKey = signal(false);
  readonly deletingKey = signal(false);
  readonly togglingMode = signal(false);

  // --- Jeton GitHub (F-31) ---
  readonly gitTokenStatus = signal<GitTokenStatus | null>(null);
  readonly gitTokenControl = new FormControl('', { nonNullable: true, validators: [Validators.required] });
  readonly savingGitToken = signal(false);
  readonly deletingGitToken = signal(false);

  ngOnInit(): void {
    this.authService.me().subscribe({
      next: (profile) => this.profile.set(profile),
      error: () => this.notify('Impossible de charger le compte.', 'snack-error'),
    });
    this.loadApiKey();
    this.loadGitToken();
  }

  /** Charge le statut de la clé BYOK de l'utilisateur. */
  loadApiKey(): void {
    this.apiKeyService.getStatus().subscribe({
      next: (status) => this.apiKeyStatus.set(status),
      error: () => this.notify('Impossible de charger la clé API.', 'snack-error'),
    });
  }

  /** Valide (backend) puis enregistre la clé personnelle ; vide le champ à succès. */
  saveApiKey(): void {
    if (this.savingKey()) {
      return;
    }
    if (this.apiKeyControl.invalid) {
      this.apiKeyControl.markAsTouched();
      return;
    }
    this.savingKey.set(true);
    this.apiKeyService.saveKey({ apiKey: this.apiKeyControl.value }).subscribe({
      next: (status) => {
        this.savingKey.set(false);
        this.apiKeyStatus.set(status);
        this.apiKeyControl.reset();
        this.notify('Clé API enregistrée. Mode BYOK activé.', 'snack-success');
      },
      error: (error: HttpErrorResponse) => {
        this.savingKey.set(false);
        this.notify(this.saveErrorMessage(error), 'snack-error');
      },
    });
  }

  /** Ouvre la confirmation puis supprime la clé si confirmé. */
  deleteApiKey(): void {
    if (this.deletingKey()) {
      return;
    }
    const dialogRef = this.dialog.open<RemoveApiKeyDialogComponent, void, boolean>(
      RemoveApiKeyDialogComponent,
      { width: '480px' },
    );
    dialogRef.afterClosed().subscribe((confirmed) => {
      if (confirmed) {
        this.performKeyDeletion();
      }
    });
  }

  /** Bascule le mode fournisseur Hosted/BYOK. */
  setMode(mode: ProviderMode): void {
    if (this.togglingMode()) {
      return;
    }
    this.togglingMode.set(true);
    this.apiKeyService.setMode({ mode }).subscribe({
      next: (status) => {
        this.togglingMode.set(false);
        this.apiKeyStatus.set(status);
        this.notify(
          mode === 'BYOK' ? 'Mode BYOK activé.' : 'Mode Hosted activé.',
          'snack-success',
        );
      },
      error: () => {
        this.togglingMode.set(false);
        this.notify('La bascule du mode a échoué.', 'snack-error');
      },
    });
  }

  /** Charge l'état du jeton GitHub de l'utilisateur. */
  loadGitToken(): void {
    this.gitTokenService.getStatus().subscribe({
      next: (status) => this.gitTokenStatus.set(status),
      error: () => this.notify('Impossible de charger le jeton GitHub.', 'snack-error'),
    });
  }

  /** Vérifie (backend, auprès de GitHub) puis enregistre le jeton ; vide le champ à succès. */
  saveGitToken(): void {
    if (this.savingGitToken()) {
      return;
    }
    if (this.gitTokenControl.invalid) {
      this.gitTokenControl.markAsTouched();
      return;
    }
    this.savingGitToken.set(true);
    this.gitTokenService.saveToken({ token: this.gitTokenControl.value }).subscribe({
      next: (status) => {
        this.savingGitToken.set(false);
        this.gitTokenStatus.set(status);
        this.gitTokenControl.reset();
        this.notify('Jeton GitHub enregistré.', 'snack-success');
      },
      error: (error: HttpErrorResponse) => {
        this.savingGitToken.set(false);
        this.notify(this.gitTokenErrorMessage(error), 'snack-error');
      },
    });
  }

  /** Ouvre la confirmation puis retire le jeton GitHub si confirmé. */
  deleteGitToken(): void {
    if (this.deletingGitToken()) {
      return;
    }
    const dialogRef = this.dialog.open<RemoveGitTokenDialogComponent, void, boolean>(
      RemoveGitTokenDialogComponent,
      { width: '480px' },
    );
    dialogRef.afterClosed().subscribe((confirmed) => {
      if (confirmed) {
        this.performGitTokenDeletion();
      }
    });
  }

  private performGitTokenDeletion(): void {
    this.deletingGitToken.set(true);
    this.gitTokenService.deleteToken().subscribe({
      next: () => {
        this.deletingGitToken.set(false);
        this.gitTokenStatus.set({
          present: false,
          githubLogin: null,
          maskedToken: null,
          last4: null,
          createdAt: null,
          updatedAt: null,
        });
        this.notify('Jeton GitHub retiré.', 'snack-success');
      },
      error: () => {
        this.deletingGitToken.set(false);
        this.notify('Le retrait du jeton a échoué.', 'snack-error');
      },
    });
  }

  /** Messages distincts : jeton refusé par GitHub (400) vs GitHub indisponible (503). */
  private gitTokenErrorMessage(error: HttpErrorResponse): string {
    if (error.status === 400) {
      return 'Jeton refusé par GitHub (invalide, révoqué ou expiré).';
    }
    if (error.status === 503) {
      return 'GitHub est momentanément indisponible. Réessayez dans un instant.';
    }
    return "L'enregistrement du jeton a échoué. Veuillez réessayer.";
  }

  private performKeyDeletion(): void {
    this.deletingKey.set(true);
    this.apiKeyService.deleteKey().subscribe({
      next: () => {
        this.deletingKey.set(false);
        this.apiKeyStatus.set({
          present: false,
          maskedKey: null,
          last4: null,
          provider: null,
          mode: 'HOSTED',
          validatedAt: null,
          createdAt: null,
        });
        this.notify('Clé API supprimée. Mode Hosted activé.', 'snack-success');
      },
      error: () => {
        this.deletingKey.set(false);
        this.notify('La suppression de la clé a échoué.', 'snack-error');
      },
    });
  }

  private saveErrorMessage(error: HttpErrorResponse): string {
    if (error.status === 400) {
      return 'Clé API invalide ou refusée par le fournisseur.';
    }
    if (error.status === 503) {
      return 'La gestion de clé API est momentanément indisponible.';
    }
    return "L'enregistrement de la clé a échoué. Veuillez réessayer.";
  }

  /** Exporte les données de l'utilisateur et déclenche le téléchargement du fichier JSON. */
  exportData(): void {
    if (this.exporting()) {
      return;
    }
    this.exporting.set(true);
    this.accountService.exportData().subscribe({
      next: (data) => {
        this.exporting.set(false);
        this.triggerDownload(data);
        this.notify('Export généré. Le téléchargement a démarré.', 'snack-success');
      },
      error: () => {
        this.exporting.set(false);
        this.notify("L'export a échoué. Veuillez réessayer.", 'snack-error');
      },
    });
  }

  /** Ouvre la confirmation destructive puis supprime le compte si l'utilisateur confirme. */
  deleteAccount(): void {
    const user = this.profile();
    if (!user || this.deleting()) {
      return;
    }
    const dialogRef = this.dialog.open<
      DeleteAccountDialogComponent,
      DeleteAccountDialogData,
      boolean
    >(DeleteAccountDialogComponent, { data: { email: user.email }, width: '480px' });

    dialogRef.afterClosed().subscribe((confirmed) => {
      if (confirmed) {
        this.performDeletion();
      }
    });
  }

  private performDeletion(): void {
    this.deleting.set(true);
    this.accountService.deleteAccount().subscribe({
      next: () => {
        this.authService.clearToken();
        this.notify('Votre compte a été supprimé.', 'snack-success');
        void this.router.navigate(['/login']);
      },
      error: () => {
        this.deleting.set(false);
        this.notify('La suppression a échoué. Veuillez réessayer.', 'snack-error');
      },
    });
  }

  /** Sérialise l'export en Blob et déclenche un téléchargement client (isolé pour être testable). */
  protected triggerDownload(data: AccountExport): void {
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'claude-gateway-export.json';
    link.click();
    URL.revokeObjectURL(url);
  }

  private notify(message: string, panelClass: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 4000, panelClass });
  }
}
