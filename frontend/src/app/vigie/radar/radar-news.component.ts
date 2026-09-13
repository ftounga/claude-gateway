import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

import { RadarNews } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { NEWS_MAX_CHARS, looksLikePastedMail, newsErrorOf, newsUndoErrorOf } from './radar-news';

/**
 * **Donner la nouvelle** (F-104 / SF-104-02) : le champ en bas du Radar d'un client. L'utilisateur écrit
 * ce qu'il sait, ou colle un courriel ; le Radar écrit dans le registre ce qu'il a dit et affiche ce qu'il
 * a compris. Pas de confirmation : **tout est annulable**, ici et depuis la chronologie.
 */
@Component({
  selector: 'app-radar-news',
  imports: [FormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatProgressSpinnerModule],
  templateUrl: './radar-news.component.html',
  styleUrl: './radar-news.component.scss',
})
export class RadarNewsComponent {
  private readonly radar = inject(RadarService);
  private readonly snackBar = inject(MatSnackBar);

  readonly hostId = input.required<string>();
  /** Le registre a changé (nouvelle écrite ou annulée) : l'onglet relit le résumé et les colonnes. */
  readonly changed = output<void>();

  readonly maxChars = NEWS_MAX_CHARS;
  readonly text = signal('');
  readonly sending = signal(false);
  readonly undoing = signal(false);
  readonly error = signal<string | null>(null);
  readonly news = signal<RadarNews | null>(null);

  readonly isMail = computed(() => looksLikePastedMail(this.text()));
  readonly canSend = computed(() => {
    const value = this.text().trim();
    return value.length > 0 && value.length <= NEWS_MAX_CHARS && !this.sending();
  });

  give(): void {
    if (!this.canSend()) {
      return;
    }
    const hostId = this.hostId();
    this.sending.set(true);
    this.error.set(null);
    this.radar.giveNews(hostId, this.text().trim()).subscribe({
      next: (news) => {
        this.sending.set(false);
        if (hostId !== this.hostId()) {
          return;
        }
        this.news.set(news);
        this.text.set('');
        if (news.changes.length > 0) {
          this.changed.emit();
        }
      },
      error: (err: unknown) => {
        this.sending.set(false);
        this.error.set(newsErrorOf(err));
      },
    });
  }

  undo(): void {
    const news = this.news();
    if (!news?.evidenceId || this.undoing()) {
      return;
    }
    this.undoing.set(true);
    this.radar.undoNews(this.hostId(), news.evidenceId).subscribe({
      next: () => {
        this.undoing.set(false);
        this.news.set(null);
        this.snackBar.open('Nouvelle annulée : le Radar a tout défait.', 'Fermer', { duration: 4000, panelClass: 'snack-success' });
        this.changed.emit();
      },
      error: (err: unknown) => {
        this.undoing.set(false);
        this.snackBar.open(newsUndoErrorOf(err), 'Fermer', { duration: 6000, panelClass: 'snack-error' });
      },
    });
  }

  dismiss(): void {
    this.news.set(null);
  }

  mailDate(value: string): string {
    return new Intl.DateTimeFormat('fr-FR', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' })
      .format(new Date(value));
  }
}
