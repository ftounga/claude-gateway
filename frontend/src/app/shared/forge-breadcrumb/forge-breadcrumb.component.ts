import { ChangeDetectionStrategy, Component, Input, computed, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { HostBadgeComponent } from '../host-badge/host-badge.component';
import { MissionBadgeComponent } from '../mission-badge/mission-badge.component';

/**
 * Un niveau du fil d'Ariane. Tout est facultatif sauf le libellé et la destination : un niveau sans
 * l'un des deux ne serait ni lisible, ni cliquable, et le PO a tranché que **chaque niveau est
 * cliquable**.
 */
export interface ForgeCrumb {
  /** Ce qui est écrit. Un libellé vide fait sauter le niveau — mieux vaut rien qu'un chaînon creux. */
  label: string;
  /** Commandes de routeur de la destination, ex. `['/atelier', id]`. */
  link: unknown[];
  /** Ancre de page visée, ex. `poste-<id>`. Absente quand on ne sait pas où pointer précisément. */
  fragment?: string | null;
  /**
   * Nom du poste : quand il est là, le niveau porte **la pastille d'identité** (SF-49-03) au lieu
   * d'un texte nu. La couleur reste dérivée du nom, jamais transmise.
   */
  hostName?: string | null;
  /**
   * État de mission à montrer à côté (F-60). Absent ⇒ rien n'est affiché : hors de l'accueil de la
   * Forge, « En cours » est la norme et reste silencieux.
   */
  missionStatus?: string | null;
}

/**
 * **Fil d'Ariane de la Forge** (F-68 / SF-68-01) — « Forge › CAGIP › mon-projet ».
 *
 * <p>F-68 fait de la vue des missions la page d'accueil de la Forge et supprime l'onglet
 * « Postes ». Retirer un onglet, c'est retirer un repère : un bouton « retour » dit d'où l'on
 * vient, jamais <b>où l'on est</b>, et encore moins <b>chez qui</b>. Ce fil dit les deux.</p>
 *
 * <p><b>Trois invariants</b> :</p>
 * <ul>
 *   <li><b>Il commence toujours par « Forge »</b>, lié à l'accueil de la Forge. Le composant
 *       l'ajoute lui-même : aucun écran ne peut l'oublier.</li>
 *   <li><b>Chaque niveau est un lien</b> — décision du PO. Le dernier pointe sur la page courante
 *       et porte `aria-current="page"` : cliquer n'emmène nulle part, et un lecteur d'écran sait
 *       que c'est là qu'on est.</li>
 *   <li><b>Aucun registre de couleur nouveau.</b> Trois cohabitent déjà — identité du poste
 *       (charte §9), statut de mission (§5), charte générale. Le fil n'en invente aucun : texte
 *       secondaire, accent au survol, et il <b>réemploie</b> `app-host-badge` et
 *       `app-mission-badge` plutôt que de les redessiner.</li>
 * </ul>
 *
 * <p>Rien n'est lu ni écrit : le composant ne fait qu'afficher ce que l'écran lui donne.</p>
 */
@Component({
  selector: 'app-forge-breadcrumb',
  imports: [RouterLink, HostBadgeComponent, MissionBadgeComponent],
  templateUrl: './forge-breadcrumb.component.html',
  styleUrl: './forge-breadcrumb.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ForgeBreadcrumbComponent {

  private readonly received = signal<ForgeCrumb[]>([]);

  /** Niveaux **après** « Forge ». Vide ⇒ le fil se réduit à « Forge », page courante. */
  @Input()
  set crumbs(value: ForgeCrumb[] | null | undefined) {
    this.received.set(value ?? []);
  }

  /** Destination du premier niveau — l'accueil de la Forge, et donc la vue des missions. */
  readonly forgeLink: unknown[] = ['/forge'];

  /**
   * Le fil complet : « Forge » puis les niveaux reçus, amputés de ceux dont le libellé est vide.
   * Les filtrer ici plutôt que dans le gabarit garde une seule définition de « dernier niveau ».
   */
  readonly trail = computed<ForgeCrumb[]>(() => [
    { label: 'Forge', link: this.forgeLink },
    ...this.received().filter((crumb) => (crumb.label ?? '').trim().length > 0),
  ]);
}
