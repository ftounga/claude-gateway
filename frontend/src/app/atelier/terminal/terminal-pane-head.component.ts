import { Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

/**
 * **L'en-tête d'un volet du split** (F-221 / SF-221-03) : le chantier montré, ce qu'il fait, et [Fermer ce
 * volet] — qui ne tue rien : le chantier continue côté serveur.
 */
@Component({
  selector: 'app-terminal-pane-head',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <div class="terminal-pane-head" role="group" [attr.aria-label]="'Volet : ' + title()">
      <mat-icon class="terminal-pane-head__icon" aria-hidden="true">view_column</mat-icon>
      <span class="terminal-pane-head__title">{{ title() }}</span>
      @if (activity()) {
        <span class="terminal-pane-head__activity">
          <span class="terminal-pane-head__dot" aria-hidden="true"></span>{{ activity() }}
        </span>
      }
      <button mat-button type="button" class="terminal-pane-head__close" (click)="closed.emit()"
              [attr.aria-label]="'Fermer ce volet — ' + title() + ' continue'">
        <mat-icon>close</mat-icon>
        Fermer ce volet
      </button>
    </div>
  `,
  styleUrl: './terminal-pane-head.component.scss',
})
export class TerminalPaneHeadComponent {
  readonly title = input.required<string>();
  readonly activity = input<string | null>(null);
  readonly closed = output<void>();
}
