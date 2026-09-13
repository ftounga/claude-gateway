import { Component, computed, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';

import { VigiePerson } from '../../core/models/vigie.models';
import { roleLabel, stateBadge } from '../radar-subject/radar-subject-view';
import {
  DIRECTORY_FILTER_MAX,
  DIRECTORY_PAGE,
  filterPeople,
  interactionLabel,
  personSubjects,
  sortPeople,
  subjectsLabel,
} from './radar-directory';

/**
 * **L'annuaire du Radar** d'un client (F-103 / SF-103-04) — l'onglet *Personnes* de la Vigie.
 *
 * <p>Les personnes rencontrées, la dernière interaction, leurs sujets et leur rôle sur chacun ; chaque
 * sujet mène à sa page. L'annuaire est lu par la Vigie (une fois par client) et passé tel quel : ce
 * composant ne lit rien lui-même.</p>
 *
 * <p>§17 : une personne est écrite, jamais colorée ; le rôle est un mot ; seule la pastille d'état du
 * sujet porte une couleur, celle de l'onglet Radar.</p>
 */
@Component({
  selector: 'app-radar-directory',
  imports: [RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
  templateUrl: './radar-directory.component.html',
  styleUrl: './radar-directory.component.scss',
})
export class RadarDirectoryComponent {
  /** Le client ouvert : les liens de sujet vivent sous lui. */
  readonly hostId = input.required<string>();
  readonly people = input<VigiePerson[]>([]);

  readonly filter = signal('');
  /** Nombre de personnes affichées ; s'élargit par « Afficher les *k* autres ». */
  readonly shown = signal(DIRECTORY_PAGE);

  readonly filterMax = DIRECTORY_FILTER_MAX;

  readonly matching = computed(() => filterPeople(sortPeople(this.people()), this.filter()));
  readonly visible = computed(() => this.matching().slice(0, this.shown()));
  readonly hiddenCount = computed(() => Math.max(0, this.matching().length - this.shown()));

  readonly personSubjects = personSubjects;
  readonly subjectsLabel = subjectsLabel;
  readonly interactionLabel = (person: VigiePerson) => interactionLabel(person);
  readonly roleLabel = roleLabel;
  readonly stateBadge = (state: string) => stateBadge(state);

  onFilter(value: string): void {
    this.filter.set(value.slice(0, DIRECTORY_FILTER_MAX));
    this.shown.set(DIRECTORY_PAGE);
  }

  showMore(): void {
    this.shown.update((n) => n + DIRECTORY_PAGE);
  }
}
