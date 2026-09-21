import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { GovernanceService } from '../../core/services/governance.service';
import { GovernanceHostSummary } from '../../core/models/governance.models';

/** Un client qui n'apprend pas, prêt pour l'écran. */
export interface ForgetfulHost {
  ref: string;
  name: string;
  /** Vrai quand l'activation existe mais que les fichiers ne sont pas posés. */
  pending: boolean;
}

/**
 * **Les clients qui n'apprennent rien, dits dans la Forge** (F-135 / SF-135-02).
 *
 * <p>L'audit du 2026-09-21 a mesuré que <b>trois postes sur quatre</b> n'accumulaient aucun savoir —
 * et que rien, nulle part, ne le disait. Deux causes : l'embarquement des paquets par défaut ne
 * tourne qu'à la création d'un poste, et une activation dont le dépôt échoue reste en attente sans
 * bruit.</p>
 *
 * <p><b>Ici et pas dans la console de gouvernance</b> : c'est la leçon de SF-133-12. Une information
 * qu'il faut aller chercher n'informe personne.</p>
 *
 * <p><b>Une erreur d'API n'affiche rien.</b> Ce bandeau est un confort ; il ne doit jamais abîmer
 * l'écran de travail.</p>
 */
@Component({
  selector: 'app-forge-memory-notice',
  imports: [MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './forge-memory-notice.component.html',
  styleUrl: './forge-memory-notice.component.scss',
})
export class ForgeMemoryNoticeComponent implements OnInit {
  private readonly governance = inject(GovernanceService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly summaries = signal<GovernanceHostSummary[]>([]);

  /** Le poste dont le geste est en cours, pour ne pas le relancer deux fois. */
  readonly working = signal<string | null>(null);

  /**
   * Les postes à signaler. Le poste « Hébergé » (`UNSUPPORTED`) n'en est jamais : il n'a pas de
   * racine, donc pas de carte — l'y faire figurer proposerait un geste sans effet.
   */
  readonly forgetful = computed<ForgetfulHost[]>(() =>
    this.summaries()
      .filter((host) => host.memory === 'ABSENT' || host.memory === 'PENDING')
      .map((host) => ({ ref: host.ref, name: host.name, pending: host.memory === 'PENDING' })),
  );

  ngOnInit(): void {
    this.load();
  }

  /** Met ce poste en mémoire, puis relit l'état plutôt que de le supposer. */
  remember(host: ForgetfulHost): void {
    if (this.working()) {
      return;
    }
    this.working.set(host.ref);
    this.governance
      .rememberHost(host.ref)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.working.set(null);
          // On relit : la machine a pu ne pas répondre, et le poste reste alors « en attente ».
          this.load();
        },
        error: () => this.working.set(null),
      });
  }

  private load(): void {
    this.governance
      .getHosts()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (hosts) => this.summaries.set(hosts),
        // Silence volontaire : pas de bandeau, pas d'erreur à l'écran.
        error: () => this.summaries.set([]),
      });
  }
}
