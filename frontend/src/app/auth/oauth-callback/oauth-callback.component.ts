import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

import { AuthService } from '../../core/services/auth.service';

/**
 * Réception du retour OAuth Google : lit le JWT dans le fragment d'URL (`#token=...`),
 * le stocke et redirige vers le profil. En cas d'`#error=...`, affiche un message.
 */
@Component({
  selector: 'app-oauth-callback',
  imports: [RouterLink, MatCardModule, MatIconModule, MatButtonModule],
  templateUrl: './oauth-callback.component.html',
})
export class OauthCallbackComponent implements OnInit {
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  readonly failed = signal(false);

  ngOnInit(): void {
    const fragment = window.location.hash.startsWith('#')
      ? window.location.hash.substring(1)
      : window.location.hash;
    const params = new URLSearchParams(fragment);
    const token = params.get('token');

    if (token) {
      this.authService.storeToken(token);
      void this.router.navigate(['/profile']);
      return;
    }
    this.failed.set(true);
  }
}
