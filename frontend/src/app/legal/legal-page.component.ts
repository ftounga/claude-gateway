import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { LEGAL_LAST_UPDATE, SERVICE_NAME } from './legal-info';

/**
 * Mise en page commune des pages légales publiques (F-29 SF-29-03).
 *
 * Fournit l'en-tête, le retour à l'accueil, la colonne de lecture et le pied de page.
 * Le contenu propre à chaque document est projeté via `<ng-content>`.
 */
@Component({
  selector: 'app-legal-page',
  standalone: true,
  imports: [RouterLink, MatButtonModule, MatIconModule],
  templateUrl: './legal-page.component.html',
  styleUrl: './legal-page.component.scss',
})
export class LegalPageComponent {
  /** Titre du document, affiché en `<h1>`. */
  readonly heading = input.required<string>();

  protected readonly serviceName = SERVICE_NAME;
  protected readonly lastUpdate = LEGAL_LAST_UPDATE;
}
