import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AuthService } from '../../core/services/auth.service';
import { ApiError, UserProfile } from '../../core/models/auth.models';

/** Écran de profil : affiche le compte courant, permet d'éditer l'e-mail et de se déconnecter. */
@Component({
  selector: 'app-profile',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './profile.component.html',
})
export class ProfileComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  readonly profile = signal<UserProfile | null>(null);
  readonly saving = signal(false);

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
  });

  ngOnInit(): void {
    this.authService.me().subscribe({
      next: (profile) => {
        this.profile.set(profile);
        this.form.controls.email.setValue(profile.email);
      },
      error: () => {
        this.snackBar.open('Impossible de charger le profil.', 'Fermer', {
          duration: 4000,
          panelClass: 'snack-error',
        });
      },
    });
  }

  saveEmail(): void {
    if (this.form.invalid || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.authService.updateProfile(this.form.getRawValue()).subscribe({
      next: (profile) => {
        this.saving.set(false);
        this.profile.set(profile);
        this.snackBar.open('Profil mis à jour. Vérifiez votre nouvel e-mail.', 'Fermer', {
          duration: 4000,
          panelClass: 'snack-success',
        });
      },
      error: (error: HttpErrorResponse) => {
        this.saving.set(false);
        const apiError = error.error as ApiError | undefined;
        const message =
          apiError?.error === 'email_already_used'
            ? 'Cet e-mail est déjà utilisé.'
            : 'Mise à jour impossible.';
        this.snackBar.open(message, 'Fermer', { duration: 4000, panelClass: 'snack-error' });
      },
    });
  }

  logout(): void {
    this.authService.logout().subscribe({
      next: () => this.redirectToLogin(),
      error: () => this.redirectToLogin(),
    });
  }

  logoutAll(): void {
    this.authService.logoutAll().subscribe({
      next: () => this.redirectToLogin(),
      error: () => this.redirectToLogin(),
    });
  }

  private redirectToLogin(): void {
    this.authService.clearToken();
    void this.router.navigate(['/login']);
  }
}
