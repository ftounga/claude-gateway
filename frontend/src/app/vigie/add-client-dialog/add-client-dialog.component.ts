import { Component, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { HostSpaces } from '../../core/models/vigie.models';
import { VigieService } from '../../core/services/vigie.service';
import { HostBadgeComponent } from '../../shared/host-badge/host-badge.component';
import { MissionBadgeComponent } from '../../shared/mission-badge/mission-badge.component';
import { httpErrorMessage } from '../../shared/http-error.util';
import { importableHosts } from '../vigie-fleet';

/** Ce que le dialogue rend : un client activé (à ouvrir), ou la demande d'en connecter un nouveau. */
export type AddClientDialogResult =
  | { kind: 'activated'; hostId: string; hostName?: string }
  | { kind: 'connect' };

/**
 * **Ajouter un client à la Vigie** (F-106 / SF-106-02).
 *
 * <p>Deux gestes, et le premier est le plus fréquent : <b>activer</b> un client qui existe déjà dans
 * la Forge — aucun appairage, c'est la même machine. Le second, <b>connecter</b> un client qui n'a
 * aucun projet technique, rend la main à l'écran qui ouvre le parcours d'appairage existant.</p>
 */
@Component({
  selector: 'app-add-client-dialog',
  imports: [
    MatDialogModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    HostBadgeComponent,
    MissionBadgeComponent,
  ],
  templateUrl: './add-client-dialog.component.html',
  styleUrl: './add-client-dialog.component.scss',
})
export class AddClientDialogComponent implements OnInit {
  static readonly DIALOG_WIDTH = '560px';

  private readonly vigie = inject(VigieService);
  private readonly dialogRef = inject(MatDialogRef<AddClientDialogComponent, AddClientDialogResult>);

  readonly loading = signal(true);
  readonly candidates = signal<HostSpaces[]>([]);
  readonly loadFailed = signal(false);
  readonly activatingId = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.read();
  }

  activate(host: HostSpaces): void {
    if (this.activatingId() !== null) {
      return;
    }
    this.activatingId.set(host.hostId);
    this.error.set(null);
    this.vigie.activate(host.hostId, 'VIGIE').subscribe({
      next: () => {
        this.activatingId.set(null);
        this.dialogRef.close({ kind: 'activated', hostId: host.hostId, hostName: host.name });
      },
      error: (err: unknown) => {
        this.activatingId.set(null);
        this.error.set(httpErrorMessage(err, `« ${host.name} » n'a pas pu être activé. Rien n'a changé.`));
        this.read();
      },
    });
  }

  connect(): void {
    this.dialogRef.close({ kind: 'connect' });
  }

  cancel(): void {
    this.dialogRef.close();
  }

  private read(): void {
    this.loading.set(true);
    this.vigie.hostSpaces().subscribe({
      next: (hosts) => {
        this.candidates.set(importableHosts(hosts ?? []));
        this.loadFailed.set(false);
        this.loading.set(false);
      },
      error: () => {
        this.candidates.set([]);
        this.loadFailed.set(true);
        this.loading.set(false);
      },
    });
  }
}
