import {
  ChangeDetectionStrategy,
  Component,
  Input,
  OnChanges,
  SimpleChanges,
  computed,
  inject,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';

import { VigieService } from '../../core/services/vigie.service';
import { RunnerDiagEntry, RunnerDiagLevel } from '../../core/models/runner-diag.models';

/** Un choix du filtre de niveau : « Tous » ou un niveau minimum. */
type LevelFilter = '' | RunnerDiagLevel;

/**
 * **Le panneau « Journal du runner »** (F-132 / SF-132-03).
 *
 * <p>Sous chaque poste de la Vigie, il lit les derniers événements de <b>diagnostic</b> remontés par
 * le runner (SF-132-01/02) : état du Chrome managé, sonde Teams, cycle de vie de la capture, ticks de
 * la Vigie, erreurs. Des <b>formes et des états</b>, jamais un contenu — le runner expurge à la
 * source. On peut filtrer par <b>niveau minimum</b> (rechargé côté serveur), <b>chercher</b> dans la
 * page chargée, et <b>rafraîchir</b>.</p>
 *
 * <p><b>Charte</b> : aucune couleur nouvelle — badges `.badge--error/--warning/--info/--neutral` de
 * la charte. Best-effort d'affichage : un journal illisible ne casse jamais la Vigie.</p>
 */
@Component({
  selector: 'app-runner-diag-journal',
  imports: [
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatSelectModule,
    MatInputModule,
  ],
  templateUrl: './runner-diag-journal.component.html',
  styleUrl: './runner-diag-journal.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RunnerDiagJournalComponent implements OnChanges {
  private readonly vigie = inject(VigieService);

  /** Le poste dont on lit le journal de diagnostic. */
  @Input({ required: true }) hostId!: string;

  readonly entries = signal<RunnerDiagEntry[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);

  /** Filtre de niveau minimum (rechargé côté serveur). */
  readonly level = signal<LevelFilter>('');
  /** Recherche simple (filtre côté client sur la page chargée). */
  readonly search = signal('');

  /** Les niveaux proposés au filtre. */
  readonly levelOptions: ReadonlyArray<{ value: LevelFilter; label: string }> = [
    { value: '', label: 'Tous les niveaux' },
    { value: 'INFO', label: 'INFO et au-dessus' },
    { value: 'WARN', label: 'WARN et au-dessus' },
    { value: 'ERROR', label: 'ERROR seulement' },
    { value: 'DEBUG', label: 'DEBUG et au-dessus' },
  ];

  /** La liste affichée : la page chargée, filtrée par la recherche (client). */
  readonly visible = computed(() => {
    const needle = this.search().trim().toLowerCase();
    if (!needle) {
      return this.entries();
    }
    return this.entries().filter((e) =>
      [e.level, e.category, e.code, e.message ?? '']
        .join(' ')
        .toLowerCase()
        .includes(needle),
    );
  });

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['hostId'] && this.hostId) {
      this.refresh();
    }
  }

  /** (Re)lit le journal au niveau minimum courant. Aucune écriture : c'est une consultation. */
  refresh(): void {
    if (!this.hostId) {
      return;
    }
    this.loading.set(true);
    this.failed.set(false);
    const level = this.level();
    this.vigie.runnerDiag(this.hostId, level ? { level } : undefined).subscribe({
      next: (entries) => {
        this.entries.set(entries);
        this.loading.set(false);
      },
      error: () => {
        this.entries.set([]);
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Change le niveau minimum et recharge (le filtre de niveau est côté serveur). */
  onLevelChange(value: LevelFilter): void {
    this.level.set(value);
    this.refresh();
  }

  /** Met à jour la recherche (filtre côté client, sans rechargement). */
  onSearch(value: string): void {
    this.search.set(value);
  }

  /** La classe de badge d'un niveau — jamais une couleur nouvelle (charte, `styles.scss`). */
  levelBadge(level: RunnerDiagLevel): string {
    switch (level) {
      case 'ERROR':
        return 'badge--error';
      case 'WARN':
        return 'badge--warning';
      case 'INFO':
        return 'badge--info';
      case 'DEBUG':
        return 'badge--neutral';
    }
  }
}
