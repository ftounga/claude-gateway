import { Component, HostListener, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DictationError, DictationService } from '../../core/services/dictation.service';

/** Où en est la dictée — ce que la zone de saisie doit traduire (F-145 / SF-145-02). */
export type DictationState = 'idle' | 'recording' | 'transcribing';

/**
 * **Le micro de la zone de saisie** (F-145 / SF-145-01).
 *
 * <p><b>La barre d'espace MAINTENUE bascule en dictée</b> (SF-145-02). Ce n'est pas la touche qui
 * distingue les deux gestes, c'est la <b>durée</b> : un appui court écrit un espace, comme partout ;
 * au-delà d'une demi-seconde de maintien, on enregistre. C'est le comportement que le PO voulait, et
 * les deux conceptions précédentes — {@code Ctrl+Espace}, puis Espace hors champ — passaient à côté,
 * la première en ajoutant un geste, la seconde en le plaçant là où l'on n'écrit pas.</p>
 *
 * <p><b>Les espaces écrits pendant le maintien sont retirés</b> au basculement : la répétition du
 * clavier en insère plusieurs, et les laisser reviendrait à faire payer la dictée d'une ligne
 * salie.</p>
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
        [matTooltip]="recording() ? 'Relâchez pour transcrire' : 'Dicter — ou maintenez Espace hors du champ (Ctrl/⌘ + Espace partout)'"
      >
        <mat-icon>{{ recording() ? 'mic' : transcribing() ? 'hourglass_empty' : 'mic_none' }}</mat-icon>
      </button>
      @if (recording()) {
        <!-- L'écran DIT qu'il enregistre : sur la machine d'un client, un micro ouvert sans
             indication est inacceptable. -->
        <span class="dictation__state" role="status">Enregistrement…</span>
      } @else if (transcribing()) {
        <span class="dictation__state" role="status">Transcription…</span>
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

  /**
   * Le brouillon courant — nécessaire pour le **restaurer** au basculement.
   *
   * <p>Maintenir Espace dans un champ y insère des espaces en rafale : au moment où l'on bascule en
   * dictée, on remet le brouillon tel qu'il était avant l'appui.</p>
   */
  readonly draft = input('');

  /** Le brouillon corrigé, quand le basculement doit effacer les espaces du maintien. */
  readonly draftRestored = output<string>();

  /**
   * L'état de la dictée, pour que **la zone de saisie elle-même** le traduise.
   *
   * <p>Une icône qui change de couleur ne suffit pas : c'est dans le champ qu'on regarde quand on
   * parle, et un micro ouvert doit se voir là où l'œil est déjà posé.</p>
   */
  readonly stateChange = output<DictationState>();

  readonly recording = signal(false);
  readonly transcribing = signal(false);
  readonly error = signal<DictationError | null>(null);

  get supported(): boolean {
    return this.dictation.supported;
  }

  /** Délai de maintien au-delà duquel Espace bascule en dictée. */
  static readonly HOLD_MS = 500;

  /** La minuterie du maintien en cours, s'il y en a un. */
  private holdTimer: ReturnType<typeof setTimeout> | null = null;

  /** Le brouillon tel qu'il était avant l'appui — ce qu'on restaure au basculement. */
  private draftBeforeHold = '';

  /**
   * <kbd>Espace</kbd> : un appui court écrit, un maintien bascule en dictée.
   * <kbd>Ctrl/⌘ + Espace</kbd> : bascule <b>immédiatement</b>, pour qui préfère l'explicite.
   */
  @HostListener('document:keydown', ['$event'])
  onKeyDown(event: KeyboardEvent): void {
    if (event.code !== 'Space' || this.recording()) {
      return;
    }
    // Sur un bouton ou un lien, l'espace actionne l'élément : on ne lui vole pas la touche.
    if (isActivationTarget(event.target)) {
      return;
    }
    if (event.ctrlKey || event.metaKey) {
      event.preventDefault();
      void this.begin();
      return;
    }
    // Espace SEUL : on ne bloque pas la frappe — l'espace doit s'écrire. On arme seulement la
    // minuterie, et la répétition du clavier ne la réarme pas.
    if (event.repeat || this.holdTimer) {
      return;
    }
    this.draftBeforeHold = this.draft();
    this.holdTimer = setTimeout(() => {
      this.holdTimer = null;
      // Les espaces insérés pendant le maintien s'effacent : le brouillon redevient ce qu'il était.
      this.draftRestored.emit(this.draftBeforeHold);
      void this.begin();
    }, DictationButtonComponent.HOLD_MS);
  }

  @HostListener('document:keyup', ['$event'])
  onKeyUp(event: KeyboardEvent): void {
    if (event.code !== 'Space') {
      return;
    }
    // Relâché avant le seuil : rien ne s'est passé, et l'espace écrit reste écrit.
    this.clearHold();
    if (this.recording()) {
      event.preventDefault();
      void this.finish();
    }
  }

  /** Quitter la fenêtre pendant un maintien ne doit pas laisser une minuterie armée. */
  @HostListener('window:blur')
  onWindowBlur(): void {
    this.clearHold();
    if (this.recording()) {
      void this.finish();
    }
  }

  private clearHold(): void {
    if (this.holdTimer) {
      clearTimeout(this.holdTimer);
      this.holdTimer = null;
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
      this.stateChange.emit('recording');
    } catch (code) {
      this.error.set(code as DictationError);
      this.stateChange.emit('idle');
    }
  }

  async finish(): Promise<void> {
    if (!this.recording()) {
      return;
    }
    this.recording.set(false);
    // L'attente de la transcription est un état À PART : le micro est fermé, mais le texte n'est
    // pas encore là. Les confondre ferait croire qu'on écoute encore.
    this.transcribing.set(true);
    this.stateChange.emit('transcribing');
    try {
      const text = await this.dictation.stop();
      if (text) {
        this.transcribed.emit(text);
      }
    } catch (code) {
      this.error.set(code as DictationError);
    } finally {
      this.transcribing.set(false);
      this.stateChange.emit('idle');
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
}

/**
 * La frappe part-elle d'un élément que l'espace **actionne** ? (F-145 / SF-145-02)
 *
 * <p>Un bouton, un lien, une case : l'espace les active — c'est le contrat du clavier, et le
 * respecter est une question d'accessibilité, pas de confort. Un <b>champ de saisie</b>, lui, n'est
 * pas exclu : c'est précisément là qu'on veut pouvoir basculer en dictée par un maintien.</p>
 */
function isActivationTarget(target: EventTarget | null): boolean {
  const element = target as HTMLElement | null;
  if (!element || typeof element.closest !== 'function') {
    return false;
  }
  return element.closest('button, a[href], select, [role="button"], [role="checkbox"]') !== null;
}
