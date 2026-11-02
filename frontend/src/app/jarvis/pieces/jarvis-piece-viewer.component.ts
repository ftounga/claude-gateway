import {
  ChangeDetectionStrategy, Component, ElementRef, OnDestroy, computed, effect, inject, input, signal, untracked, viewChild,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';

import { JarvisState } from '../jarvis-orb.component';
import { PageFrameComponent } from '../../shared/pages/page-frame.component';
import { JarvisPieceClient } from './jarvis-piece-client';
import {
  DUCK_RAMP_MS, RESUME_AFTER_MS, UNDUCK_DELAY_MS, clockOf, duckTarget, pdfAt, rampSteps, shouldAutoPause, slidePath,
  videoInfoPath,
} from './jarvis-piece-media';
import { PieceNav } from './jarvis-piece-nav';
import { SvgBox, SvgTarget, findTarget, svgTargets, viewOf } from './jarvis-piece-svg';
import { JarvisPieceStore } from './jarvis-piece-store';
import { viewerFor } from './jarvis-piece-viewers';
import { Piece, PieceChapter } from './jarvis-piece.models';

/** Fiche de lecture d'une vidéo (sous-ensemble de `VideoResponse`). */
interface VideoInfo {
  mediaUrl?: string | null;
  posterUrl?: string | null;
  chapters?: { t: number; titre: string }[];
}

/** Fiche d'une page (sous-ensemble de `PageResponse`). */
interface PageInfo {
  viewUrl?: string | null;
  title?: string;
}

/**
 * F-237 / SF-237-06 — **les visionneuses** (cadrage §8.2) : le contenu **réel** de la pièce héros, une visionneuse par
 * type (registre `PIECE_VIEWERS`). Image et schéma : zoom ×1-×8 et déplacement (molette, glisser) ; vidéo / audio :
 * lecture, chapitres, son baissé à 15 % quand Jarvis parle et pause quand le PO parle ; PDF : lecteur natif à la page
 * de l'état ; page : cadre bac à sable existant ; diapos : une image par diapo ; texte : carte ; fichier : fiche. Tout
 * passe par la passerelle (JWT ou ticket existant) ; les URL d'objet sont révoquées au changement de pièce.
 */
@Component({
  selector: 'app-jarvis-piece-viewer',
  standalone: true,
  imports: [NgTemplateOutlet, PageFrameComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (piece(); as p) {
      @if (p.available === 'HOST_OFFLINE') {
        <p class="pv__note">Sur le poste — hors ligne. La pièce s'affichera quand le poste sera connecté.</p>
      } @else {
        @switch (viewer().name) {
          @case ('image') { <ng-container [ngTemplateOutlet]="zoomable" /> }
          @case ('schema-svg') {
            <ng-container [ngTemplateOutlet]="zoomable" />
            @if (nav().target && !targetView()) {
              <p class="pv__note">Je ne trouve pas « {{ nav().target }} » sur ce schéma.</p>
            }
            @if (svgZones().length) {
              <div class="pv__zones" aria-label="Zones du schéma">
                @for (zone of svgZones(); track zone.label) {
                  <button type="button" class="pv__zone" [class.pv__zone--on]="nav().target === zone.label"
                          (click)="store.dispatch({ type: 'zoom', target: zone.label })">{{ zone.label }}</button>
                }
                @if (nav().zoom > 1 || nav().target) {
                  <button type="button" class="pv__zone" (click)="store.dispatch({ type: 'zoom', step: 'reset' })">
                    Tout le schéma</button>
                }
              </div>
            }
          }
          @case ('video') {
            @if (mediaUrl()) {
              <video #media class="pv__video" [src]="mediaUrl()" [attr.poster]="posterUrl()" controls playsinline
                     (play)="onPlay()" (pause)="onPause()" (timeupdate)="onTime($event)"></video>
            } @else if (failed()) { <p class="pv__note">Vidéo illisible pour le moment.</p> }
          }
          @case ('audio') {
            @if (mediaUrl()) {
              <audio #media class="pv__audio" [src]="mediaUrl()" controls (play)="onPlay()" (pause)="onPause()"
                     (timeupdate)="onTime($event)"></audio>
            } @else if (failed()) { <p class="pv__note">Audio illisible pour le moment.</p> }
          }
          @case ('pdf') {
            @if (pdfUrl()) {
              <iframe class="pv__pdf" [src]="pdfUrl()" [title]="p.title"></iframe>
            } @else if (failed()) { <p class="pv__note">PDF illisible pour le moment.</p> }
          }
          @case ('page') {
            @if (pageUrl()) {
              <app-page-frame [url]="pageUrl()" [pageTitle]="p.title" />
            } @else if (failed()) { <p class="pv__note">Page illisible pour le moment.</p> }
          }
          @case ('diapos') {
            @if (src()) {
              <img class="pv__slide" [src]="src()" [alt]="p.title + ' — diapo ' + (nav().page ?? 1)">
            }
            <p class="pv__meta">Diapo {{ nav().page ?? 1 }}{{ total() ? ' / ' + total() : '' }}</p>
          }
          @case ('texte') {
            <blockquote class="pv__text">{{ text() ?? p.caption ?? p.title }}</blockquote>
          }
          @default {
            <dl class="pv__fiche">
              <dt>Nom</dt><dd>{{ p.meta?.['name'] ?? p.title }}</dd>
              @if (p.meta?.sizeBytes) { <dt>Taille</dt><dd>{{ size(p.meta!.sizeBytes!) }}</dd> }
              @if (p.at) { <dt>Date</dt><dd>{{ p.at.slice(0, 16).replace('T', ' ') }}</dd> }
            </dl>
            <p class="pv__note">Pas d'aperçu pour ce type de fichier.</p>
          }
        }
        @if (chapters().length) {
          <ol class="pv__chapters" aria-label="Chapitres">
            @for (c of chapters(); track $index) {
              <li><button type="button" [class.pv__chapter--on]="nav().chapter === $index + 1"
                          (click)="goChapter($index + 1, c.t)">
                {{ clock(c.t) }} — {{ c.title }}</button></li>
            }
          </ol>
        }
      }
    }
    <ng-template #zoomable>
      <div class="pv__zoom" (wheel)="onWheel($event)" (pointerdown)="dragStart($event)" (pointermove)="dragMove($event)"
           (pointerup)="dragEnd()" (pointerleave)="dragEnd()" (dblclick)="store.dispatch({ type: 'zoom', step: 'reset' })">
        @if (src()) {
          <img class="pv__image" [src]="src()" [alt]="piece()?.title ?? ''" draggable="false"
               [style.transform]="transform()">
        } @else if (failed()) { <p class="pv__note">Image illisible pour le moment.</p> }
      </div>
      @if (nav().zoom > 1) { <p class="pv__meta">Zoom ×{{ nav().zoom }}</p> }
    </ng-template>
  `,
  styles: [`
    :host { display: block; }
    .pv__zoom { overflow: hidden; display: grid; place-items: center; max-height: 48vh; touch-action: none;
      cursor: grab; }
    .pv__image, .pv__slide { max-width: 100%; max-height: 48vh; transition: transform 350ms cubic-bezier(.16,1,.3,1); }
    .pv__video { width: 100%; max-height: 48vh; background: var(--cg-navy, #0B1020); }
    .pv__audio { width: 100%; }
    .pv__pdf { width: 100%; height: 52vh; border: 0; background: var(--cg-holo-core, #FFF3E4); }
    .pv__text { margin: 0; padding: 12px 16px; white-space: pre-wrap; font-size: 14px; line-height: 20px;
      border-left: 2px solid var(--cg-holo, #E07B39); color: var(--cg-holo-core, #FFF3E4); }
    .pv__fiche { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; margin: 0; font-size: 13px; }
    .pv__fiche dt { font-family: 'Space Grotesk', Inter, sans-serif; text-transform: uppercase; letter-spacing: .08em; }
    .pv__note, .pv__meta { margin: 8px 0 0; font-size: 12px; opacity: .8; }
    .pv__chapters { margin: 8px 0 0; padding-left: 20px; font: 12px 'JetBrains Mono', monospace; }
    .pv__chapters button { background: none; border: 0; color: inherit; font: inherit; cursor: pointer;
      min-height: 32px; text-align: left; }
    .pv__chapter--on { color: var(--cg-holo-green, #4CF2A0); }
    .pv__zones { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
    .pv__zone { min-height: 32px; padding: 0 12px; border-radius: 4px; cursor: pointer; color: inherit;
      font: 12px Inter, sans-serif; background: transparent;
      border: 1px solid color-mix(in srgb, var(--cg-holo, #E07B39) 55%, transparent); }
    .pv__zone--on { border-color: var(--cg-holo-green, #4CF2A0); }
    @media (prefers-reduced-motion: reduce) { .pv__image, .pv__slide { transition: none; } }
  `],
})
export class JarvisPieceViewerComponent implements OnDestroy {
  readonly store = inject(JarvisPieceStore);
  private readonly client = inject(JarvisPieceClient);
  private readonly sanitizer = inject(DomSanitizer);

  readonly piece = input<Piece | null>(null);
  readonly nav = input.required<PieceNav>();
  /** L'état de Jarvis : `parole` baisse le son, `ecoute` met en pause (D10). */
  readonly jarvisState = input<JarvisState>('repos');

  private readonly media = viewChild<ElementRef<HTMLMediaElement>>('media');

  readonly viewer = computed(() => viewerFor(this.piece()?.kind));
  readonly src = signal<string | null>(null);
  readonly mediaUrl = signal<string | null>(null);
  readonly posterUrl = signal<string | null>(null);
  readonly pageUrl = signal<string | null>(null);
  readonly pdfObject = signal<string | null>(null);
  readonly text = signal<string | null>(null);
  readonly failed = signal(false);
  private readonly videoChapters = signal<PieceChapter[]>([]);
  readonly chapters = computed(() => {
    const fromVideo = this.videoChapters();
    return fromVideo.length ? fromVideo : this.piece()?.meta?.chapters ?? [];
  });
  readonly total = computed(() => this.piece()?.meta?.slides ?? this.piece()?.meta?.pages ?? null);
  readonly pdfUrl = computed<SafeResourceUrl | null>(() => {
    const url = this.pdfObject();
    // URL d'objet locale (blob:) lue sous JWT par la passerelle : sûre à intégrer.
    return url ? this.sanitizer.bypassSecurityTrustResourceUrl(pdfAt(url, this.nav().page)) : null;
  });
  /** SF-237-07 — le SVG d'un schéma, analysé (jamais inséré) : son cadre et ses cibles nommées. */
  private readonly svg = signal<{ frame: SvgBox | null; targets: SvgTarget[] }>({ frame: null, targets: [] });
  /** Les groupes de premier plan proposés au toucher (≤ 8). */
  readonly svgZones = computed(() => this.svg().targets.slice(0, 8));
  /** Le cadrage de la cible nommée de l'état, s'il la trouve. */
  readonly targetView = computed(() => {
    const target = this.nav().target;
    const { frame, targets } = this.svg();
    if (!target || !frame) {
      return null;
    }
    const found = findTarget(targets, target);
    return found ? viewOf(frame, found.box) : null;
  });
  readonly transform = computed(() => {
    const nav = this.nav();
    const view = this.targetView();
    if (view) {
      return `translate(${nav.pan.x}px, ${nav.pan.y}px) scale(${Math.max(view.zoom, 1)}) translate(${view.tx}%, ${view.ty}%)`;
    }
    return `translate(${nav.pan.x}px, ${nav.pan.y}px) scale(${nav.zoom})`;
  });

  private loading: AbortController | null = null;
  private objectUrls: string[] = [];
  private drag: { x: number; y: number } | null = null;
  private rampTimer: ReturnType<typeof setInterval> | null = null;
  private unduckTimer: ReturnType<typeof setTimeout> | null = null;
  private resumeTimer: ReturnType<typeof setTimeout> | null = null;
  private autoPaused = false;

  /** Charge le contenu à chaque changement de pièce (annulable), jamais à un simple changement d'état. */
  private readonly pieceKey = computed(() => this.piece()?.id ?? null);
  private readonly loadPiece = effect(() => {
    this.pieceKey();
    untracked(() => void this.load());
  });

  /** Diapos : la diapo de l'état. */
  private readonly loadSlide = effect(() => {
    const page = this.nav().page;
    const piece = untracked(() => this.piece());
    if (piece && viewerFor(piece.kind).name === 'diapos') {
      untracked(() => void this.loadImage(slidePath(piece.id, page ?? 1)));
    }
  });

  /** Lecture / pause / aller à : l'état pilote l'élément média. */
  private readonly syncMedia = effect(() => {
    const nav = this.nav();
    const el = this.media()?.nativeElement;
    if (!el) {
      return;
    }
    if (nav.time != null && Math.abs(el.currentTime - nav.time) > 1 && nav.level === 'INSTANT') {
      el.currentTime = nav.time;
    }
    if (nav.playing && el.paused) {
      void el.play().catch(() => undefined);
    } else if (!nav.playing && !el.paused) {
      el.pause();
    }
  });

  /** D10 — la voix de Jarvis baisse la pièce ; la parole du PO la met en pause. */
  private readonly duck = effect(() => {
    const state = this.jarvisState();
    const el = this.media()?.nativeElement;
    if (!el) {
      return;
    }
    untracked(() => this.applyVoice(el, state));
  });

  private async load(): Promise<void> {
    this.loading?.abort();
    this.revoke();
    this.src.set(null);
    this.mediaUrl.set(null);
    this.posterUrl.set(null);
    this.pageUrl.set(null);
    this.pdfObject.set(null);
    this.text.set(null);
    this.failed.set(false);
    this.videoChapters.set([]);
    this.svg.set({ frame: null, targets: [] });
    const piece = this.piece();
    if (!piece || piece.available === 'HOST_OFFLINE') {
      return;
    }
    const controller = new AbortController();
    this.loading = controller;
    switch (viewerFor(piece.kind).name) {
      case 'image':
        await this.loadImage(piece.open ?? piece.thumb ?? null, controller);
        break;
      case 'schema-svg': {
        await this.loadImage(piece.open ?? piece.thumb ?? null, controller);
        const text = await this.client.text(piece.open, controller.signal);
        if (!controller.signal.aborted && text && text.includes('<svg')) {
          this.svg.set(svgTargets(text));
        }
        break;
      }
      case 'video':
      case 'audio': {
        const info = await this.client.json<VideoInfo>(videoInfoPath(piece.id), controller.signal);
        if (controller.signal.aborted) {
          return;
        }
        if (info?.mediaUrl) {
          this.mediaUrl.set(info.mediaUrl);
          this.posterUrl.set(info.posterUrl ?? null);
          this.videoChapters.set((info.chapters ?? []).map((c) => ({ t: c.t, title: c.titre })));
        } else if (piece.open?.startsWith('/workspaces/')) {
          await this.loadInto(this.mediaUrl, piece.open, controller); // vidéo ou audio déposé : l'original
        } else {
          this.failed.set(true);
        }
        break;
      }
      case 'pdf':
        await this.loadInto(this.pdfObject, piece.open ?? null, controller);
        break;
      case 'page': {
        const info = await this.client.json<PageInfo>(piece.open, controller.signal);
        if (!controller.signal.aborted) {
          this.pageUrl.set(info?.viewUrl ?? null);
          this.failed.set(!info?.viewUrl);
        }
        break;
      }
      case 'diapos':
        await this.loadImage(slidePath(piece.id, this.nav().page ?? 1), controller);
        break;
      case 'texte':
        if (piece.id.startsWith('mail:')) {
          const body = await this.client.text(piece.open, controller.signal);
          if (!controller.signal.aborted) {
            this.text.set(body);
          }
        }
        break;
      default:
        break;
    }
  }

  private async loadImage(path: string | null, controller = this.loading ?? new AbortController()): Promise<void> {
    await this.loadInto(this.src, path, controller);
  }

  private async loadInto(target: { set(v: string | null): void }, path: string | null,
    controller: AbortController): Promise<void> {
    const url = await this.client.objectUrl(path, controller.signal);
    if (!url) {
      if (!controller.signal.aborted) {
        this.failed.set(true);
      }
      return;
    }
    if (controller.signal.aborted) {
      URL.revokeObjectURL(url);
      return;
    }
    this.objectUrls.push(url);
    target.set(url);
  }

  private applyVoice(el: HTMLMediaElement, state: JarvisState): void {
    if (this.unduckTimer) {
      clearTimeout(this.unduckTimer);
      this.unduckTimer = null;
    }
    if (shouldAutoPause(state)) {
      if (!el.paused) {
        this.autoPaused = true;
        el.pause();
        this.resumeTimer = setTimeout(() => {
          // aucun ordre n'est venu : la pièce reprend (parole non confirmée)
          if (this.autoPaused && this.nav().playing) {
            void el.play().catch(() => undefined);
          }
          this.autoPaused = false;
        }, RESUME_AFTER_MS);
      }
      return;
    }
    const target = duckTarget(state);
    if (target < 1) {
      this.ramp(el, target, DUCK_RAMP_MS);
    } else {
      this.unduckTimer = setTimeout(() => this.ramp(el, 1, DUCK_RAMP_MS), UNDUCK_DELAY_MS);
    }
  }

  private ramp(el: HTMLMediaElement, to: number, ms: number): void {
    if (this.rampTimer) {
      clearInterval(this.rampTimer);
    }
    const steps = rampSteps(el.volume, to, ms);
    let i = 0;
    this.rampTimer = setInterval(() => {
      el.volume = Math.min(1, Math.max(0, steps[i++]));
      if (i >= steps.length && this.rampTimer) {
        clearInterval(this.rampTimer);
        this.rampTimer = null;
      }
    }, 20);
  }

  /** F-237 / SF-237-12 — l'instant de lecture, pour horodater un retour dicté. */
  onTime(event: Event): void {
    const t = (event.target as HTMLMediaElement | null)?.currentTime;
    if (typeof t === 'number' && Number.isFinite(t)) {
      this.store.mediaTime.set(t);
    }
  }

  onPlay(): void {
    this.autoPaused = false;
    if (!this.nav().playing) {
      this.store.dispatch({ type: 'play' });
    }
  }

  onPause(): void {
    if (this.nav().playing && !this.autoPaused) {
      this.store.dispatch({ type: 'pause' });
    }
  }

  onWheel(event: WheelEvent): void {
    event.preventDefault();
    this.store.dispatch({ type: 'zoom', step: event.deltaY < 0 ? '+' : '-' });
  }

  dragStart(event: PointerEvent): void {
    this.drag = this.nav().zoom > 1 ? { x: event.clientX, y: event.clientY } : null;
  }

  dragMove(event: PointerEvent): void {
    if (!this.drag) {
      return;
    }
    const dx = event.clientX - this.drag.x;
    const dy = event.clientY - this.drag.y;
    if (Math.abs(dx) + Math.abs(dy) < 4) {
      return;
    }
    this.drag = { x: event.clientX, y: event.clientY };
    this.store.dispatch({ type: 'pan', dx, dy });
  }

  dragEnd(): void {
    this.drag = null;
  }

  /** Un chapitre touché : par son rang si l'index le connaît, sinon par son temps. */
  goChapter(rank: number, t: number): void {
    const known = (this.piece()?.meta?.chapters ?? []).length >= rank;
    this.store.dispatch(known ? { type: 'seek', chapter: rank } : { type: 'seek', t });
  }

  clock(seconds: number): string {
    return clockOf(seconds);
  }

  size(bytes: number): string {
    return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} Mo` : `${Math.max(1, Math.round(bytes / 1024))} Ko`;
  }

  ngOnDestroy(): void {
    this.loading?.abort();
    this.revoke();
    for (const timer of [this.unduckTimer, this.resumeTimer]) {
      if (timer) {
        clearTimeout(timer);
      }
    }
    if (this.rampTimer) {
      clearInterval(this.rampTimer);
    }
  }

  private revoke(): void {
    for (const url of this.objectUrls) {
      URL.revokeObjectURL(url);
    }
    this.objectUrls = [];
  }
}
