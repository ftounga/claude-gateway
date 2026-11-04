import { DatePipe } from '@angular/common';
import { Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { MailCard } from './jarvis-mail';

/**
 * **La visionneuse de courriel** (F-242 / SF-242-04, D16) — le verre holographique de la galerie (DESIGN_SYSTEM §23).
 *
 * Trois scènes : « mail mis en avant » (`highlight` : en-têtes et extrait, pendant le bref), « capture du mail »
 * (`capture` : le corps relu par la passerelle) et « brouillon qui apparaît » (`draft`, SF-242-06). Le corps est rendu
 * en **texte seul** (interpolation Angular, jamais `innerHTML`) : aucune image distante, aucun script, aucun lien actif
 * ne peut se charger. La mention « Contenu externe » rappelle que le texte vient d'un tiers. Le parent ne l'affiche
 * **jamais** en présentation.
 */
@Component({
  selector: 'app-jarvis-mail-card',
  imports: [DatePipe, MatButtonModule, MatIconModule],
  template: `
    @if (card(); as c) {
      <section class="jmc" [class.jmc--draft]="c.mode === 'draft'" [attr.aria-label]="label(c)">
        <header class="jmc__head">
          <span class="jmc__scene">{{ label(c) }}</span>
          @if (c.mode !== 'draft') { <span class="jmc__external">Contenu externe</span> }
          <button mat-icon-button type="button" class="jmc__close" aria-label="Fermer le courriel" (click)="closed.emit()">
            <mat-icon>close</mat-icon>
          </button>
        </header>
        <dl class="jmc__headers">
          <dt>{{ c.mode === 'draft' ? 'À' : 'De' }}</dt>
          <dd class="jmc__from">{{ c.mode === 'draft' ? c.to : (c.fromName ? c.fromName + ' <' + c.from + '>' : c.from) }}</dd>
          <dt>Objet</dt>
          <dd class="jmc__subject">{{ c.subject || '(sans objet)' }}</dd>
          @if (c.receivedAt) {
            <dt>Reçu</dt>
            <dd>{{ c.receivedAt | date: 'dd/MM/yyyy HH:mm' }}</dd>
          }
          @if (c.account) {
            <dt>Boîte</dt>
            <dd>{{ c.account }}</dd>
          }
        </dl>
        <div class="jmc__body">{{ c.text }}</div>
        @if (c.truncated) { <p class="jmc__cut">… la suite est coupée à l’écran.</p> }
      </section>
    }
  `,
  styles: [`
    :host { display: block; }
    .jmc {
      position: fixed; z-index: 4; top: calc(var(--cg-space-2) + 52px); left: var(--cg-space-4);
      width: min(560px, calc(100vw - 2 * var(--cg-space-4))); max-height: calc(100dvh - 140px); overflow: auto;
      box-sizing: border-box; padding: var(--cg-space-4); display: flex; flex-direction: column; gap: var(--cg-space-2);
      background: color-mix(in srgb, var(--cg-navy-2) 70%, transparent); color: var(--cg-cream);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 8px;
      backdrop-filter: blur(10px); font-family: var(--cg-font-body); overflow-wrap: anywhere;
    }
    .jmc--draft { border-color: var(--cg-orange-2); }
    .jmc__head { display: flex; align-items: center; gap: var(--cg-space-2); }
    .jmc__scene { flex: 1; font-family: var(--cg-font-heading); font-size: 16px; }
    .jmc__external { font-size: 12px; padding: 0 var(--cg-space-1); border: 1px solid var(--cg-orange-2);
      color: var(--cg-orange-2); border-radius: 4px; }
    .jmc__close { color: var(--cg-cream); }
    .jmc__headers { display: grid; grid-template-columns: auto 1fr; gap: var(--cg-space-1) var(--cg-space-2);
      margin: 0; font-size: 13px; }
    .jmc__headers dt { opacity: .7; }
    .jmc__headers dd { margin: 0; }
    .jmc__subject { font-weight: 600; }
    .jmc__body { white-space: pre-wrap; font-size: 14px; line-height: 1.5;
      border-top: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); padding-top: var(--cg-space-2); }
    .jmc__cut { margin: 0; font-size: 12px; opacity: .7; }
    @media (max-width: 767px) {
      .jmc { left: var(--cg-space-2); right: var(--cg-space-2); width: auto; padding: var(--cg-space-3); }
    }
  `],
})
export class JarvisMailCardComponent {
  readonly card = input<MailCard | null>(null);
  readonly closed = output<void>();

  label(c: MailCard): string {
    return c.mode === 'draft' ? 'Brouillon' : c.mode === 'capture' ? 'Courriel' : 'Courriel à traiter';
  }
}
