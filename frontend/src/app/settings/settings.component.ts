import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AccountService } from '../core/services/account.service';
import { AuthService } from '../core/services/auth.service';
import { AccountExport } from '../core/models/account.models';
import { UserProfile } from '../core/models/auth.models';
import {
  DeleteAccountDialogComponent,
  DeleteAccountDialogData,
} from './delete-account-dialog/delete-account-dialog.component';

/**
 * Écran « Paramètres du compte » (F-11) : récapitulatif du compte et gestion RGPD des données
 * (export de portabilité, suppression définitive). Conforme au design system (MatCard, MatSnackBar,
 * confirmation destructive via MatDialog).
 */
@Component({
  selector: 'app-settings',
  imports: [RouterLink, MatCardModule, MatButtonModule, MatIconModule],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.scss',
})
export class SettingsComponent implements OnInit {
  private readonly accountService = inject(AccountService);
  private readonly authService = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);

  readonly profile = signal<UserProfile | null>(null);
  readonly exporting = signal(false);
  readonly deleting = signal(false);

  ngOnInit(): void {
    this.authService.me().subscribe({
      next: (profile) => this.profile.set(profile),
      error: () => this.notify('Impossible de charger le compte.', 'snack-error'),
    });
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
