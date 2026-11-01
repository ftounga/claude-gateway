import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AdminJarvisVoiceService, Audition, AuditionChoice } from './admin-jarvis-voice.service';

/**
 * Section **Voix de Jarvis — audition à l'aveugle** de l'administration (F-233 / SF-233-07, D7, D-PO-6).
 *
 * Les mêmes phrases françaises, dites par des voix anonymisées (A, B, C…) ; écoute répétable ; le choix fait de la
 * voix celle de Jarvis pour ce compte, et la révèle. L'échéance : `gpt-4o-mini-tts` s'arrête le 06/01/2027.
 */
@Component({
  selector: 'app-admin-jarvis-voice',
  imports: [MatButtonModule, MatCardModule, MatIconModule],
  template: `
    <mat-card class="audition">
      <mat-card-header>
        <mat-card-title>Voix de Jarvis — audition à l’aveugle</mat-card-title>
        <mat-card-subtitle>
          Écoutez les mêmes phrases par chaque voix, sans savoir laquelle est laquelle, puis choisissez. La voix
          actuelle (OpenAI) s’arrête le 6 janvier 2027.
        </mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        @if (audition(); as a) {
          <ol class="audition__phrases">
            @for (phrase of a.phrases; track $index) {
              <li>
                <button mat-button type="button" class="audition__phrase" [class.audition__phrase--on]="$index === phraseIndex()"
                        [attr.aria-pressed]="$index === phraseIndex()" (click)="phraseIndex.set($index)">
                  {{ phrase }}
                </button>
              </li>
            }
          </ol>

          <div class="audition__candidates">
            @for (c of a.candidates; track c.label) {
              <div class="audition__candidate">
                <span class="audition__label">Voix {{ c.label }}</span>
                <button mat-stroked-button type="button" [disabled]="!c.available || busy()" (click)="listen(c.label)"
                        [attr.aria-label]="'Écouter la voix ' + c.label">
                  <mat-icon aria-hidden="true">play_arrow</mat-icon> Écouter
                </button>
                <button mat-flat-button color="primary" type="button" [disabled]="!c.available || busy()"
                        (click)="choose(c.label)">
                  Choisir
                </button>
                @if (!c.available) {
                  <span class="audition__note">indisponible (clé du fournisseur absente)</span>
                }
              </div>
            }
          </div>

          @if (a.chosen; as chosen) {
            <p class="audition__chosen" role="status">
              Voix de Jarvis : <strong>{{ chosen.name }}</strong>
              @if (chosen.label) { (voix {{ chosen.label }}) }. Elle peut aussi servir à la voix off des vidéos.
              <button mat-button type="button" [disabled]="busy()" (click)="reset()">Revenir à la voix par défaut</button>
            </p>
          }
        } @else if (failed()) {
          <p class="audition__note">L’audition n’a pas pu être chargée.</p>
        } @else {
          <p class="audition__note">Chargement…</p>
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .audition__phrases {
      margin: 0 0 var(--cg-space-3);
      padding-left: var(--cg-space-4);
    }

    .audition__phrase {
      text-align: left;
      white-space: normal;
      height: auto;
      min-height: 36px;
    }

    .audition__phrase--on {
      color: var(--cg-orange-2);
      font-weight: 600;
    }

    .audition__candidates {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-2);
    }

    .audition__candidate {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
    }

    .audition__label {
      min-width: 72px;
      font-weight: 600;
    }

    .audition__note {
      color: var(--cg-text-secondary);
    }

    .audition__chosen {
      margin-top: var(--cg-space-3);
    }
  `,
})
export class AdminJarvisVoiceComponent implements OnInit, OnDestroy {
  private readonly service = inject(AdminJarvisVoiceService);
  private readonly snackBar = inject(MatSnackBar);

  readonly audition = signal<Audition | null>(null);
  readonly failed = signal(false);
  readonly busy = signal(false);
  readonly phraseIndex = signal(0);
  private player: HTMLAudioElement | null = null;
  private playerUrl: string | null = null;

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    this.stopPlayer();
  }

  load(): void {
    this.service.audition().subscribe({
      next: (a) => {
        this.failed.set(false);
        this.audition.set(a);
      },
      error: () => this.failed.set(true),
    });
  }

  listen(label: string): void {
    this.busy.set(true);
    this.service.sample(label, this.phraseIndex()).subscribe({
      next: (blob) => {
        this.busy.set(false);
        this.stopPlayer();
        this.playerUrl = URL.createObjectURL(blob);
        this.player = new Audio(this.playerUrl);
        void this.player.play().catch(() => this.snackBar.open('La lecture a été refusée par le navigateur.', 'OK',
          { duration: 4000 }));
      },
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        this.snackBar.open(sampleMessage(error.status), 'OK', { duration: 5000 });
      },
    });
  }

  choose(label: string): void {
    this.busy.set(true);
    this.service.choose(label).subscribe({
      next: (choice: AuditionChoice) => {
        this.busy.set(false);
        this.snackBar.open(`La voix ${label} était ${choice.name} : c’est désormais la voix de Jarvis.`, 'OK',
          { duration: 6000 });
        this.load();
      },
      error: (error: HttpErrorResponse) => {
        this.busy.set(false);
        this.snackBar.open(sampleMessage(error.status), 'OK', { duration: 5000 });
      },
    });
  }

  reset(): void {
    this.busy.set(true);
    this.service.reset().subscribe({
      next: () => {
        this.busy.set(false);
        this.snackBar.open('Jarvis reprend la voix par défaut.', 'OK', { duration: 4000 });
        this.load();
      },
      error: () => {
        this.busy.set(false);
        this.snackBar.open('Le retour à la voix par défaut a échoué.', 'OK', { duration: 5000 });
      },
    });
  }

  private stopPlayer(): void {
    this.player?.pause();
    this.player = null;
    if (this.playerUrl) {
      URL.revokeObjectURL(this.playerUrl);
      this.playerUrl = null;
    }
  }
}

/** Ce qui a empêché l'écoute ou le choix, en mots de l'administrateur. */
export function sampleMessage(status: number): string {
  switch (status) {
    case 503: return 'Cette voix n’est pas disponible : la clé du fournisseur manque.';
    case 429: return 'Plafond du mois atteint pour la voix.';
    case 404: return 'Cette voix n’est plus dans l’audition : rechargez.';
    default: return 'La voix n’a pas répondu : réessayez.';
  }
}
