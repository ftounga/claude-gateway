import { ChangeDetectionStrategy, Component, OnDestroy, WritableSignal, computed, effect, inject, input, signal,
  untracked } from '@angular/core';

import { JarvisState } from '../jarvis-orb.component';
import { JarvisPieceClient } from './jarvis-piece-client';
import { PieceNav } from './jarvis-piece-nav';
import { JarvisPieceViewerComponent } from './jarvis-piece-viewer.component';
import { Piece } from './jarvis-piece.models';
import { curtain } from './jarvis-piece-versions';

/**
 * F-237 / SF-237-11 — **avant / après** (cadrage §14 idée 1, situation I5 « ça marche maintenant ? ») : deux pièces
 * côte à côte — la capture d'erreur collée et la preuve, ou deux versions d'une page / d'une vidéo. Deux images : un
 * **rideau glissant** (l'après recouvre l'avant jusqu'au curseur, au toucher, à la souris ou au clavier). Sinon :
 * deux visionneuses côte à côte. Contenus par la passerelle seulement (URL d'objet, révoquées).
 */

@Component({
  selector: 'app-jarvis-piece-compare',
  standalone: true,
  imports: [JarvisPieceViewerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (before() && after()) {
      <section class="cmp" aria-label="Avant / après">
        @if (images()) {
          <div class="cmp__stage">
            @if (beforeUrl(); as b) { <img class="cmp__img" [src]="b" [alt]="'Avant : ' + before()!.title"> }
            @if (afterUrl(); as a) {
              <img class="cmp__img cmp__img--after" [src]="a" [alt]="'Après : ' + after()!.title"
                   [style.clip-path]="'inset(0 0 0 ' + (100 - split()) + '%)'">
            }
            <span class="cmp__rule" [style.left.%]="100 - split()" aria-hidden="true"></span>
          </div>
          <label class="cmp__slider">
            <span class="cmp__label">Avant</span>
            <input type="range" min="0" max="100" [value]="split()" (input)="slide($event)"
                   aria-label="Rideau avant / après">
            <span class="cmp__label">Après</span>
          </label>
        } @else {
          <div class="cmp__pair">
            <div class="cmp__side">
              <h3 class="cmp__title">Avant — {{ before()!.title }}</h3>
              <app-jarvis-piece-viewer [piece]="before()" [nav]="nav()" [jarvisState]="jarvisState()" />
            </div>
            <div class="cmp__side">
              <h3 class="cmp__title">Après — {{ after()!.title }}</h3>
              <app-jarvis-piece-viewer [piece]="after()" [nav]="nav()" [jarvisState]="jarvisState()" />
            </div>
          </div>
        }
        <p class="cmp__caption">{{ before()!.caption || before()!.title }} → {{ after()!.caption || after()!.title }}</p>
      </section>
    }
  `,
  styles: [`
    :host { display: block; }
    .cmp { display: flex; flex-direction: column; gap: 8px; }
    .cmp__stage { position: relative; width: 100%; aspect-ratio: 16 / 10; overflow: hidden; border-radius: 4px;
      background: color-mix(in srgb, var(--cg-navy, #0B1020) 85%, transparent); }
    .cmp__img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain; }
    .cmp__rule { position: absolute; top: 0; bottom: 0; width: 2px; background: var(--cg-holo, #E07B39);
      box-shadow: 0 0 8px var(--cg-holo, #E07B39); }
    .cmp__slider { display: flex; align-items: center; gap: 8px; font: 12px 'JetBrains Mono', monospace; }
    .cmp__slider input { flex: 1; accent-color: var(--cg-holo, #E07B39); min-height: 44px; }
    .cmp__pair { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
    .cmp__side { min-width: 0; }
    .cmp__title { margin: 0 0 4px; font: 600 14px 'Space Grotesk', Inter, sans-serif; }
    .cmp__caption { margin: 0; font-size: 14px; line-height: 20px; color: var(--cg-holo-core, #FFF3E4); }
    @media (max-width: 767px) { .cmp__pair { grid-template-columns: 1fr; } }
  `],
})
export class JarvisPieceCompareComponent implements OnDestroy {
  private readonly client = inject(JarvisPieceClient);

  readonly before = input<Piece | null>(null);
  readonly after = input<Piece | null>(null);
  readonly nav = input.required<PieceNav>();
  readonly jarvisState = input<JarvisState>('repos');

  readonly split = signal(50);
  readonly beforeUrl = signal<string | null>(null);
  readonly afterUrl = signal<string | null>(null);
  readonly images = computed(() => this.before()?.kind === 'IMAGE' && this.after()?.kind === 'IMAGE');
  private loading: AbortController | null = null;

  private readonly load = effect(() => {
    const before = this.before();
    const after = this.after();
    const images = this.images();
    untracked(() => this.revoke());
    this.loading?.abort();
    if (!images || !before || !after) {
      return;
    }
    const controller = new AbortController();
    this.loading = controller;
    void this.client.objectUrl(before.open, controller.signal).then((url) => this.keep(url, controller, this.beforeUrl));
    void this.client.objectUrl(after.open, controller.signal).then((url) => this.keep(url, controller, this.afterUrl));
  });

  slide(event: Event): void {
    this.split.set(curtain(Number((event.target as HTMLInputElement).value)));
  }

  ngOnDestroy(): void {
    this.loading?.abort();
    this.revoke();
  }

  private keep(url: string | null, controller: AbortController, target: WritableSignal<string | null>): void {
    if (!url) {
      return;
    }
    if (controller.signal.aborted) {
      URL.revokeObjectURL(url);
      return;
    }
    target.set(url);
  }

  private revoke(): void {
    for (const url of [this.beforeUrl(), this.afterUrl()]) {
      if (url) {
        URL.revokeObjectURL(url);
      }
    }
    this.beforeUrl.set(null);
    this.afterUrl.set(null);
  }
}
