import { Component, DestroyRef, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { AtelierService } from '../core/services/atelier.service';
import { VideoGalleryComponent } from '../shared/videos/video-gallery.component';

/**
 * **L'écran Vidéos d'un sujet** (F-213 / SF-213-03) : `/atelier/:id/videos`, `?video=` ouvre une vidéo. Ouvert
 * dans un nouvel onglet depuis le terminal (bouton *Vidéos*, *Ouvrir dans la galerie*) : le tour vit dans le flux
 * du terminal, le quitter le tuerait.
 */
@Component({
  selector: 'app-video-gallery-page',
  imports: [VideoGalleryComponent],
  template: `
    <section class="videos-page">
      <header class="videos-page__bar">
        <h1 class="videos-page__title">Vidéos</h1>
        @if (subjectName(); as name) {
          <span class="videos-page__subject">{{ name }}</span>
        }
      </header>
      <app-video-gallery [workspaceId]="workspaceId()" [initialVideoId]="videoId()"></app-video-gallery>
    </section>
  `,
  styles: `
    .videos-page {
      max-width: 1200px;
      margin: 0 auto;
      padding: var(--cg-space-4) var(--cg-space-3);
      color: var(--cg-text-primary);
    }

    .videos-page__bar {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: var(--cg-space-2);
      margin-bottom: var(--cg-space-4);
    }

    .videos-page__title {
      margin: 0;
      font-family: var(--cg-font-heading);
      font-size: 32px;
      font-weight: 700;
    }

    .videos-page__subject {
      font-size: 14px;
      color: var(--cg-text-secondary);
    }
  `,
})
export class VideoGalleryPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly atelier = inject(AtelierService);

  readonly workspaceId = signal<string | null>(null);
  readonly videoId = signal<string | null>(null);
  readonly subjectName = signal<string | null>(null);

  constructor() {
    const params = this.route.paramMap.subscribe((map) => {
      const id = map.get('id');
      this.workspaceId.set(id);
      this.subjectName.set(null);
      if (id) {
        this.atelier.getWorkspace(id).subscribe({
          next: (workspace) => this.subjectName.set(workspace.name ?? null),
          error: () => this.subjectName.set(null),
        });
      }
    });
    const query = this.route.queryParamMap.subscribe((map) => this.videoId.set(map.get('video')));
    inject(DestroyRef).onDestroy(() => {
      params.unsubscribe();
      query.unsubscribe();
    });
  }
}
