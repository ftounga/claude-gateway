import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';

import { SpeechService, SpeechVoice } from '../../core/services/speech.service';

/** Longueur maximale des consignes de ton (même borne que le serveur). */
export const MAX_INSTRUCTIONS = 1000;

/**
 * **Paramètres › Vidéos** (F-215 / SF-215-01, amendement PO « je veux pouvoir changer de voix ») : la voix
 * off utilisée par défaut dans les vidéos de l'agent — liste des voix, ▶ écoute d'un échantillon fixe,
 * consignes de ton modifiables, retour au réglage par défaut. Masquée quand la voix off est éteinte.
 * Changer la voix n'altère aucune vidéo existante.
 */
@Component({
  selector: 'app-video-voice-settings',
  imports: [FormsModule, MatButtonModule, MatCardModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule],
  template: `
    @if (enabled()) {
      <mat-card class="voice">
        <h2 class="voice__title">Vidéos</h2>
        <p class="voice__text">
          La voix off des vidéos que l'agent fabrique pour vous. Vous pouvez toujours en demander une autre au fil de
          la conversation (« avec une voix féminine », « plus dynamique »). Changer ce réglage ne modifie aucune vidéo
          déjà faite.
        </p>
        <div class="voice__row">
          <mat-form-field appearance="outline" class="voice__select">
            <mat-label>Voix par défaut</mat-label>
            <mat-select [(ngModel)]="voice" name="voice">
              @for (option of voices(); track option.id) {
                <mat-option [value]="option.id">{{ option.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <button mat-stroked-button type="button" class="voice__listen" [disabled]="listening()" (click)="listen()">
            <mat-icon>play_arrow</mat-icon>
            Écouter
          </button>
        </div>
        <mat-form-field appearance="outline" class="voice__instructions">
          <mat-label>Consignes de ton</mat-label>
          <textarea matInput rows="3" [(ngModel)]="instructions" name="instructions" [maxlength]="max"
            [placeholder]="defaultInstructions()"></textarea>
          <mat-hint>Vide : « {{ defaultInstructions() }} »</mat-hint>
          @if (instructions.length > max) {
            <mat-error>{{ max }} caractères au plus.</mat-error>
          }
        </mat-form-field>
        <div class="voice__actions">
          <button mat-flat-button color="primary" type="button" class="voice__save" [disabled]="saving()" (click)="save()">
            Enregistrer
          </button>
          @if (custom()) {
            <button mat-button type="button" class="voice__reset" [disabled]="saving()" (click)="reset()">
              Revenir au réglage par défaut
            </button>
          }
        </div>
      </mat-card>
    }
  `,
  styles: `
    .voice {
      margin-bottom: var(--cg-space-4);
      padding: var(--cg-space-4);
    }

    .voice__title {
      margin: 0 0 var(--cg-space-3);
    }

    .voice__text {
      color: var(--cg-text-secondary);
      font-size: 14px;
      margin: 0 0 var(--cg-space-3);
    }

    .voice__row {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: var(--cg-space-2);
    }

    .voice__select {
      flex: 1 1 280px;
      min-width: 0;
    }

    .voice__listen {
      margin-top: var(--cg-space-2);
    }

    .voice__instructions {
      width: 100%;
    }

    .voice__actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
      margin-top: var(--cg-space-2);
    }
  `,
})
export class VideoVoiceSettingsComponent implements OnInit, OnDestroy {
  private readonly speech = inject(SpeechService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly max = MAX_INSTRUCTIONS;
  protected readonly enabled = signal(false);
  protected readonly voices = signal<SpeechVoice[]>([]);
  protected readonly defaultInstructions = signal('');
  protected readonly custom = signal(false);
  protected readonly saving = signal(false);
  protected readonly listening = signal(false);
  protected voice = '';
  protected instructions = '';
  private defaultVoice = '';
  private audio: HTMLAudioElement | null = null;
  private audioUrl: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    this.stop();
  }

  private load(): void {
    this.speech.voices().subscribe({
      next: (view) => {
        this.voices.set(view.voices);
        this.defaultVoice = view.defaultVoice;
        this.defaultInstructions.set(view.defaultInstructions);
        this.voice = view.preference?.voice ?? view.defaultVoice;
        this.instructions = view.preference?.instructions ?? '';
        this.custom.set(view.preference !== null);
        this.enabled.set(view.enabled);
      },
      // Réglage illisible ou voix off absente : la carte ne s'affiche pas.
      error: () => this.enabled.set(false),
    });
  }

  protected save(): void {
    if (this.instructions.length > MAX_INSTRUCTIONS) {
      return;
    }
    this.saving.set(true);
    this.speech.save({ voice: this.voice, instructions: this.instructions.trim() || null }).subscribe({
      next: () => {
        this.saving.set(false);
        this.custom.set(true);
        this.snackBar.open('Voix des vidéos enregistrée.', 'Fermer', { duration: 4000 });
      },
      error: (error: HttpErrorResponse) => {
        this.saving.set(false);
        const message = (error.error as { message?: string } | null)?.message ?? 'Enregistrement impossible.';
        this.snackBar.open(message, 'Fermer', { duration: 6000 });
      },
    });
  }

  protected reset(): void {
    this.saving.set(true);
    this.speech.reset().subscribe({
      next: () => {
        this.saving.set(false);
        this.custom.set(false);
        this.voice = this.defaultVoice;
        this.instructions = '';
        this.snackBar.open('Réglage par défaut rétabli.', 'Fermer', { duration: 4000 });
      },
      error: () => {
        this.saving.set(false);
        this.snackBar.open('Impossible de revenir au réglage par défaut.', 'Fermer', { duration: 6000 });
      },
    });
  }

  /** Joue l'échantillon fixe de la voix choisie (même phrase pour toutes les voix). */
  protected listen(): void {
    if (!this.voice) {
      return;
    }
    this.stop();
    this.listening.set(true);
    this.speech.sample(this.voice).subscribe({
      next: (blob) => {
        this.listening.set(false);
        this.audioUrl = URL.createObjectURL(blob);
        this.audio = new Audio(this.audioUrl);
        this.audio.play().catch(() => {
          this.snackBar.open('Lecture impossible sur ce navigateur.', 'Fermer', { duration: 4000 });
        });
      },
      error: () => {
        this.listening.set(false);
        this.snackBar.open("L'échantillon n'a pas pu être chargé : réessayez.", 'Fermer', { duration: 6000 });
      },
    });
  }

  private stop(): void {
    this.audio?.pause();
    this.audio = null;
    if (this.audioUrl) {
      URL.revokeObjectURL(this.audioUrl);
      this.audioUrl = null;
    }
  }
}
