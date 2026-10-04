import { Component, computed, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';

import { JourneyMode, SubjectJourney, journeyLabel } from '../../core/models/journey.models';

/**
 * **Le mode du sujet, dans l'en-tête du terminal** (F-176 / SF-176-01, décisions Q1 et Q4).
 *
 * <p>Un bouton qui dit où l'on en est — « Libre », ou « Guidé · Investigation » — et ouvre le menu
 * pour changer de mode à tout moment. Libre est le défaut et ne change rien au terminal ; Guidé
 * applique les phases et la porte de plan.</p>
 */
@Component({
  selector: 'app-terminal-journey-chip',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule],
  template: `
    <button
      mat-button
      type="button"
      class="journey-chip"
      [class.journey-chip--guided]="guided()"
      [disabled]="busy()"
      [matMenuTriggerFor]="journeyMenu"
      [attr.aria-label]="'Parcours du sujet : ' + label() + '. Changer de mode.'"
      matTooltip="Le parcours du sujet : Libre, ou Guidé (investigation, plan, exécution, vérification)"
    >
      <mat-icon>{{ guided() ? 'route' : 'explore' }}</mat-icon>
      {{ label() }}
    </button>
    <mat-menu #journeyMenu="matMenu" class="journey-menu">
      <button mat-menu-item type="button" class="journey-menu__libre" (click)="choose('LIBRE')">
        <mat-icon>{{ guided() ? 'radio_button_unchecked' : 'radio_button_checked' }}</mat-icon>
        <span class="journey-menu__text">
          <strong>Libre</strong>
          <small>Question, petit geste, exploration — le terminal tel qu'il est.</small>
        </span>
      </button>
      <button mat-menu-item type="button" class="journey-menu__guide" (click)="choose('GUIDE')">
        <mat-icon>{{ guided() ? 'radio_button_checked' : 'radio_button_unchecked' }}</mat-icon>
        <span class="journey-menu__text">
          <strong>Guidé</strong>
          <small>Chantier, incident, changement d'infra : on comprend, on planifie, puis on agit.</small>
        </span>
      </button>
    </mat-menu>
  `,
  styles: `
    :host {
      display: inline-flex;
    }

    .journey-chip {
      white-space: nowrap;
    }

    .journey-chip--guided {
      color: var(--cg-orange-2);
      border: 1px solid var(--cg-orange-2);
      border-radius: 16px;
    }

    .journey-menu__text {
      display: inline-flex;
      flex-direction: column;
      line-height: 1.25;
      white-space: normal;
    }

    .journey-menu__text small {
      color: var(--cg-text-secondary);
      font-size: 12px;
    }
  `,
})
export class TerminalJourneyChipComponent {
  /** Le parcours du terminal ; `null` tant qu'il n'est pas chargé (affiché « Libre »). */
  readonly journey = input<SubjectJourney | null>(null);

  /** Un changement est en cours : le bouton attend. */
  readonly busy = input(false);

  /** Le mode choisi dans le menu (rien n'est émis si c'est déjà le mode courant). */
  readonly modeChange = output<JourneyMode>();

  readonly guided = computed(() => this.journey()?.mode === 'GUIDE');
  readonly label = computed(() => journeyLabel(this.journey()));

  choose(mode: JourneyMode): void {
    const current: JourneyMode = this.guided() ? 'GUIDE' : 'LIBRE';
    if (mode !== current) {
      this.modeChange.emit(mode);
    }
  }
}
