import { Component, computed, input, output } from '@angular/core';

import { TurnOutcome, TurnSuggestion, suggestionsFor } from './turn-suggestions';

/**
 * **La suite suggérée, sous la zone de saisie** (F-144 / SF-144-01).
 *
 * <p>Un clic <b>remplit</b> le champ ; il n'envoie rien. C'est la règle du produit — rien ne part
 * vers la machine d'un client sans un geste — et c'est aussi ce qui permet de corriger la phrase
 * avant de l'envoyer.</p>
 *
 * <p><b>Les puces s'effacent dès que l'utilisateur écrit</b> : elles proposent, elles n'encombrent
 * pas.</p>
 */
@Component({
  selector: 'app-turn-suggestions',
  template: `
    @if (visible().length > 0) {
      <div class="suggestions" role="group" aria-label="Suites suggérées">
        @for (suggestion of visible(); track suggestion.text) {
          <button
            type="button"
            class="suggestions__chip"
            (click)="pick.emit(suggestion.text)"
            [attr.aria-label]="'Reprendre : ' + suggestion.label"
          >
            {{ suggestion.label }}
          </button>
        }
      </div>
    }
  `,
  styles: `
    .suggestions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 8px 0 0;
    }

    /* Une puce qu'on peut cliquer, et qui se voit comme telle — sans couleur nouvelle. */
    .suggestions__chip {
      max-width: 100%;
      padding: 4px 12px;
      border: 1px solid var(--cg-divider);
      border-radius: 16px;
      background: var(--cg-surface);
      /* Encre PRINCIPALE et non secondaire : le balayage AA du terminal Teams (F-89 / SF-89-09)
         mesurait 4,38 sur la surface « Papier », sous le seuil de 4,5. Une puce est une action, et
         une action se lit. */
      color: var(--cg-text-primary);
      font: inherit;
      font-size: 12px;
      text-align: left;
      cursor: pointer;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .suggestions__chip:hover,
    .suggestions__chip:focus-visible {
      border-color: var(--cg-accent);
      background: var(--cg-surface-2);
    }
  `,
})
export class TurnSuggestionsComponent {
  /** Le relevé du dernier tour — la seule source de ces suggestions. */
  readonly report = input<TurnOutcome | null>(null);

  /** Ce que l'utilisateur est en train d'écrire : dès qu'il tape, les puces s'effacent. */
  readonly draft = input('');

  /** Le texte choisi, à poser dans le champ. **Jamais envoyé** par ce composant. */
  readonly pick = output<string>();

  private readonly all = computed<TurnSuggestion[]>(() => suggestionsFor(this.report()));

  readonly visible = computed<TurnSuggestion[]>(() =>
    this.draft().trim().length > 0 ? [] : this.all(),
  );
}
