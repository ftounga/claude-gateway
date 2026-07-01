import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AuthService } from '../../core/services/auth.service';
import { ApiError } from '../../core/models/auth.models';
import { HttpErrorResponse } from '@angular/common/http';

/** Écran d'inscription : crée un compte local (email/mot de passe), puis invite à vérifier l'e-mail. */
@Component({
  selector: 'app-register',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './register.component.html',
})
export class RegisterComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);

  readonly submitting = signal(false);
  readonly registeredEmail = signal<string | null>(null);

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]],
  });

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    const email = this.form.getRawValue().email;
    this.authService.register(this.form.getRawValue()).subscribe({
      next: () => {
        this.submitting.set(false);
        this.registeredEmail.set(email);
      },
      error: (error: HttpErrorResponse) => {
        this.submitting.set(false);
        const apiError = error.error as ApiError | undefined;
        const message =
          apiError?.error === 'email_already_used'
            ? 'Un compte existe déjà pour cet e-mail.'
            : 'Inscription impossible. Vérifiez vos informations.';
        this.snackBar.open(message, 'Fermer', { duration: 4000, panelClass: 'snack-error' });
      },
    });
  }
}
