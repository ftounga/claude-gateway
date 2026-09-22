import { Component, HostListener, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DictationError, DictationService } from '../../core/services/dictation.service';

/**
 * **Le micro de la zone de saisie** (F-145 / SF-145-01).
 *
 * <p><b>Pourquoi pas la barre d'espace seule</b>, comme demandé : elle sert à écrire. Deux gestes
 * la remplacent — ce bouton, découvrable et utilisable au doigt, et <kbd>Ctrl/⌘ + Espace</kbd>
 * maintenu, qui n'entre en conflit avec aucune saisie.</p>
 *
 * <p><b>La dictée écrit, elle n'envoie pas.</b> Le texte rejoint le brouillon ; c'est l'utilisateur
 * qui décide de l'envoyer, et il peut le corriger avant.</p>
 */
@Component({
  selector: 'app-dictation-button',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  template: `
    @if (supported) {
      <button
        mat-icon-button
        type="button"
        class="dictation"
        [class.dictation--on]="recording()"
        (pointerdown)="begin($event)"
        (pointerup)="finish()"
        (pointerleave)="finish()"
        [attr.aria-label]="recording() ? 'Enregistrement en cours — relâchez pour transcrire' : 'Dicter la demande'"
        [attr.aria-pressed]="recording()"
        [matTooltip]="recording() ? 'Relâchez pour transcrire' : 'Dicter — ou Ctrl/⌘ + Espace maintenu'"
      >
        <mat-icon>{{ recording() ? 'mic' : 'mic_none' }}</mat-icon>
      </button>
      @if (recording()) {
        <!-- L'écran DIT qu'il enregistre : sur la machine d'un client, un micro ouvert sans
             indication est inacceptable. -->
        <span class="dictation__state" role="status">Enregistrement…</span>
      }
      @if (error(); as code) {
        <span class="dictation__error" role="status">{{ message(code) }}</span>
      }
    }
  `,
  styles: `
    .dictation--on { color: var(--cg-error); }

    .dictation__state,
    .dictation__error {
      font-size: 12px;
      color: var(--cg-text-secondary);
      white-space: nowrap;
    }

    .dictation__error { color: var(--cg-error); }
  `,
})
export class DictationButtonComponent {
  private readonly dictation = inject(DictationService);

  /** Le texte transcrit, à ajouter au brouillon. **Jamais envoyé** par ce composant. */
  readonly transcribed = output<string>();

  readonly recording = signal(false);
  readonly error = signal<DictationError | null>(null);

  get supported(): boolean {
    return this.dictation.supported;
  }

  /** <kbd>Ctrl/⌘ + Espace</kbd> maintenu : enregistre tant qu'on tient. */
  @HostListener('document:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (!this.isShortcut(event) || event.repeat) {
      return;
    }
    event.preventDefault();
    void this.begin();
  }

  @HostListener('document:keyup', ['$event'])
  onKeyUp(event: KeyboardEvent): void {
    if (event.code === 'Space' && this.recording()) {
      event.preventDefault();
      void this.finish();
    }
  }

  async begin(event?: Event): Promise<void> {
    event?.preventDefault();
    if (this.recording()) {
      return;
    }
    this.error.set(null);
    try {
      await this.dictation.start();
      this.recording.set(true);
    } catch (code) {
      this.error.set(code as DictationError);
    }
  }

  async finish(): Promise<void> {
    if (!this.recording()) {
      return;
    }
    this.recording.set(false);
    try {
      const text = await this.dictation.stop();
      if (text) {
        this.transcribed.emit(text);
      }
    } catch (code) {
      this.error.set(code as DictationError);
    }
  }

  message(code: DictationError): string {
    switch (code) {
      case 'micro-refuse':
        return 'Micro refusé par le navigateur.';
      case 'non-configure':
        return "La dictée n'est pas configurée.";
      case 'trop-long':
        return 'Extrait trop long.';
      default:
        return 'La transcription a échoué.';
    }
  }

  private isShortcut(event: KeyboardEvent): boolean {
    return event.code === 'Space' && (event.ctrlKey || event.metaKey);
  }
}
