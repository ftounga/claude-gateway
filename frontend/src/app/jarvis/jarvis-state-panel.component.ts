import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';

import { ConfirmDialogComponent, ConfirmDialogData } from '../chat/confirm-dialog/confirm-dialog.component';
import {
  JarvisStateClient, STATE_FAMILIES, StateClient, StateFact, StateSettingsPatch, stateSourceLabel,
} from './jarvis-state';

/**
 * **Le volet « Ce que je sais »** (F-247 / SF-247-15) — dans les réglages de `/jarvis` (pas de nouvelle route) : un
 * volet par client, **activé d'office**, avec l'interrupteur pour couper (couper efface), les sources, les familles, la
 * rétention, « Effacer tout », et ce que Jarvis sait de ce client (faits datés, source), effaçables un à un. Le parent
 * ne l'affiche **jamais** en présentation.
 */
@Component({
  selector: 'app-jarvis-state-panel',
  imports: [DatePipe, MatButtonModule, MatIconModule, MatSlideToggleModule],
  template: `
    <section class="js" aria-label="Ce que je sais">
      <header class="js__head">
        <h2 class="js__title">Ce que je sais</h2>
        <button mat-icon-button type="button" class="js__close" aria-label="Fermer « Ce que je sais »"
                (click)="closed.emit()">
          <mat-icon>close</mat-icon>
        </button>
      </header>
      <p class="js__hint">
        Ce que Jarvis a lu sur vos postes (tickets, MR, pipelines, versions déployées), daté, pour vous répondre
        poste éteint. Activé pour chaque client ; le couper efface ce qu'il sait de ce client.
      </p>
      @if (loading()) {
        <p class="js__hint">Chargement…</p>
      } @else if (clients().length === 0) {
        <p class="js__hint js__empty">Aucun client relié.</p>
      } @else {
        @for (c of clients(); track c.hostId) {
          <article class="js__client">
            <div class="js__row">
              <span class="js__name">{{ c.name }}</span>
              <span class="js__meta">{{ c.facts }} fait{{ c.facts > 1 ? 's' : '' }}</span>
              <mat-slide-toggle class="js__enabled" [checked]="c.enabled" [disabled]="busy()"
                                (change)="toggleEnabled(c, $event.checked)">Activé</mat-slide-toggle>
            </div>
            @if (c.enabled) {
              <div class="js__sources">
                <mat-slide-toggle [checked]="c.extractFromTurns" [disabled]="busy()"
                                  (change)="save(c, { extractFromTurns: $event.checked })">Tours</mat-slide-toggle>
                <mat-slide-toggle [checked]="c.releveEnabled" [disabled]="busy()"
                                  (change)="save(c, { releveEnabled: $event.checked })">Relevé</mat-slide-toggle>
                <mat-slide-toggle [checked]="c.capturesEnabled" [disabled]="busy()"
                                  (change)="save(c, { capturesEnabled: $event.checked })">Captures</mat-slide-toggle>
                <mat-slide-toggle [checked]="c.notesEnabled" [disabled]="busy()"
                                  (change)="save(c, { notesEnabled: $event.checked })">Constats</mat-slide-toggle>
              </div>
              <div class="js__families" role="group" aria-label="Familles">
                @for (f of families; track f.id) {
                  <button mat-stroked-button type="button" class="js__family"
                          [class.js__family--off]="c.disabledFamilies.includes(f.id)"
                          [attr.aria-pressed]="!c.disabledFamilies.includes(f.id)" [disabled]="busy()"
                          (click)="toggleFamily(c, f.id)">{{ f.label }}</button>
                }
              </div>
              <div class="js__row">
                <span class="js__meta">Gardé {{ c.factRetentionDays }} j</span>
                <button mat-button type="button" class="js__see" (click)="show(c)">
                  {{ openHost() === c.hostId ? 'Masquer' : 'Voir' }}
                </button>
                <button mat-button type="button" color="warn" class="js__erase" [disabled]="busy() || c.facts === 0"
                        (click)="eraseAll(c)">Effacer tout</button>
              </div>
              @if (openHost() === c.hostId) {
                <ul class="js__facts">
                  @for (f of facts(); track f.id) {
                    <li class="js__fact">
                      <span class="js__key">{{ f.objectKey }}</span>
                      <span class="js__value">{{ f.attribute }} : {{ f.value }}</span>
                      <span class="js__meta">{{ f.observedAt | date: 'd/MM HH:mm' }} · {{ sourceLabel(f.source) }}</span>
                      <button mat-icon-button type="button" class="js__delete" [disabled]="busy()"
                              [attr.aria-label]="'Effacer ' + f.objectKey" (click)="deleteFact(c, f)">
                        <mat-icon>delete</mat-icon>
                      </button>
                    </li>
                  } @empty {
                    <li class="js__hint">Rien de relevé pour ce client.</li>
                  }
                </ul>
              }
            }
          </article>
        }
      }
    </section>
  `,
  styles: [`
    :host { display: block; }
    .js {
      position: fixed; z-index: 4; top: calc(var(--cg-space-2) + 52px); right: var(--cg-space-4);
      width: min(560px, calc(100vw - 2 * var(--cg-space-4))); max-height: calc(100dvh - 120px); overflow: auto;
      box-sizing: border-box; padding: var(--cg-space-4); display: flex; flex-direction: column; gap: var(--cg-space-2);
      background: color-mix(in srgb, var(--cg-navy-2) 70%, transparent); color: var(--cg-cream);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 8px;
      backdrop-filter: blur(10px); font-family: var(--cg-font-body); overflow-wrap: anywhere;
    }
    .js__head, .js__row { display: flex; align-items: center; flex-wrap: wrap; gap: var(--cg-space-2); }
    .js__title { margin: 0; flex: 1; font-family: var(--cg-font-heading); font-size: 18px; color: var(--cg-cream); }
    .js__close, .js__see, .js__delete { color: var(--cg-cream); }
    .js__hint { margin: 0; font-size: 13px; opacity: .8; }
    .js__client { display: flex; flex-direction: column; gap: var(--cg-space-2); padding: var(--cg-space-2);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 4px; }
    .js__name { flex: 1 1 120px; font-weight: 600; font-size: 14px; }
    .js__meta { font-family: var(--cg-font-mono); font-size: 12px; opacity: .7; }
    .js__sources, .js__families { display: flex; flex-wrap: wrap; gap: var(--cg-space-1) var(--cg-space-3); }
    .js__family { color: var(--cg-cream); font-size: 12px; }
    .js__family--off { opacity: .45; text-decoration: line-through; }
    .js__facts { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--cg-space-1); }
    .js__fact { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cg-space-1) var(--cg-space-2);
      font-size: 13px; border-top: 1px solid color-mix(in srgb, var(--cg-cream) 12%, transparent); padding-top: var(--cg-space-1); }
    .js__key { font-family: var(--cg-font-mono); font-size: 12px; }
    .js__value { flex: 1 1 160px; }
    @media (max-width: 767px) {
      .js { right: var(--cg-space-2); left: var(--cg-space-2); width: auto; padding: var(--cg-space-3); }
    }
  `],
})
export class JarvisStatePanelComponent implements OnInit {
  private readonly client = inject(JarvisStateClient);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);

  readonly closed = output<void>();
  readonly families = STATE_FAMILIES;
  readonly clients = signal<StateClient[]>([]);
  readonly facts = signal<StateFact[]>([]);
  readonly openHost = signal<string | null>(null);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly sourceLabel = stateSourceLabel;

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.client.clients().subscribe({
      next: (list) => {
        this.clients.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.snack.open('Impossible de lire ce que sait Jarvis.', undefined, { duration: 4000 });
      },
    });
  }

  /** Couper efface : confirmation d'abord ; rallumer part tout de suite. */
  toggleEnabled(c: StateClient, enabled: boolean): void {
    if (enabled) {
      this.save(c, { enabled: true });
      return;
    }
    const data: ConfirmDialogData = {
      title: `Couper pour ${c.name} ?`,
      message: 'Jarvis efface tout ce qu’il sait de ce client et cesse de le relever.',
      confirmLabel: 'Couper et effacer',
    };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (ok) {
        this.save(c, { enabled: false });
      } else {
        this.replace({ ...c });
      }
    });
  }

  toggleFamily(c: StateClient, family: string): void {
    const off = c.disabledFamilies.includes(family)
      ? c.disabledFamilies.filter((f) => f !== family) : [...c.disabledFamilies, family];
    this.save(c, { disabledFamilies: off });
  }

  save(c: StateClient, patch: StateSettingsPatch): void {
    this.busy.set(true);
    this.client.save(c.hostId, patch).subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.replace(saved);
        if (this.openHost() === c.hostId) {
          this.loadFacts(c.hostId);
        }
      },
      error: () => {
        this.busy.set(false);
        this.snack.open('Réglage non enregistré.', undefined, { duration: 4000 });
        this.replace({ ...c });
      },
    });
  }

  show(c: StateClient): void {
    if (this.openHost() === c.hostId) {
      this.openHost.set(null);
      return;
    }
    this.openHost.set(c.hostId);
    this.loadFacts(c.hostId);
  }

  eraseAll(c: StateClient): void {
    const data: ConfirmDialogData = {
      title: `Effacer tout pour ${c.name} ?`,
      message: 'Jarvis oublie ce qu’il a relevé sur ce client. Le relevé reprendra.',
      confirmLabel: 'Effacer tout',
    };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (!ok) {
        return;
      }
      this.client.eraseAll(c.hostId).subscribe({
        next: () => {
          this.snack.open('Effacé.', undefined, { duration: 3000 });
          this.replace({ ...c, facts: 0 });
          this.facts.set([]);
        },
        error: () => this.snack.open('Effacement impossible.', undefined, { duration: 4000 }),
      });
    });
  }

  deleteFact(c: StateClient, f: StateFact): void {
    this.busy.set(true);
    this.client.deleteFact(f.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.facts.set(this.facts().filter((x) => x.id !== f.id));
        this.replace({ ...c, facts: Math.max(0, c.facts - 1) });
      },
      error: () => {
        this.busy.set(false);
        this.snack.open('Effacement impossible.', undefined, { duration: 4000 });
      },
    });
  }

  private loadFacts(hostId: string): void {
    this.client.facts(hostId).subscribe({ next: (list) => this.facts.set(list), error: () => this.facts.set([]) });
  }

  private replace(c: StateClient): void {
    this.clients.set(this.clients().map((x) => (x.hostId === c.hostId ? c : x)));
  }
}
