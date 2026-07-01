import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AuthService } from '../../core/services/auth.service';

/** Écran de réinitialisation : applique un nouveau mot de passe à partir du token du lien. */
@Component({
  selector: 'app-reset-password',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
  ],
  templateUrl: './reset-password.component.html',
})
export class ResetPasswordComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly route = inject(ActivatedRoute);
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);

  private token = '';
  readonly submitting = signal(false);
  readonly done = signal(false);
  readonly tokenMissing = signal(false);

  readonly form = this.fb.nonNullable.group({
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]],
  });

  ngOnInit(): void {
    const token = this.route.snapshot.queryParamMap.get('token');
    if (!token) {
      this.tokenMissing.set(true);
      return;
    }
    this.token = token;
  }

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.authService.resetPassword(this.token, this.form.getRawValue().password).subscribe({
      next: () => {
        this.submitting.set(false);
        this.done.set(true);
      },
      error: () => {
        this.submitting.set(false);
        this.snackBar.open('Lien invalide ou expiré.', 'Fermer', {
          duration: 4000,
          panelClass: 'snack-error',
        });
      },
    });
  }
}
