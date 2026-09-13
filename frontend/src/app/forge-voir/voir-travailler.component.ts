import { Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { map } from 'rxjs';

import { MosaiqueComponent } from '../mosaique/mosaique.component';
import { SupervisionComponent } from '../supervision/supervision.component';
import { ForgeDensity, effectiveDensity, parseDensity, storeDensity } from './forge-density';

/**
 * **Voir travailler** (F-98 / SF-98-04) — un seul écran pour regarder travailler ses terminaux.
 *
 * <p>« Y a-t-il une différence entre Voir travailler et Mosaïque ? » La différence était une question
 * de <b>densité</b>, pas de fonction : deux boutons voisins posaient une question que l'utilisateur
 * n'avait pas à se poser. Ici, une porte, et un sélecteur <b>Aperçus / Flux entiers</b>.</p>
 *
 * <p>C'est un <b>conteneur</b> : la supervision (F-76, aucun flux ouvert) et la mosaïque (F-83, quatre
 * lectures de tour, lecture seule) gardent leur logique et leurs tests. Seule la porte change.</p>
 */
@Component({
  selector: 'app-voir-travailler',
  imports: [RouterLink, MatIconModule, SupervisionComponent, MosaiqueComponent],
  templateUrl: './voir-travailler.component.html',
  styleUrl: './voir-travailler.component.scss',
})
export class VoirTravaillerComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private readonly requested = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('densite'))),
    { initialValue: null },
  );

  /** La densité affichée : l'URL, sinon le choix retenu, sinon les aperçus. */
  readonly density = computed<ForgeDensity>(() => effectiveDensity(this.requested()));

  readonly options: readonly { key: ForgeDensity; label: string }[] = [
    { key: 'apercus', label: 'Aperçus' },
    { key: 'flux', label: 'Flux entiers' },
  ];

  constructor() {
    // Un lien qui porte une densité la RETIENT aussi : c'est un choix, d'où qu'il vienne.
    effect(() => {
      const fromUrl = parseDensity(this.requested());
      if (fromUrl) {
        storeDensity(fromUrl);
      }
    });
  }

  /**
   * Change de densité sans changer de page. L'entrée d'historique est **remplacée** : la densité est
   * une manière de regarder, et « retour » doit ramener à la Forge, pas à l'autre densité.
   */
  choose(density: ForgeDensity): void {
    storeDensity(density);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { densite: density },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
}
