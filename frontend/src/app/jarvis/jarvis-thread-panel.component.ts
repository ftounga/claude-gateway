import { Component, OnInit, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

import { AtelierMessageLink } from '../core/models/atelier.models';
import { JarvisThreadItem, JarvisThreadService } from '../core/services/jarvis-thread.service';

/** Qui parle, sur une ligne du fil : la parole, Jarvis (cerveau rapide) ou Opus. */
export function speakerOf(item: JarvisThreadItem): string {
  if (item.role === 'USER') {
    return 'Vous';
  }
  return item.fast ? 'Jarvis' : 'Opus';
}

/** L'adresse d'une ligne-lien (F-245 / SF-245-03) : le sujet, `?chantier=` et `?message=` s'ils sont connus. */
export function linkQueryOf(link: AtelierMessageLink): Record<string, string> {
  const query: Record<string, string> = {};
  if (link.chantier !== null && link.chantier !== undefined) {
    query['chantier'] = String(link.chantier);
  }
  if (link.messageId) {
    query['message'] = link.messageId;
  }
  return query;
}

/**
 * **Le volet « Fil du jour »** (F-245 / SF-245-04, D8) : les échanges du jour du fil Jarvis et ses lignes-liens, dans
 * l'ordre ; un toucher sur une ligne-lien ouvre le chantier à la réponse. Relu à l'ouverture. Volet du design system
 * §22 : aucune couleur nouvelle. Jamais monté en présentation (le parent ne le monte pas).
 */
@Component({
  selector: 'app-jarvis-thread-panel',
  imports: [MatButtonModule, MatIconModule, RouterLink],
  template: `
    <section class="jt" aria-label="Fil du jour">
      <header class="jt__head">
        <h2 class="jt__title">Fil du jour</h2>
        <button mat-icon-button type="button" class="jt__close" aria-label="Fermer" (click)="closed.emit()">
          <mat-icon aria-hidden="true">close</mat-icon>
        </button>
      </header>
      @if (failed()) {
        <p class="jt__note" role="alert">Fil illisible pour l’instant.</p>
      }
      @if (!failed() && items(); as list) {
        @if (list.length === 0) {
          <p class="jt__note">Rien encore aujourd’hui.</p>
        } @else {
          <ol class="jt__list">
            @for (item of list; track item.id) {
              <li class="jt__item">
                <span class="jt__time">{{ time(item.at) }}</span>
                @if (item.link; as link) {
                  <a class="jt__link" [attr.data-status]="link.status" [routerLink]="['/atelier', link.workspaceId]"
                     [queryParams]="query(link)" (click)="closed.emit()">{{ oneLine(item.text) }}</a>
                } @else {
                  <span class="jt__who">{{ speaker(item) }}</span>
                  <span class="jt__text">{{ item.text }}</span>
                }
              </li>
            }
          </ol>
        }
      }
    </section>
  `,
  styles: [`
    :host { display: block; }
    .jt {
      position: fixed; z-index: 4; top: calc(var(--cg-space-2) + 52px); right: var(--cg-space-4);
      width: min(440px, calc(100vw - 2 * var(--cg-space-2))); max-height: calc(100dvh - 120px); overflow: auto;
      box-sizing: border-box; padding: var(--cg-space-4); display: flex; flex-direction: column; gap: var(--cg-space-2);
      background: color-mix(in srgb, var(--cg-navy-2) 70%, transparent); color: var(--cg-cream);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 8px;
      backdrop-filter: blur(10px); font-family: var(--cg-font-body); overflow-wrap: anywhere;
    }
    @media (max-width: 767px) { .jt { right: var(--cg-space-2); left: var(--cg-space-2); width: auto; } }
    .jt__head { display: flex; align-items: center; gap: var(--cg-space-2); }
    .jt__title { margin: 0; flex: 1; font-family: var(--cg-font-heading); font-size: 18px; color: var(--cg-cream); }
    .jt__close { color: var(--cg-cream); }
    .jt__note { margin: 0; font-size: 14px; }
    .jt__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--cg-space-2); }
    .jt__item { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px var(--cg-space-2); font-size: 14px; }
    .jt__time { font-family: var(--cg-font-mono); font-size: 12px; opacity: 0.7; }
    .jt__who { font-weight: 600; }
    .jt__text { flex: 1 1 100%; min-width: 0; }
    .jt__link {
      flex: 1 1 100%; min-height: 44px; display: flex; align-items: center; color: var(--cg-cream);
      text-decoration: none; cursor: pointer;
    }
    .jt__link:hover, .jt__link:focus-visible { text-decoration: underline; }
    .jt__link:focus-visible { outline: 2px solid var(--cg-accent); outline-offset: 2px; }
    .jt__link[data-status='EN_COURS'] { color: color-mix(in srgb, var(--cg-cream) 60%, transparent); }
  `],
})
export class JarvisThreadPanelComponent implements OnInit {
  private readonly threads = inject(JarvisThreadService);

  /** Fermer le volet (bouton, ou toucher d'une ligne-lien qui quitte l'écran). */
  readonly closed = output<void>();

  /** Le fil du jour ; `null` tant qu'il n'est pas lu. */
  readonly items = signal<JarvisThreadItem[] | null>(null);
  readonly failed = signal(false);

  ngOnInit(): void {
    this.threads.today().subscribe({
      next: (list) => this.items.set(list ?? []),
      error: () => this.failed.set(true),
    });
  }

  speaker(item: JarvisThreadItem): string {
    return speakerOf(item);
  }

  query(link: AtelierMessageLink): Record<string, string> {
    return linkQueryOf(link);
  }

  oneLine(text: string): string {
    return (text ?? '').split('\n').map((l) => l.trim()).filter((l) => l.length > 0).join(' — ');
  }

  time(at: string): string {
    const date = new Date(at);
    return Number.isNaN(date.getTime()) ? ''
      : date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Paris' });
  }
}
