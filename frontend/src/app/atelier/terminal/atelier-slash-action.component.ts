import { ChangeDetectionStrategy, Component, Input } from '@angular/core';

/**
 * Le **corps d'accusé d'une commande ACTION** (F-165 / SF-165-06) — `/compacter` et `/nouveau`.
 *
 * <p>Le dispatch a émis la **sortie existante** du terminal (`compactNow` / `restart`), déjà câblée au
 * parent qui produit ses retours habituels (barre, marqueur, repli, snackbar). Ce corps n'est qu'un
 * **accusé local** : il confirme le geste, sans réimplémenter aucun mécanisme. Présentation pure.</p>
 */
@Component({
  selector: 'app-atelier-slash-action',
  standalone: true,
  imports: [],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atelier-slash-action.component.html',
  styleUrl: './atelier-slash-action.component.scss',
})
export class AtelierSlashActionComponent {
  /** Le message d'accusé à afficher. */
  @Input() message = '';
}
