import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { Router } from '@angular/router';
import { catchError, interval, of, startWith, switchMap } from 'rxjs';

import {
  JarvisNotice, JarvisNoticesClient, MAX_SHOWN, NOTICE_POLL_MS, noticeIcon,
} from '../../jarvis/jarvis-notices';

/**
 * **L'annonceur** (F-249 / SF-249-08, D17) — sur **tous les écrans** de l'application (il vit dans le shell) : les
 * annonces d'interface — « 2 mails sur « Alarme Fortec » (EDENRED) », « Déposez vos mails EDENRED », « Réunion
 * « Point Fortec » dans 10 min », le récap du matin — en pop-ups empilées en haut à droite (sous la barre ; le bas droit est à l'aide), avec leur lien et « OK ».
 * Sondage léger toutes les 60 s. Ce n'est **jamais** un message dans le fil d'un sujet : un clic ouvre le sujet, rien
 * n'y est écrit.
 */
@Component({
  selector: 'app-notice-announcer',
  imports: [MatButtonModule, MatIconModule],
  template: `
    @if (shown().length) {
      <div class="na" role="region" aria-label="Annonces" aria-live="polite">
        @for (n of shown(); track n.id) {
          <div class="na__item" [attr.data-kind]="n.kind">
            <mat-icon class="na__icon" aria-hidden="true">{{ icon(n) }}</mat-icon>
            <span class="na__text">{{ n.text }}</span>
            <span class="na__actions">
              @if (n.link) {
                <button mat-button type="button" class="na__open" (click)="open(n)">Voir</button>
              }
              <button mat-button type="button" class="na__ok" (click)="dismiss(n)">OK</button>
            </span>
          </div>
        }
      </div>
    }
  `,
  styles: [`
    .na {
      position: fixed; z-index: 1000; right: var(--cg-space-4); top: calc(64px + var(--cg-space-2));
      width: min(400px, calc(100vw - 2 * var(--cg-space-4))); display: flex; flex-direction: column;
      gap: var(--cg-space-2);
    }
    .na__item {
      display: flex; flex-wrap: wrap; align-items: center; gap: var(--cg-space-2);
      padding: var(--cg-space-3); background: var(--cg-surface); color: var(--cg-text-primary);
      border: 1px solid var(--cg-divider); border-left: 4px solid var(--cg-orange); border-radius: 4px;
      box-shadow: 0 4px 16px rgba(0, 0, 0, .16); font-family: var(--cg-font-body); font-size: 14px;
    }
    .na__icon { color: var(--cg-orange); }
    .na__text { flex: 1 1 200px; overflow-wrap: anywhere; }
    .na__actions { display: flex; margin-left: auto; }
    @media (max-width: 767px) {
      .na { right: var(--cg-space-2); left: var(--cg-space-2); top: calc(56px + var(--cg-space-2)); width: auto; }
    }
  `],
})
export class NoticeAnnouncerComponent implements OnInit {
  private readonly client = inject(JarvisNoticesClient);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly shown = signal<JarvisNotice[]>([]);

  ngOnInit(): void {
    interval(NOTICE_POLL_MS).pipe(
      startWith(0),
      switchMap(() => this.client.open().pipe(catchError(() => of(null)))),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe((list) => {
      if (list) {
        this.shown.set(list.slice(0, MAX_SHOWN));
      }
    });
  }

  icon(n: JarvisNotice): string {
    return noticeIcon(n.kind);
  }

  /** « Voir » : ouvre le lien (le sujet, Jarvis) et ferme l'annonce. */
  open(n: JarvisNotice): void {
    this.dismiss(n);
    if (n.link) {
      void this.router.navigateByUrl(n.link);
    }
  }

  /** « OK » : fermée ici et sur tous les écrans. */
  dismiss(n: JarvisNotice): void {
    this.shown.update((list) => list.filter((x) => x.id !== n.id));
    this.client.seen(n.id).subscribe({ error: () => undefined });
  }
}
