import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';

import { HelpService } from '../../core/services/help.service';
import { MarkdownPipe } from '../../shared/markdown.pipe';
import { httpErrorMessage } from '../../shared/http-error.util';

/** Longueur maximale d'une question — miroir exact de la contrainte serveur (SF-54-01). */
export const HELP_MAX_LENGTH = 500;

/**
 * Questions proposées d'emblée. Ce sont les obstacles réellement rencontrés pendant les deux jours
 * de mise en service qui ont motivé F-54 — pas des exemples génériques.
 */
export const HELP_SUGGESTIONS = [
  'Comment connecter ma machine ?',
  "Quel fichier télécharger si je n'ai pas Java ?",
  'Mon terminal ne sort pas : que vérifier ?',
];

/** Message de repli quand la réponse d'erreur ne porte aucun message exploitable. */
export const HELP_FALLBACK_ERROR =
  "L'aide est momentanément indisponible. Réessayez dans quelques instants.";

/**
 * Bulle d'aide produit (F-54 / SF-54-02) : une bulle fixe, un panneau, une question, une réponse.
 *
 * <p>Le composant est porté par la coquille authentifiée (`ShellComponent`) : la condition
 * « réservée aux comptes connectés » est donc <b>structurelle</b>, et non un test d'authentification
 * recopié dans un gabarit.</p>
 *
 * <p>Le service d'aide est sans mémoire : chaque question est indépendante, et le panneau affiche
 * donc une seule réponse à la fois — afficher un fil laisserait croire le contraire.</p>
 */
@Component({
  selector: 'app-help-chat-widget',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MarkdownPipe,
  ],
  templateUrl: './help-chat-widget.component.html',
  styleUrl: './help-chat-widget.component.scss',
})
export class HelpChatWidgetComponent {
  private readonly helpService = inject(HelpService);

  readonly maxLength = HELP_MAX_LENGTH;
  readonly suggestions = HELP_SUGGESTIONS;

  readonly panelOpen = signal(false);
  readonly loading = signal(false);
  readonly answer = signal<string | null>(null);
  readonly errorMessage = signal<string | null>(null);
  /** Question en cours de saisie. Signal plutôt que champ nu : le compteur en dépend. */
  readonly question = signal('');

  /** Vrai si la question saisie dépasse la longueur autorisée (compteur en état d'erreur). */
  tooLong(): boolean {
    return this.question().length > this.maxLength;
  }

  /** Vrai si la question peut partir : non vide, dans les bornes, et aucun appel en cours. */
  canSend(): boolean {
    return this.question().trim().length > 0 && !this.tooLong() && !this.loading();
  }

  togglePanel(): void {
    this.panelOpen.update((open) => !open);
  }

  closePanel(): void {
    this.panelOpen.set(false);
  }

  /** Pose l'une des questions suggérées. */
  askSuggestion(suggestion: string): void {
    if (this.loading()) {
      return;
    }
    this.question.set(suggestion);
    this.send();
  }

  /**
   * Envoie la question. La saisie n'est vidée qu'en cas de succès : après une erreur, l'utilisateur
   * retrouve son texte et peut réessayer sans le retaper.
   */
  send(): void {
    if (!this.canSend()) {
      return;
    }
    const message = this.question().trim();

    this.loading.set(true);
    this.answer.set(null);
    this.errorMessage.set(null);

    this.helpService.chat(message).subscribe({
      next: (response) => {
        this.answer.set(response.answer);
        this.question.set('');
        this.loading.set(false);
      },
      error: (error: unknown) => {
        // Le backend renvoie un message métier lisible (429, 502, 503) : on le préfère au générique.
        this.errorMessage.set(httpErrorMessage(error, HELP_FALLBACK_ERROR));
        this.loading.set(false);
      },
    });
  }
}
