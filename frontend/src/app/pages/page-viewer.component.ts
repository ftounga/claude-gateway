import { Component, DestroyRef, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';

import { PageSummary } from '../core/models/pages.models';
import { PagesService } from '../core/services/pages.service';
import { PageFrameComponent } from '../shared/pages/page-frame.component';

/**
 * **Une page en plein écran** (F-109 / SF-109-03) — `/pages/:id`, sous la coquille authentifiée. Ouverte dans
 * un nouvel onglet depuis le terminal : le bandeau dit ce qu'on regarde, l'`iframe` prend le reste.
 */
@Component({
  selector: 'app-page-viewer',
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule, PageFrameComponent],
  template: `
    <section class="page-viewer">
      @if (summary(); as page) {
        <header class="page-viewer__bar">
          <mat-icon aria-hidden="true">web</mat-icon>
          <h1 class="page-viewer__title">{{ page.title }}</h1>
          <span class="page-viewer__meta">Version {{ page.currentVersion }} · privée</span>
        </header>
        <app-page-frame class="page-viewer__frame" [url]="page.viewUrl" [pageTitle]="page.title"></app-page-frame>
      } @else if (notFound()) {
        <div class="page-viewer__missing">
          <h1>Cette page est introuvable</h1>
          <p>Elle a peut-être été supprimée, ou elle appartient à un autre compte.</p>
        </div>
      } @else {
        <div class="page-viewer__loading"><mat-spinner diameter="28"></mat-spinner></div>
      }
    </section>
  `,
  styles: `
    .page-viewer {
      display: flex;
      flex-direction: column;
      height: calc(100vh - 64px);
    }

    .page-viewer__bar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-2) var(--cg-space-4);
      background: var(--cg-surface);
      border-bottom: 1px solid var(--cg-divider);

      mat-icon {
        color: var(--cg-text-secondary);
      }
    }

    .page-viewer__title {
      margin: 0;
      font-size: 20px;
      font-weight: 600;
    }

    .page-viewer__meta {
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .page-viewer__frame {
      flex: 1;
      min-height: 0;
    }

    .page-viewer__missing,
    .page-viewer__loading {
      padding: var(--cg-space-4);
    }

    .page-viewer__missing p {
      color: var(--cg-text-secondary);
    }
  `,
})
export class PageViewerComponent {
  private readonly pages = inject(PagesService);

  readonly summary = signal<PageSummary | null>(null);
  readonly notFound = signal(false);

  private loading: Subscription | null = null;

  constructor() {
    const route = inject(ActivatedRoute);
    const destroyRef = inject(DestroyRef);
    const params = route.paramMap.subscribe((map) => this.load(map.get('id') ?? ''));
    destroyRef.onDestroy(() => {
      params.unsubscribe();
      this.loading?.unsubscribe();
    });
  }

  private load(pageId: string): void {
    this.loading?.unsubscribe();
    this.summary.set(null);
    this.notFound.set(false);
    this.loading = this.pages.get(pageId).subscribe({
      next: (summary) => this.summary.set(summary),
      error: () => this.notFound.set(true),
    });
  }
}
