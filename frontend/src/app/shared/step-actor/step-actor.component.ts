import { Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { StepActor } from '../../core/models/journey.models';

/** Ce que la marque dit, par acteur — l'outil n'est jamais marqué (le cas courant reste silencieux). */
const ACTOR_VIEW: Record<'MOI' | 'TIERS', { icon: string; text: string; aria: string }> = {
  MOI: {
    icon: 'front_hand',
    text: 'par vous',
    aria: 'Exécutée par vous : l\'outil ne peut pas faire ce geste',
  },
  TIERS: {
    icon: 'group',
    text: 'par un tiers',
    aria: 'Exécutée par quelqu\'un d\'autre : l\'outil ne peut pas faire ce geste',
  },
};

/**
 * **Qui exécute l'étape ?** (F-219 / SF-219-06) — une marque discrète posée sur une étape quand ce n'est pas
 * l'outil : icône décorative + libellé court, phrase complète en infobulle et dans `aria-label`. Rien pour
 * l'outil. Aucune couleur nouvelle : « par vous » reprend `--cg-orange` de la pastille « votre décision ».
 */
@Component({
  selector: 'app-step-actor',
  imports: [MatIconModule, MatTooltipModule],
  template: `
    @if (view(); as v) {
      <span class="actor" [attr.data-actor]="actor()" [matTooltip]="v.aria" [attr.aria-label]="v.aria" role="note">
        <mat-icon aria-hidden="true">{{ v.icon }}</mat-icon><span aria-hidden="true">{{ v.text }}</span>
      </span>
    }
  `,
  styles: [`
    :host { display: inline-flex; }

    .actor {
      --tone: var(--cg-text-secondary);
      display: inline-flex;
      align-items: center;
      gap: var(--cg-space-1);
      color: var(--cg-text-secondary);
      font-size: 12px;
      line-height: 1.2;
      white-space: nowrap;

      mat-icon {
        font-size: 14px;
        width: 14px;
        height: 14px;
        color: var(--tone);
      }
    }

    .actor[data-actor='MOI'] { --tone: var(--cg-orange); }
  `],
})
export class StepActorComponent {
  /** L'acteur effectif de l'étape, tel que le serveur le calcule ; `OUTIL` ou absent = rien. */
  readonly actor = input<StepActor | string | null | undefined>(null);

  readonly view = computed(() => {
    const a = this.actor();
    return a === 'MOI' || a === 'TIERS' ? ACTOR_VIEW[a] : null;
  });
}
