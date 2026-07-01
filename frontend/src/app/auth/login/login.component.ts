import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AuthService } from '../../core/services/auth.service';
import { OnboardingService } from '../../core/services/onboarding.service';

/** Écran de connexion : e-mail/mot de passe (JWT) + bouton « Continuer avec Google ». */
@Component({
  selector: 'app-login',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './login.component.html',
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly onboarding = inject(OnboardingService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  readonly submitting = signal(false);

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required]],
  });

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.authService.login(this.form.getRawValue()).subscribe({
      next: () => {
        this.submitting.set(false);
        void this.router.navigate([this.onboarding.postLoginPath()]);
      },
      error: () => {
        this.submitting.set(false);
        this.snackBar.open('Identifiants invalides.', 'Fermer', {
          duration: 4000,
          panelClass: 'snack-error',
        });
      },
    });
  }

  loginWithGoogle(): void {
    window.location.href = this.authService.googleLoginUrl;
  }
}
