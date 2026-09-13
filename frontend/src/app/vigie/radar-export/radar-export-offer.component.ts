import { Component, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { RadarExporter } from './radar-export';

/**
 * **L'export proposé** (F-99 / SF-99-07) : un bouton qui télécharge le Radar du client en Markdown, et ce
 * qu'il en est advenu. Réemployé dans les dialogues qui effacent un Radar — il ne purge jamais rien.
 */
@Component({
  selector: 'app-radar-export-offer',
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  template: `
    <div class="radar-export">
      @if (lead()) {
        <p class="radar-export__lead">{{ lead() }}</p>
      }
      <div class="radar-export__row">
        <button
          mat-stroked-button
          type="button"
          class="radar-export__button"
          [disabled]="state() === 'busy'"
          (click)="download()"
        >
          @if (state() === 'busy') {
            <mat-spinner diameter="16"></mat-spinner>
          } @else {
            <mat-icon>download</mat-icon>
          }
          {{ state() === 'error' ? 'Réessayer' : 'Exporter le Radar (Markdown)' }}
        </button>
        @if (state() === 'done') {
          <span class="radar-export__done" role="status">
            <mat-icon aria-hidden="true">check_circle</mat-icon>
            Exporté : {{ fileName() }}
          </span>
        }
        @if (state() === 'error') {
          <span class="radar-export__error" role="alert">Le Radar n'a pas pu être exporté.</span>
        }
      </div>
    </div>
  `,
  styles: `
    .radar-export {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-2);
      padding: var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
    }

    .radar-export__lead {
      margin: 0;
      font-size: 14px;
    }

    .radar-export__row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .radar-export__done {
      display: inline-flex;
      align-items: center;
      gap: var(--cg-space-1);
      font-size: 14px;

      mat-icon {
        color: var(--cg-success);
        font-size: 18px;
        width: 18px;
        height: 18px;
      }
    }

    .radar-export__error {
      color: var(--cg-error);
      font-size: 14px;
    }
  `,
})
export class RadarExportOfferComponent {
  private readonly exporter = inject(RadarExporter);

  readonly hostId = input.required<string>();
  readonly hostName = input<string | null>(null);
  /** La phrase qui précède le bouton (« Avant d'effacer, gardez-en une copie. »). */
  readonly lead = input<string | null>(null);

  readonly state = signal<'idle' | 'busy' | 'done' | 'error'>('idle');
  readonly fileName = signal<string | null>(null);

  download(): void {
    if (this.state() === 'busy') {
      return;
    }
    this.state.set('busy');
    this.exporter.download(this.hostId(), this.hostName()).subscribe({
      next: (name) => {
        this.fileName.set(name);
        this.state.set('done');
      },
      error: () => this.state.set('error'),
    });
  }
}
