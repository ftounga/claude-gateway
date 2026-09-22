import { Component, DestroyRef, HostListener, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';

import { ExportService } from '../../core/services/export.service';
import { PresentationService } from '../../core/services/presentation.service';

interface Slide {
  index: number;
  url: SafeUrl | null;
}

/**
 * **La visionneuse de deck** (F-129 / SF-129-03) : un overlay plein écran qui rend une présentation
 * **entièrement lisible dans l'app** — la slide courante en grand, un rail de miniatures de toutes les
 * slides, la navigation (boutons, clavier ← →), Échap pour fermer, et le téléchargement du vrai .pptx.
 *
 * <p>Les images (une par slide) sont produites <b>ailleurs</b> (sandbox/terminal, SF-129-03) et servies
 * par la gateway ; elles sont chargées en blob (JWT porté), transformées en URL objet et révoquées à la
 * fermeture. Une présentation sans rendu affiche un état de repli, le téléchargement restant offert.</p>
 */
@Component({
  selector: 'app-deck-viewer',
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  template: `
    <div class="deckview" role="dialog" aria-modal="true" [attr.aria-label]="'Présentation ' + title()">
      <header class="deckview__head">
        <div class="deckview__icon" aria-hidden="true"><mat-icon>slideshow</mat-icon></div>
        <div class="deckview__titles">
          <h2 class="deckview__title">{{ title() }}</h2>
          @if (slideCount()) {
            <p class="deckview__meta">{{ slideCount() }} slides</p>
          }
        </div>
        <div class="deckview__actions">
          <button mat-stroked-button type="button" class="deckview__download" (click)="download()">
            <mat-icon>download</mat-icon>Télécharger le .pptx
          </button>
          <button mat-icon-button type="button" class="deckview__close" aria-label="Fermer" (click)="close.emit()">
            <mat-icon>close</mat-icon>
          </button>
        </div>
      </header>

      @if (!slideCount()) {
        <div class="deckview__empty">
          <p>L'aperçu n'est pas encore disponible pour cette présentation.</p>
          <p class="deckview__hint">Vous pouvez la télécharger, ou redemander à l'agent d'en produire le rendu.</p>
        </div>
      } @else {
        <div class="deckview__deck">
          <aside class="deckview__rail" aria-label="Miniatures des slides">
            @for (slide of slides(); track slide.index) {
              <button type="button" class="deckview__thumb" [class.deckview__thumb--cur]="slide.index === current()"
                [attr.aria-label]="'Slide ' + slide.index" [attr.aria-current]="slide.index === current()"
                (click)="go(slide.index)">
                <span class="deckview__n">{{ slide.index }}</span>
                @if (slide.url) {
                  <img class="deckview__mini" [src]="slide.url" [alt]="'Slide ' + slide.index" />
                } @else {
                  <span class="deckview__mini deckview__mini--load"></span>
                }
              </button>
            }
          </aside>

          <div class="deckview__stagewrap">
            <div class="deckview__stage">
              @if (currentSlide()?.url; as url) {
                <img class="deckview__slide" [src]="url" [alt]="'Slide ' + current()" />
              } @else {
                <div class="deckview__loading"><mat-spinner diameter="32"></mat-spinner></div>
              }
            </div>
            <div class="deckview__ctrl">
              <button mat-icon-button type="button" class="deckview__prev" aria-label="Slide précédente"
                [disabled]="current() <= 1" (click)="prev()"><mat-icon>chevron_left</mat-icon></button>
              <span class="deckview__pos">{{ current() }} / {{ slideCount() }}</span>
              <button mat-icon-button type="button" class="deckview__next" aria-label="Slide suivante"
                [disabled]="current() >= (slideCount() ?? 0)" (click)="next()"><mat-icon>chevron_right</mat-icon></button>
              <span class="deckview__spacer"></span>
              <span class="deckview__keys">← → pour naviguer · Échap pour fermer</span>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .deckview {
      position: fixed;
      inset: 0;
      z-index: 1000;
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-3);
      padding: var(--cg-space-3);
      background: var(--cg-bg);
      overflow: auto;
    }

    .deckview__head {
      display: flex;
      align-items: center;
      gap: var(--cg-space-3);
    }

    .deckview__icon {
      display: grid;
      place-items: center;
      flex: none;
      width: 40px;
      height: 40px;
      border-radius: 8px;
      background: var(--cg-primary);
      color: #fff;
    }

    .deckview__titles { min-width: 0; flex: 1; }
    .deckview__title { margin: 0; font-size: 18px; overflow-wrap: anywhere; }
    .deckview__meta { margin: 2px 0 0; font-size: 12px; color: var(--cg-text-secondary); }
    .deckview__actions { display: flex; align-items: center; gap: var(--cg-space-2); flex: none; }

    .deckview__deck {
      display: grid;
      grid-template-columns: 190px 1fr;
      gap: var(--cg-space-3);
      align-items: start;
      min-height: 0;
    }

    @media (max-width: 820px) {
      .deckview__deck { grid-template-columns: 1fr; }
    }

    .deckview__rail {
      display: flex;
      flex-direction: column;
      gap: var(--cg-space-2);
      max-height: 82vh;
      overflow: auto;
      padding: var(--cg-space-2);
      background: var(--cg-surface);
      border: 1px solid var(--cg-divider);
      border-radius: 12px;
    }

    .deckview__thumb {
      display: grid;
      grid-template-columns: 22px 1fr;
      gap: var(--cg-space-2);
      align-items: center;
      padding: var(--cg-space-1);
      border: 1px solid transparent;
      border-radius: 8px;
      background: none;
      cursor: pointer;
    }

    .deckview__thumb:hover { background: var(--cg-surface-2); }
    .deckview__thumb--cur { border-color: var(--cg-accent); background: var(--cg-surface-2); }
    .deckview__n { font-size: 11px; color: var(--cg-text-secondary); text-align: center; }
    .deckview__thumb--cur .deckview__n { color: var(--cg-gold-ink); font-weight: 700; }

    .deckview__mini {
      display: block;
      width: 100%;
      aspect-ratio: 16 / 9;
      object-fit: contain;
      border: 1px solid var(--cg-divider);
      border-radius: 4px;
      background: var(--cg-bg);
    }

    .deckview__mini--load { background: var(--cg-surface-2); }

    .deckview__stagewrap { display: flex; flex-direction: column; gap: var(--cg-space-2); min-width: 0; }

    .deckview__stage {
      display: grid;
      place-items: center;
      background: var(--cg-surface);
      border: 1px solid var(--cg-divider);
      border-radius: 12px;
      overflow: hidden;
    }

    .deckview__slide {
      display: block;
      width: 100%;
      max-height: 74vh;
      object-fit: contain;
    }

    .deckview__loading { padding: var(--cg-space-7); }

    .deckview__ctrl {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-1) var(--cg-space-3);
      background: var(--cg-surface);
      border: 1px solid var(--cg-divider);
      border-radius: 10px;
    }

    .deckview__pos { font-size: 13px; color: var(--cg-text-secondary); min-width: 64px; text-align: center; }
    .deckview__spacer { flex: 1; }
    .deckview__keys { font-size: 12px; color: var(--cg-text-secondary); }

    .deckview__empty {
      display: grid;
      place-items: center;
      align-content: center;
      gap: var(--cg-space-2);
      flex: 1;
      color: var(--cg-text-secondary);
      text-align: center;
    }

    .deckview__hint { font-size: 12px; }
  `,
})
export class DeckViewerComponent {
  private readonly service = inject(PresentationService);
  private readonly files = inject(ExportService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly snackBar = inject(MatSnackBar);

  readonly presentationId = input.required<string>();
  readonly title = input<string>('Présentation');
  readonly slideCount = input<number | null>(null);

  /** Émis quand l'utilisateur ferme la visionneuse (bouton ou Échap). */
  readonly close = output<void>();

  readonly slides = signal<Slide[]>([]);
  readonly current = signal(1);
  readonly currentSlide = computed(() => this.slides().find((s) => s.index === this.current()) ?? null);

  private readonly objectUrls: string[] = [];

  constructor() {
    inject(DestroyRef).onDestroy(() => this.objectUrls.forEach((url) => URL.revokeObjectURL(url)));
    // Les entrées sont posées avant l'affichage : charger dès la construction (une seule fois).
    queueMicrotask(() => this.load());
  }

  private load(): void {
    const count = this.slideCount() ?? 0;
    const id = this.presentationId();
    this.slides.set(Array.from({ length: count }, (_, i) => ({ index: i + 1, url: null })));
    for (let index = 1; index <= count; index++) {
      const at = index;
      this.service.slide(id, at).subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          this.objectUrls.push(url);
          const safe = this.sanitizer.bypassSecurityTrustUrl(url);
          this.slides.update((list) => list.map((s) => (s.index === at ? { ...s, url: safe } : s)));
        },
        error: () => {
          // Une slide manquante ne casse pas le deck : elle reste une vignette vide.
        },
      });
    }
  }

  go(index: number): void {
    const max = this.slideCount() ?? 0;
    this.current.set(Math.min(Math.max(1, index), Math.max(1, max)));
  }

  prev(): void {
    this.go(this.current() - 1);
  }

  next(): void {
    this.go(this.current() + 1);
  }

  download(): void {
    this.service.download(this.presentationId()).subscribe({
      next: (response) => this.files.triggerDownload(response, 'presentation.pptx'),
      error: () => this.snackBar.open("La présentation n'a pas pu être téléchargée.", 'Fermer',
        { duration: 8000, panelClass: 'snack-error' }),
    });
  }

  @HostListener('document:keydown.arrowright')
  onRight(): void {
    if (this.slideCount()) {
      this.next();
    }
  }

  @HostListener('document:keydown.arrowleft')
  onLeft(): void {
    if (this.slideCount()) {
      this.prev();
    }
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.close.emit();
  }
}
