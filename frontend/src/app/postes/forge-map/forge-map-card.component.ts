import { Clipboard } from '@angular/cdk/clipboard';
import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { MapCard, MapCardFact, MapCardRelation } from '../../core/models/governance.models';
import { GovernanceService } from '../../core/services/governance.service';
import { MapFileDialogComponent, MapFileDialogData } from '../map-file-dialog/map-file-dialog.component';
import { kindLabel } from './forge-map-levels';

const NATURE_OUT: Record<string, string> = {
  dans: 'est dans',
  heberge: 'héberge',
  accede_a: 'accède à',
  depend_de: 'dépend de',
  accorde: 'accorde',
  remplace: 'remplace',
};

const NATURE_IN: Record<string, string> = {
  dans: 'contient',
  heberge: 'est hébergé par',
  accede_a: 'est atteint par',
  depend_de: 'est requis par',
  accorde: 'est accordé par',
  remplace: 'est remplacé par',
};

/** Une relation, dite depuis la ressource : « est dans compte prod », « est atteint par netskope ». */
export function relationPhrase(relation: MapCardRelation): string {
  const table = relation.direction === 'out' ? NATURE_OUT : NATURE_IN;
  return table[relation.nature] ?? relation.nature.replace(/_/g, ' ');
}

/** D'où vient un fait : « acces.md § Proxy · L7 ». */
export function factSource(fact: MapCardFact): string {
  return `${fact.path}${fact.heading ? ' § ' + fact.heading : ''} · L${fact.lineNo}`;
}

/**
 * **La fiche d'une ressource** (F-173 / SF-173-03, niveau 3 de D4) : ce que la carte sait d'elle —
 * pièges en tête, faits datés et sourcés, identifiants copiables, relations cliquables.
 *
 * <p>Lue dans l'index en base (D1). Le lien vers le fichier ouvre le texte exact, lu sur le poste
 * (le dialogue de la vue Fichiers, inchangé) : c'est la référence (F-174 D1).</p>
 */
@Component({
  selector: 'app-forge-map-card',
  standalone: true,
  imports: [DatePipe, MatButtonModule, MatIconModule, MatProgressSpinnerModule, MatTooltipModule],
  templateUrl: './forge-map-card.component.html',
  styleUrl: './forge-map-card.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ForgeMapCardComponent {
  readonly hostRef = input.required<string>();
  readonly hostName = input.required<string>();
  readonly nodeId = input.required<string>();
  /** Aller à une ressource liée. */
  readonly navigate = output<string>();
  /** Fermer la fiche (remonter au niveau). */
  readonly closed = output<void>();

  private readonly governance = inject(GovernanceService);
  private readonly clipboard = inject(Clipboard);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  readonly card = signal<MapCard | null>(null);
  readonly loading = signal(false);
  readonly failed = signal(false);

  readonly traps = computed(() => this.card()?.facts.filter((f) => f.kind === 'PIEGE') ?? []);
  readonly others = computed(() => this.card()?.facts.filter((f) => f.kind !== 'PIEGE') ?? []);

  readonly kindLabel = kindLabel;
  readonly relationPhrase = relationPhrase;
  readonly factSource = factSource;

  constructor() {
    effect(() => {
      const ref = this.hostRef();
      const id = this.nodeId();
      untracked(() => this.load(ref, id));
    });
  }

  load(ref: string = this.hostRef(), id: string = this.nodeId()): void {
    this.loading.set(true);
    this.failed.set(false);
    this.governance.hostMapEntity(ref, id).subscribe({
      next: (card) => {
        this.card.set(card);
        this.loading.set(false);
      },
      error: () => {
        this.card.set(null);
        this.loading.set(false);
        this.failed.set(true);
      },
    });
  }

  copy(identifier: string): void {
    const done = this.clipboard.copy(identifier);
    this.snackBar.open(done ? `Copié : ${identifier}` : 'La copie a échoué.', undefined, { duration: 2500 });
  }

  /** Ouvre le fichier de la carte où le fait est écrit — le texte exact, lu sur le poste. */
  openFile(path: string): void {
    this.dialog.open<MapFileDialogComponent, MapFileDialogData>(MapFileDialogComponent, {
      width: '720px',
      maxWidth: '95vw',
      data: {
        hostRef: this.hostRef(),
        hostName: this.hostName(),
        file: { path, title: path, present: true, readable: true, sections: [], facts: 0, truncated: false, message: null },
      },
    });
  }
}
