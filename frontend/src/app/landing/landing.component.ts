import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { AuthService } from '../core/services/auth.service';

/** Argument de valeur mis en avant sur la landing. */
interface Benefit {
  icon: string;
  title: string;
  text: string;
}

/** Étape du parcours « comment ça marche ». */
interface Step {
  index: number;
  title: string;
  text: string;
}

/**
 * Landing page publique (route `/`) destinée aux consultants.
 *
 * Page marketing statique : aucun appel réseau. L'état d'authentification est lu localement via
 * `AuthService.isAuthenticated` (présence du JWT) pour adapter les appels à l'action.
 */
@Component({
  selector: 'app-landing',
  imports: [RouterLink, MatButtonModule, MatIconModule],
  templateUrl: './landing.component.html',
  styleUrl: './landing.component.scss',
})
export class LandingComponent {
  private readonly authService = inject(AuthService);

  /** Vrai si un JWT est présent : on propose alors d'ouvrir le chat plutôt que de s'inscrire. */
  readonly isAuthenticated = this.authService.isAuthenticated;

  readonly benefits: readonly Benefit[] = [
    {
      icon: 'shield',
      title: 'Accès sécurisé',
      text: "Votre navigateur ne parle qu'à la passerelle. Les clés fournisseur ne sont jamais exposées.",
    },
    {
      icon: 'bolt',
      title: 'Expérience Claude native',
      text: 'Chat en streaming, Markdown, code et pièces jointes — sans quitter votre environnement de mission.',
    },
    {
      icon: 'workspace_premium',
      title: 'Essai gratuit 14 jours',
      text: 'Testez la plateforme sans engagement, en mode Hosted ou avec votre propre clé (BYOK).',
    },
  ];

  readonly steps: readonly Step[] = [
    { index: 1, title: 'Créez votre compte', text: 'Inscription en quelques secondes, e-mail ou Google.' },
    { index: 2, title: 'Choisissez votre mode', text: 'Hosted (clé plateforme) ou BYOK (votre clé Anthropic).' },
    { index: 3, title: 'Discutez avec Claude', text: "Ouvrez une conversation et retrouvez l'expérience Claude." },
  ];
}
