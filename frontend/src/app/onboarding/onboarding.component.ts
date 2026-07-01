import { Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatStepperModule } from '@angular/material/stepper';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AuthService } from '../core/services/auth.service';
import { OnboardingService } from '../core/services/onboarding.service';
import { ProviderMode } from '../core/models/billing.models';

/**
 * Parcours d'onboarding en 2 étapes (F-12) : bienvenue/compte, puis choix du mode fournisseur.
 *
 * N'affiche que le compte courant (via `GET /api/me`). Aucun mode n'est persisté côté serveur
 * (voir `OnboardingService`) : l'onboarding oriente l'utilisateur vers la suite adaptée.
 */
@Component({
  selector: 'app-onboarding',
  imports: [MatStepperModule, MatButtonModule, MatIconModule],
  templateUrl: './onboarding.component.html',
  styleUrl: './onboarding.component.scss',
})
export class OnboardingComponent implements OnInit {
  private readonly authService = inject(AuthService);
  private readonly onboarding = inject(OnboardingService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  readonly email = signal<string | null>(null);
  readonly emailVerified = signal(true);
  readonly selectedMode = signal<ProviderMode | null>(null);

  ngOnInit(): void {
    this.authService.me().subscribe({
      next: (profile) => {
        this.email.set(profile.email);
        this.emailVerified.set(profile.emailVerified);
      },
      // Erreur non bloquante : on garde un message de bienvenue générique.
      error: () => this.email.set(null),
    });
  }

  selectMode(mode: ProviderMode): void {
    this.selectedMode.set(mode);
  }

  /** Valide le mode choisi à l'étape 2 puis oriente vers la suite adaptée. */
  finish(): void {
    const mode = this.selectedMode();
    if (!mode) {
      return;
    }
    this.onboarding.complete(mode);
    if (mode === 'BYOK') {
      this.snackBar.open(
        'Vous pourrez enregistrer votre clé Anthropic depuis les Réglages.',
        'Fermer',
        { duration: 4000, panelClass: 'snack-info' },
      );
      void this.router.navigate(['/billing']);
      return;
    }
    void this.router.navigate(['/chat']);
  }

  /** Reporte le choix : mode Hosted par défaut, accès direct au chat. */
  skip(): void {
    this.onboarding.complete('HOSTED');
    void this.router.navigate(['/chat']);
  }
}
