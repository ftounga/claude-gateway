import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';

import { ConfirmDialogComponent, ConfirmDialogData } from '../chat/confirm-dialog/confirm-dialog.component';
import {
  JarvisStateClient, ReleveView, STATE_FAMILIES, StateClient, StateConversation, StateFact, StateSettingsPatch,
  StateSpend, capturePlatformLabel, eur, releveProbeStatus, stateSourceLabel,
} from './jarvis-state';

/** Ce que le volet d'un client montre sous ses réglages. */
type StateView = 'facts' | 'conversations' | 'releve';

/**
 * **Le volet « Ce que je sais »** (F-247 / SF-247-15) — dans les réglages de `/jarvis` (pas de nouvelle route) : un
 * volet par client, **activé d'office**, avec l'interrupteur pour couper (couper efface), les sources, les familles, la
 * rétention, « Effacer tout », et ce que Jarvis sait de ce client (faits datés, source), effaçables un à un. Le parent
 * ne l'affiche **jamais** en présentation.
 */
@Component({
  selector: 'app-jarvis-state-panel',
  imports: [DatePipe, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSlideToggleModule],
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
      @if (spend(); as s) {
        <p class="js__spend">
          Ce mois : captures {{ euro(s.captures.monthEur) }} / {{ euro(s.captures.capEur) }}
          · constats {{ euro(s.notes.monthEur) }} / {{ euro(s.notes.capEur) }}
        </p>
      }
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
              <div class="js__retention">
                <mat-form-field appearance="outline" class="js__days" subscriptSizing="dynamic">
                  <mat-label>Faits (jours)</mat-label>
                  <input matInput type="number" min="1" max="365" [value]="c.factRetentionDays" [disabled]="busy()"
                         (change)="retention(c, 'factRetentionDays', $any($event.target).value)">
                  @if (badDays(c.factRetentionDays)) { <mat-error>De 1 à 365 jours.</mat-error> }
                </mat-form-field>
                <mat-form-field appearance="outline" class="js__days" subscriptSizing="dynamic">
                  <mat-label>Captures (jours)</mat-label>
                  <input matInput type="number" min="1" max="365" [value]="c.captureRetentionDays" [disabled]="busy()"
                         (change)="retention(c, 'captureRetentionDays', $any($event.target).value)">
                </mat-form-field>
              </div>
              <div class="js__row">
                <button mat-button type="button" class="js__see" [class.js__tab--on]="isOpen(c, 'facts')"
                        (click)="show(c)">{{ isOpen(c, 'facts') ? 'Masquer' : 'Voir' }}</button>
                <button mat-button type="button" class="js__conv" [class.js__tab--on]="isOpen(c, 'conversations')"
                        (click)="showConversations(c)">Conversations</button>
                <button mat-button type="button" class="js__rel" [class.js__tab--on]="isOpen(c, 'releve')"
                        (click)="showReleve(c)">Relevé</button>
                <button mat-button type="button" color="warn" class="js__erase" [disabled]="busy() || c.facts === 0"
                        (click)="eraseAll(c)">Effacer tout</button>
              </div>
              @if (isOpen(c, 'conversations')) {
                <ul class="js__facts js__convs">
                  @for (k of conversations(); track k.id) {
                    <li class="js__conv-item">
                      <div class="js__row">
                        <span class="js__value">{{ platformLabel(k.platform) }}{{ k.title ? ' · « ' + k.title + ' »' : '' }}</span>
                        <span class="js__meta">{{ k.depositedAt | date: 'd/MM HH:mm' }} · {{ k.lineCount }} message{{ k.lineCount > 1 ? 's' : '' }}</span>
                        <button mat-icon-button type="button" class="js__delete" [disabled]="busy()"
                                [attr.aria-label]="'Effacer la capture ' + (k.title || '')" (click)="deleteCapture(k)">
                          <mat-icon>delete</mat-icon>
                        </button>
                      </div>
                      @for (l of k.lines; track $index) {
                        <p class="js__line"><span class="js__who">{{ l.speaker }}</span>
                          <span class="js__meta">{{ l.approx ? 'vers ' : '' }}{{ l.saidAt | date: 'd/MM HH:mm' }}</span>
                          {{ l.text }}</p>
                      }
                    </li>
                  } @empty {
                    <li class="js__hint">Aucune conversation capturée lue pour ce client.</li>
                  }
                </ul>
              }
              @if (isOpen(c, 'releve')) {
                @if (releve(); as r) {
                  <div class="js__releve">
                    <div class="js__row">
                      <span class="js__meta js__setup">{{ r.setupAt ? 'Mis en place le ' + (r.setupAt | date: 'd/MM HH:mm') : 'Jamais mis en place' }}</span>
                      <button mat-stroked-button type="button" class="js__family js__setup-btn" [disabled]="busy() || !c.releveEnabled"
                              (click)="setupReleve(c)">{{ r.setupAt ? 'Refaire l’inventaire' : 'Mettre en place' }}</button>
                      <button mat-flat-button type="button" class="js__validate" [disabled]="busy() || !hasProposed(r)"
                              (click)="validateAll(c)">Tout valider</button>
                    </div>
                    <ul class="js__facts">
                      @for (p of r.probes; track p.id) {
                        <li class="js__fact">
                          <span class="js__key">{{ p.platform }}</span>
                          <span class="js__value">{{ p.name }} — {{ p.scope }}</span>
                          <span class="js__meta">{{ probeStatus(p) }}{{ p.lastRunAt ? ' · ' + (p.lastRunAt | date: 'd/MM HH:mm') : '' }}</span>
                        </li>
                      } @empty {
                        <li class="js__hint">Aucune sonde de relevé : « Mettre en place », poste allumé.</li>
                      }
                    </ul>
                  </div>
                } @else {
                  <p class="js__hint">Chargement…</p>
                }
              }
              @if (isOpen(c, 'facts')) {
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
    .js__spend { margin: 0; font-family: var(--cg-font-mono); font-size: 12px; opacity: .8; }
    .js__retention { display: flex; flex-wrap: wrap; gap: var(--cg-space-2); }
    .js__days { width: 136px; }
    .js__conv, .js__rel { color: var(--cg-cream); }
    .js__tab--on { text-decoration: underline; text-underline-offset: 4px; }
    .js__conv-item { display: flex; flex-direction: column; gap: var(--cg-space-1);
      border-top: 1px solid color-mix(in srgb, var(--cg-cream) 12%, transparent); padding-top: var(--cg-space-1); }
    .js__line { margin: 0; font-size: 13px; }
    .js__who { font-weight: 600; margin-right: var(--cg-space-1); }
    .js__releve { display: flex; flex-direction: column; gap: var(--cg-space-2); }
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
  readonly view = signal<StateView | null>(null);
  readonly conversations = signal<StateConversation[]>([]);
  readonly releve = signal<ReleveView | null>(null);
  readonly spend = signal<StateSpend | null>(null);
  readonly euro = eur;
  readonly platformLabel = capturePlatformLabel;
  readonly probeStatus = releveProbeStatus;
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly sourceLabel = stateSourceLabel;

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.client.spend().subscribe({ next: (s) => this.spend.set(s), error: () => this.spend.set(null) });
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
        if (this.isOpen(c, 'facts')) {
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

  /** Vrai si ce client montre cette vue. */
  isOpen(c: StateClient, v: StateView): boolean {
    return this.openHost() === c.hostId && this.view() === v;
  }

  /** Ouvre (ou referme) une vue d'un client ; rend vrai si elle s'ouvre. */
  private open(c: StateClient, v: StateView): boolean {
    if (this.isOpen(c, v)) {
      this.openHost.set(null);
      this.view.set(null);
      return false;
    }
    this.openHost.set(c.hostId);
    this.view.set(v);
    return true;
  }

  show(c: StateClient): void {
    if (this.open(c, 'facts')) {
      this.loadFacts(c.hostId);
    }
  }

  showConversations(c: StateClient): void {
    if (this.open(c, 'conversations')) {
      this.conversations.set([]);
      this.client.conversations(c.hostId).subscribe({
        next: (list) => this.conversations.set(list),
        error: () => this.snack.open('Impossible de lire les conversations.', undefined, { duration: 4000 }),
      });
    }
  }

  showReleve(c: StateClient): void {
    if (this.open(c, 'releve')) {
      this.loadReleve(c.hostId);
    }
  }

  private loadReleve(hostId: string): void {
    this.releve.set(null);
    this.client.releve(hostId).subscribe({
      next: (r) => this.releve.set(r),
      error: () => this.snack.open('Impossible de lire le relevé.', undefined, { duration: 4000 }),
    });
  }

  hasProposed(r: ReleveView): boolean {
    return r.probes.some((p) => p.proposed);
  }

  /** Une rétention de 1 à 365 jours ; hors bornes, rien n'est envoyé. */
  badDays(days: number): boolean {
    return !Number.isInteger(days) || days < 1 || days > 365;
  }

  retention(c: StateClient, field: 'factRetentionDays' | 'captureRetentionDays', raw: string): void {
    const days = Number(raw);
    if (this.badDays(days) || days === c[field]) {
      if (this.badDays(days)) {
        this.snack.open('La rétention va de 1 à 365 jours.', undefined, { duration: 4000 });
      }
      this.replace({ ...c });
      return;
    }
    this.save(c, field === 'factRetentionDays' ? { factRetentionDays: days } : { captureRetentionDays: days });
  }

  deleteCapture(k: StateConversation): void {
    this.busy.set(true);
    this.client.deleteCapture(k.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.conversations.set(this.conversations().filter((x) => x.id !== k.id));
      },
      error: () => {
        this.busy.set(false);
        this.snack.open('Effacement impossible.', undefined, { duration: 4000 });
      },
    });
  }

  /** Le tour d'inventaire unique (poste allumé et inactif) ; un refus dit pourquoi. */
  setupReleve(c: StateClient): void {
    this.busy.set(true);
    this.client.setupReleve(c.hostId).subscribe({
      next: () => {
        this.busy.set(false);
        this.snack.open('Inventaire lancé : Jarvis propose une sonde par famille, à valider ici.', undefined,
          { duration: 5000 });
        this.loadReleve(c.hostId);
      },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        const message = typeof e?.error?.message === 'string' ? e.error.message : 'Mise en place impossible.';
        this.snack.open(message, undefined, { duration: 5000 });
      },
    });
  }

  /** « Tout valider » : chaque sonde est testée puis activée ; le résultat est dit. */
  validateAll(c: StateClient): void {
    this.busy.set(true);
    this.client.validateAll(c.hostId).subscribe({
      next: (results) => {
        this.busy.set(false);
        const ok = results.filter((r) => r.activated).length;
        const ko = results.length - ok;
        this.snack.open(`${ok} sonde${ok > 1 ? 's' : ''} activée${ok > 1 ? 's' : ''}`
          + (ko ? ` · ${ko} en échec` : ''), undefined, { duration: 5000 });
        this.loadReleve(c.hostId);
      },
      error: () => {
        this.busy.set(false);
        this.snack.open('Validation impossible.', undefined, { duration: 4000 });
      },
    });
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
