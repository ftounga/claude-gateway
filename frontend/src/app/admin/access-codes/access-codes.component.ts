import { Component, OnInit, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe } from '@angular/common';

import { AccessCodeAdminService } from '../access-code-admin.service';
import { AccessCodeAdminView, AccessCodeState } from '../access-code-admin.models';
import {
  AccessCodeDialogComponent,
  AccessCodeDraft,
} from './access-code-dialog/access-code-dialog.component';

/**
 * Section **Codes d'accès** de l'administration (F-62 / SF-62-03) : l'endroit où l'admin crée un
 * code, le copie une fois, et lit ensuite qui l'a consommé, quand, et vers quel plan ce compte
 * reviendra.
 *
 * <p>Elle vit dans `/admin`, à côté des utilisateurs et de la gouvernance : donner une route à part
 * à chaque geste d'administration multiplierait les endroits où l'on se demande « que peut faire un
 * admin » (arbitrage repris de F-51).</p>
 *
 * <p><b>Le code n'est montré qu'une fois.</b> Il n'est pas stocké — seule son empreinte l'est — et
 * l'écran le dit sans détour plutôt que de laisser croire qu'on pourra le retrouver.</p>
 *
 * <p>L'<b>état</b> d'un code est calculé par le serveur, jamais redérivé ici : deux horloges
 * produiraient deux vérités, dont une fausse.</p>
 */
@Component({
  selector: 'app-access-codes',
  imports: [
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './access-codes.component.html',
  styleUrl: './access-codes.component.scss',
})
export class AccessCodesComponent implements OnInit {
  private readonly service = inject(AccessCodeAdminService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);

  readonly codes = signal<AccessCodeAdminView[]>([]);
  readonly loading = signal(true);
  readonly failed = signal(false);
  readonly busy = signal(false);
  /**
   * Le dernier code émis, en clair. Vit uniquement en mémoire, le temps de le copier : il n'est ni
   * stocké côté serveur, ni relisible.
   */
  readonly freshCode = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.service.list().subscribe({
      next: (codes) => {
        this.codes.set(codes);
        this.loading.set(false);
      },
      error: () => {
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Libellé lisible d'un état. Le serveur donne le fait, l'écran donne le mot. */
  stateLabel(state: AccessCodeState): string {
    switch (state) {
      case 'ISSUED':
        return 'à remettre';
      case 'ACTIVE':
        return 'en cours';
      case 'ENDED':
        return 'terminé';
      default:
        return 'périmé';
    }
  }

  /** Destinataire d'un code, ou la mention qui dit qu'il n'en a pas. */
  recipient(code: AccessCodeAdminView): string {
    return code.assignedEmail ?? 'non nominatif';
  }

  create(): void {
    this.dialog
      .open(AccessCodeDialogComponent, { width: '520px' })
      .afterClosed()
      .subscribe((draft: AccessCodeDraft | undefined) => {
        if (draft) {
          this.issue(draft);
        }
      });
  }

  /**
   * Copie le code dans le presse-papiers. Échec silencieux **non bloquant** : le code reste affiché
   * et sélectionnable — un presse-papiers refusé ne doit pas faire perdre le code.
   */
  copyFreshCode(): void {
    const code = this.freshCode();
    if (!code) {
      return;
    }
    navigator.clipboard?.writeText(code).then(
      () => this.snackBar.open('Code copié.', 'Fermer', { duration: 3000 }),
      () => this.snackBar.open('Copie impossible : sélectionnez le code à la main.', 'Fermer', {
        duration: 6000,
      }),
    );
  }

  /** Efface le code affiché — geste explicite, pour ne pas le laisser traîner à l'écran. */
  dismissFreshCode(): void {
    this.freshCode.set(null);
  }

  private issue(draft: AccessCodeDraft): void {
    this.busy.set(true);
    this.service.issue(draft.label, draft.assignedEmail).subscribe({
      next: (issued) => {
        this.busy.set(false);
        this.freshCode.set(issued.code);
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        // Le message du backend nomme déjà le champ fautif : le réécrire ici en perdrait la
        // précision et créerait une seconde version de la règle.
        const message = err.error?.message ?? "Le code n'a pas pu être créé.";
        this.snackBar.open(message, 'Fermer', { duration: 8000 });
      },
    });
  }
}
