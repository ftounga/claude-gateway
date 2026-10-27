import { Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { UNBLOCKER_ICONS, UNBLOCKER_SHORT, UnblockerMark } from '../../core/models/client-board.models';

/**
 * **La pastille « qui débloque ? »** (F-220 / SF-220-04) — la même couleur que le tableau du client, posée
 * dans la vue d'un sujet (tableau des chantiers F-208 : onglets, résumé, étapes).
 *
 * <p>La couleur ne porte jamais seule l'information : la pastille écrit **toujours** son libellé, l'icône est
 * décorative, la phrase complète est en infobulle et dans `aria-label`. Aucune couleur nouvelle : l'outil =
 * `--cg-success`, ma décision = `--cg-orange`, quelqu'un = `--cg-text-secondary`.</p>
 */
@Component({
  selector: 'app-unblocker-pill',
  imports: [MatIconModule, MatTooltipModule],
  template: `
    @if (mark(); as m) {
      <span class="pill" [attr.data-who]="m.who" [class.pill--sm]="size() === 'sm'"
        [matTooltip]="m.detail" [attr.aria-label]="m.label + ' — ' + m.detail">
        <mat-icon aria-hidden="true">{{ icon() }}</mat-icon>{{ text() }}
      </span>
    }
  `,
  styles: [`
    :host { display: inline-flex; }

    .pill {
      --tone: var(--cg-text-secondary);
      display: inline-flex;
      align-items: center;
      gap: var(--cg-space-1);
      padding: var(--cg-space-1) var(--cg-space-2);
      border-radius: 999px;
      background: color-mix(in srgb, var(--tone) 14%, var(--cg-surface));
      color: var(--cg-text-primary);
      font-size: 12px;
      font-weight: 600;
      line-height: 1.2;
      white-space: nowrap;

      mat-icon {
        font-size: 16px;
        width: 16px;
        height: 16px;
        color: var(--tone);
      }
    }

    .pill[data-who='OUTIL'] { --tone: var(--cg-success); }
    .pill[data-who='MOI'] { --tone: var(--cg-orange); }

    .pill--sm {
      padding: 0 var(--cg-space-1);
      font-size: 11px;
      font-weight: 500;

      mat-icon {
        font-size: 12px;
        width: 12px;
        height: 12px;
      }
    }
  `],
})
export class UnblockerPillComponent {
  /** La marque calculée par le serveur ; rien ne s'affiche sans elle. */
  readonly mark = input<UnblockerMark | null | undefined>(null);
  /** `md` : le libellé complet ; `sm` : le libellé court (onglets, étapes). */
  readonly size = input<'sm' | 'md'>('md');

  readonly icon = computed(() => {
    const m = this.mark();
    return m ? UNBLOCKER_ICONS[m.who] : '';
  });

  readonly text = computed(() => {
    const m = this.mark();
    if (!m) {
      return '';
    }
    return this.size() === 'sm' ? UNBLOCKER_SHORT[m.who] : m.label;
  });
}
