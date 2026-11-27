import { Component, computed, effect, ElementRef, inject, input, signal, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';
import { firstValueFrom } from 'rxjs';

import { AtelierGalleryBlock, AtelierGalleryItem, AtelierTerminalBlock } from '../../core/models/atelier.models';

/** Le bloc de transcription d'une galerie reçue au fil de l'eau (F-278) : rangé comme une carte. */
export function galleryBlock(toolUseId: string, gallery: AtelierGalleryBlock): AtelierTerminalBlock {
  return {
    tool: 'galerie_images',
    toolUseId,
    threadId: null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
    gallery,
  };
}

/** URL de l'aperçu d'une image, servi au propriétaire du terminal. */
export function galleryImageUrl(workspaceId: string | null, imageId: string): string {
  return `/api/workspaces/${workspaceId ?? ''}/gallery/${encodeURIComponent(imageId)}`;
}

/**
 * La date d'une image, lisible : « 27/11/2026 09:30 » pour une date ISO (dépôt, nom du fichier), le texte de l'agent
 * tel quel sinon, « date inconnue » à défaut.
 */
export function galleryDate(date: string | null | undefined): string {
  if (!date) {
    return 'date inconnue';
  }
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(date)) {
    const parsed = new Date(date);
    if (!Number.isNaN(parsed.getTime())) {
      const pad = (value: number) => String(value).padStart(2, '0');
      return `${pad(parsed.getDate())}/${pad(parsed.getMonth() + 1)}/${parsed.getFullYear()} `
        + `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}`;
    }
  }
  return date;
}

/** Un JPEG converti en PNG (le presse-papiers n'accepte que le PNG). */
async function toPng(blob: Blob): Promise<Blob> {
  const bitmap = await createImageBitmap(blob);
  const canvas = document.createElement('canvas');
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  canvas.getContext('2d')?.drawImage(bitmap, 0, 0);
  return new Promise((resolve, reject) =>
    canvas.toBlob((png) => (png ? resolve(png) : reject(new Error('conversion impossible'))), 'image/png'));
}

/**
 * **La galerie d'images du poste** (F-278 / SF-278-02) : une grande image avec son nom, sa date et la légende de
 * l'agent ; précédent / suivant (boutons, flèches du clavier), compteur, bande de vignettes cliquables, Agrandir
 * (Échap pour sortir), Copier l'image, Télécharger. Les aperçus viennent de la route isolée du propriétaire.
 */
@Component({
  selector: 'app-gallery-block',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <section #root class="gallery" [class.gallery--expanded]="expanded()" tabindex="0"
             [attr.aria-label]="'Galerie : ' + gallery().title" (keydown)="onKey($event)">
      <p class="gallery__headline">
        <mat-icon aria-hidden="true">photo_library</mat-icon>
        <span>{{ headline() }}</span>
      </p>
      @if (current(); as item) {
        <div class="gallery__stage">
          <button mat-icon-button type="button" class="gallery__prev" (click)="previous()" [disabled]="index() === 0"
                  aria-label="Image précédente">
            <mat-icon aria-hidden="true">chevron_left</mat-icon>
          </button>
          <figure class="gallery__figure">
            @if (!broken().has(item.id)) {
              <button type="button" class="gallery__zoom" (click)="toggleExpanded()"
                      [attr.aria-label]="expanded() ? 'Réduire la galerie' : 'Agrandir l’image'">
                <img class="gallery__image" [src]="imageUrl(item)" [alt]="item.caption || item.name"
                     [attr.width]="item.width" [attr.height]="item.height" (error)="markBroken(item.id)" />
              </button>
            } @else {
              <p class="gallery__missing">Image indisponible.</p>
            }
            <figcaption class="gallery__caption">
              <span class="gallery__name">{{ item.name }}</span>
              <span class="gallery__facts">{{ date(item) }} · {{ index() + 1 }} / {{ count() }}</span>
              @if (item.caption) {
                <span class="gallery__legend">{{ item.caption }}</span>
              }
            </figcaption>
          </figure>
          <button mat-icon-button type="button" class="gallery__next" (click)="next()"
                  [disabled]="index() >= count() - 1" aria-label="Image suivante">
            <mat-icon aria-hidden="true">chevron_right</mat-icon>
          </button>
        </div>
        <div class="gallery__actions">
          <button mat-button type="button" class="gallery__expand" (click)="toggleExpanded()">
            <mat-icon aria-hidden="true">{{ expanded() ? 'close_fullscreen' : 'open_in_full' }}</mat-icon>
            {{ expanded() ? 'Réduire' : 'Agrandir' }}
          </button>
          <button mat-button type="button" class="gallery__copy" (click)="copyImage(item)">
            <mat-icon aria-hidden="true">content_copy</mat-icon>Copier l’image
          </button>
          <button mat-button type="button" class="gallery__download" (click)="download(item)">
            <mat-icon aria-hidden="true">download</mat-icon>Télécharger
          </button>
        </div>
      }
      @if (count() > 1) {
        <ol #strip class="gallery__strip" aria-label="Vignettes">
          @for (item of gallery().items; track item.id; let n = $index) {
            <li>
              <button type="button" class="gallery__thumb" [class.gallery__thumb--current]="n === index()"
                      [attr.aria-current]="n === index() ? 'true' : null" (click)="select(n)"
                      [attr.aria-label]="'Image ' + (n + 1) + ' : ' + item.name">
                <img [src]="imageUrl(item)" alt="" loading="lazy" />
              </button>
            </li>
          }
        </ol>
      }
      @if (skipped().length > 0) {
        <details class="gallery__skipped">
          <summary>{{ skippedLabel() }}</summary>
          <ul>
            @for (entry of skipped(); track $index) {
              <li><code>{{ entry.path }}</code> — {{ entry.reason }}</li>
            }
          </ul>
        </details>
      }
    </section>
  `,
  styles: `
    .gallery {
      margin: var(--cg-space-2) 0;
      padding: var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-divider);
      border-left: 4px solid var(--cg-orange-2);
      border-radius: 8px;
      font-family: var(--cg-font-body);
      color: inherit;
      outline: none;
      min-width: 0;
    }

    .gallery:focus-visible {
      box-shadow: 0 0 0 2px var(--cg-orange-2);
    }

    .gallery--expanded {
      position: fixed;
      inset: 0;
      z-index: 1000;
      margin: 0;
      border-radius: 0;
      display: flex;
      flex-direction: column;
      background: var(--cg-surface);
      overflow: auto;
    }

    .gallery__headline {
      display: flex;
      align-items: center;
      gap: var(--cg-space-1);
      margin: 0 0 var(--cg-space-2);
      font-weight: 600;
      color: var(--cg-orange-2);
      overflow-wrap: anywhere;
    }

    .gallery__stage {
      display: flex;
      align-items: center;
      gap: var(--cg-space-1);
      min-width: 0;
    }

    .gallery--expanded .gallery__stage {
      flex: 1;
    }

    .gallery__figure {
      flex: 1;
      min-width: 0;
      margin: 0;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: var(--cg-space-1);
    }

    .gallery__zoom {
      display: block;
      padding: 0;
      border: 0;
      background: none;
      cursor: zoom-in;
      max-width: 100%;
    }

    .gallery--expanded .gallery__zoom {
      cursor: zoom-out;
    }

    .gallery__image {
      display: block;
      max-width: 100%;
      max-height: 480px;
      width: auto;
      height: auto;
      object-fit: contain;
      border-radius: 6px;
    }

    .gallery--expanded .gallery__image {
      max-height: calc(100vh - 240px);
    }

    .gallery__caption {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: var(--cg-space-1);
      text-align: center;
      max-width: 100%;
    }

    .gallery__name {
      font-family: var(--cg-font-mono);
      font-size: 13px;
      overflow-wrap: anywhere;
    }

    .gallery__facts,
    .gallery__missing,
    .gallery__skipped {
      color: var(--cg-text-secondary);
      font-size: 12px;
    }

    .gallery__legend {
      font-size: 14px;
      overflow-wrap: anywhere;
    }

    .gallery__actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: var(--cg-space-1);
      margin-top: var(--cg-space-1);
    }

    .gallery__strip {
      display: flex;
      gap: var(--cg-space-1);
      margin: var(--cg-space-2) 0 0;
      padding: 0 0 var(--cg-space-1);
      list-style: none;
      overflow-x: auto;
      scroll-snap-type: x proximity;
    }

    .gallery__strip li {
      flex: 0 0 auto;
      scroll-snap-align: center;
    }

    .gallery__thumb {
      display: block;
      padding: 0;
      border: 2px solid transparent;
      border-radius: 4px;
      background: none;
      cursor: pointer;
      opacity: 0.7;
    }

    .gallery__thumb--current {
      border-color: var(--cg-orange-2);
      opacity: 1;
    }

    .gallery__thumb img {
      display: block;
      width: 96px;
      height: 64px;
      object-fit: cover;
      border-radius: 2px;
    }

    .gallery__skipped {
      margin-top: var(--cg-space-2);
    }

    .gallery__skipped ul {
      margin: var(--cg-space-1) 0 0;
      padding-left: var(--cg-space-4);
    }

    .gallery__skipped code {
      font-family: var(--cg-font-mono);
      overflow-wrap: anywhere;
    }

    @media (max-width: 600px) {
      .gallery__image {
        max-height: 320px;
      }

      .gallery__thumb img {
        width: 72px;
        height: 48px;
      }
    }
  `,
})
export class GalleryBlockComponent {
  private readonly http = inject(HttpClient);
  private readonly snackBar = inject(MatSnackBar);
  private readonly strip = viewChild<ElementRef<HTMLElement>>('strip');

  /** Les images et leurs métadonnées. */
  readonly gallery = input.required<AtelierGalleryBlock>();

  /** Terminal propriétaire des images. */
  readonly workspaceId = input<string | null>(null);

  readonly index = signal(0);
  readonly expanded = signal(false);
  readonly broken = signal<ReadonlySet<string>>(new Set());

  readonly count = computed(() => this.gallery().items.length);
  readonly current = computed<AtelierGalleryItem | null>(() => this.gallery().items[this.index()] ?? null);
  readonly skipped = computed(() => this.gallery().skipped ?? []);

  readonly headline = computed(() => {
    const n = this.count();
    return `Galerie · ${this.gallery().title} · ${n} ${n > 1 ? 'images' : 'image'}`;
  });

  readonly skippedLabel = computed(() => {
    const n = this.skipped().length;
    return `${n} ${n > 1 ? 'images écartées' : 'image écartée'}`;
  });

  constructor() {
    // La vignette courante reste visible dans la bande quand on défile.
    effect(() => {
      const n = this.index();
      const strip = this.strip()?.nativeElement;
      const thumb = strip?.querySelectorAll('li')[n] as HTMLElement | undefined;
      if (strip && thumb) {
        strip.scrollTo({ left: thumb.offsetLeft - strip.clientWidth / 2 + thumb.clientWidth / 2, behavior: 'smooth' });
      }
    });
  }

  imageUrl(item: AtelierGalleryItem): string {
    return galleryImageUrl(this.workspaceId(), item.id);
  }

  date(item: AtelierGalleryItem): string {
    return galleryDate(item.date);
  }

  select(n: number): void {
    this.index.set(Math.max(0, Math.min(this.count() - 1, n)));
  }

  previous(): void {
    this.select(this.index() - 1);
  }

  next(): void {
    this.select(this.index() + 1);
  }

  toggleExpanded(): void {
    this.expanded.update((value) => !value);
  }

  markBroken(id: string): void {
    this.broken.update((set) => new Set([...set, id]));
  }

  /** ← / → défilent, Début / Fin vont aux bouts, Échap réduit. */
  onKey(event: KeyboardEvent): void {
    const target = event.target as HTMLElement | null;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) {
      return;
    }
    switch (event.key) {
      case 'ArrowLeft':
        this.previous();
        break;
      case 'ArrowRight':
        this.next();
        break;
      case 'Home':
        this.select(0);
        break;
      case 'End':
        this.select(this.count() - 1);
        break;
      case 'Escape':
        if (!this.expanded()) {
          return;
        }
        this.expanded.set(false);
        break;
      default:
        return;
    }
    event.preventDefault();
    event.stopPropagation();
  }

  private fetchImage(item: AtelierGalleryItem): Promise<Blob> {
    return firstValueFrom(this.http.get(this.imageUrl(item), { responseType: 'blob' }));
  }

  /** « Copier l’image » : presse-papiers image (PNG) ; repli « Télécharger » si non pris en charge. */
  copyImage(item: AtelierGalleryItem): void {
    const clipboard = navigator.clipboard;
    if (!clipboard || typeof clipboard.write !== 'function' || typeof ClipboardItem === 'undefined') {
      this.snackBar.open('Copie d’image impossible ici : l’image est téléchargée.', 'Fermer', { duration: 3000 });
      this.download(item);
      return;
    }
    // La promesse est passée telle quelle à ClipboardItem : le geste de l’utilisateur reste valable pendant le
    // chargement (Safari l’exige).
    const png = this.fetchImage(item).then((blob) => toPng(blob));
    clipboard.write([new ClipboardItem({ 'image/png': png })]).then(
      () => this.snackBar.open('Image copiée : collez-la dans Teams ou un mail.', 'Fermer', { duration: 2500 }),
      () => {
        this.snackBar.open('Copie d’image refusée par le navigateur : l’image est téléchargée.', 'Fermer', { duration: 3000 });
        this.download(item);
      },
    );
  }

  /** « Télécharger » : l’aperçu, nommé d’après le fichier d’origine. */
  download(item: AtelierGalleryItem): void {
    this.fetchImage(item).then(
      (blob) => {
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = item.name.replace(/\.[^.]+$/, '') + '.jpg';
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 0);
      },
      () => this.snackBar.open('Image indisponible.', 'Fermer', { duration: 3000 }),
    );
  }
}
