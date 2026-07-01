import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

import { AuthService } from '../../core/services/auth.service';

type VerifyState = 'checking' | 'success' | 'error';

/** Page de confirmation : valide le token de vérification d'e-mail issu du lien reçu. */
@Component({
  selector: 'app-verify-email',
  imports: [RouterLink, MatCardModule, MatIconModule, MatButtonModule],
  templateUrl: './verify-email.component.html',
})
export class VerifyEmailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly authService = inject(AuthService);

  readonly state = signal<VerifyState>('checking');
  readonly email = signal<string>('');

  ngOnInit(): void {
    const token = this.route.snapshot.queryParamMap.get('token');
    if (!token) {
      this.state.set('error');
      return;
    }
    this.authService.verifyEmail(token).subscribe({
      next: (response) => {
        this.email.set(response.email);
        this.state.set('success');
      },
      error: () => this.state.set('error'),
    });
  }
}
