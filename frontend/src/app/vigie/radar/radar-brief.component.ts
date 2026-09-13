import { Component, DestroyRef, OnChanges, computed, inject, input, output, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

import { RadarBrief, RadarCoverageItem } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { httpErrorMessage } from '../../shared/http-error.util';
import {
  briefDay,
  coverageHeading,
  coverageKindIcon,
  coverageSourceIcon,
  coverageStatusLabel,
  itemGestures,
  ruleLabel,
} from './radar-brief';

/** Relecture du résumé tant qu'une synchro tourne. */
export const BRIEF_RUNNING_REFRESH_MS = 10_000;

/**
 * **Le résumé du matin** d'un client (F-102 / SF-102-01) : ce qui a bougé depuis hier, les compteurs, et
 * ce que la dernière synchro a lu — *Synchroniser maintenant*, la progression, *Annuler*, et les gestes
 * sur les manques (*ignorer ce fil*, *lire ce canal*).
 *
 * <p><b>Il dit ce qu'il n'a pas lu</b> (cadrage §4.4) : l'avertissement de couverture passe **avant** les
 * phrases, et les manques sont listés sans repli.</p>
 */
@Component({
  selector: 'app-radar-brief',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './radar-brief.component.html',
  styleUrl: './radar-brief.component.scss',
})
export class RadarBriefComponent implements OnChanges {
  private readonly radar = inject(RadarService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);

  readonly hostId = input.required<string>();
  /** Le résumé relu : l'onglet en tire ses compteurs et relit ses colonnes. */
  readonly briefChange = output<RadarBrief>();

  readonly brief = signal<RadarBrief | null>(null);
  readonly error = signal(false);
  readonly busy = signal(false);
  /** Le manque dont une règle est en cours de pose ou de retrait. */
  readonly ruleBusy = signal<string | null>(null);

  readonly day = briefDay();
  readonly sourceIcon = coverageSourceIcon;
  readonly kindIcon = coverageKindIcon;
  readonly statusLabel = coverageStatusLabel;
  readonly gestures = itemGestures;
  readonly ruleLabel = ruleLabel;

  readonly heading = computed(() => coverageHeading(this.brief()?.lastSync ?? null));
  readonly items = computed<RadarCoverageItem[]>(() => this.brief()?.lastSync?.summary?.items ?? []);

  /** Règles posées depuis cet écran : leur identifiant, pour les retirer sans relire la liste. */
  private readonly postedRules = new Map<string, string>();
  private timer: ReturnType<typeof setTimeout> | null = null;
  private loadedFor: string | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => this.clearTimer());
  }

  ngOnChanges(): void {
    if (this.loadedFor !== this.hostId()) {
      this.loadedFor = this.hostId();
      this.brief.set(null);
      this.error.set(false);
      this.postedRules.clear();
      this.load();
    }
  }

  /** Relit le résumé. */
  load(): void {
    const hostId = this.hostId();
    this.clearTimer();
    this.radar.brief(hostId).subscribe({
      next: (brief) => {
        if (hostId !== this.hostId()) {
          return;
        }
        this.brief.set(brief);
        this.error.set(false);
        this.briefChange.emit(brief);
        if (brief.running) {
          this.timer = setTimeout(() => this.refreshWhileRunning(), BRIEF_RUNNING_REFRESH_MS);
        }
      },
      error: () => {
        if (hostId === this.hostId()) {
          this.error.set(true);
        }
      },
    });
  }

  syncNow(): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.radar.syncNow(this.hostId()).subscribe({
      next: () => {
        this.busy.set(false);
        this.snackBar.open('Synchro lancée : elle tourne sur la machine du client.', 'Fermer', { duration: 4000 });
        this.load();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.fail(err, "La synchro n'a pas pu partir.");
        this.load();
      },
    });
  }

  cancel(): void {
    const running = this.brief()?.running;
    if (!running || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.radar.cancelSync(this.hostId(), running.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.snackBar.open('Synchro annulée : ce qui avait été lu est conservé.', 'Fermer', { duration: 4000 });
        this.load();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.fail(err, "La synchro n'a pas pu être annulée.");
        this.load();
      },
    });
  }

  addRule(item: RadarCoverageItem, rule: 'IGNORE' | 'READ_CHANNEL'): void {
    if (this.ruleBusy() !== null) {
      return;
    }
    this.ruleBusy.set(item.ref);
    this.radar.addThreadRule(this.hostId(), item.ref, rule, item.label || null).subscribe({
      next: (posted) => {
        this.ruleBusy.set(null);
        this.postedRules.set(item.ref, posted.id);
        this.setItemRule(item.ref, posted.rule);
      },
      error: (err: unknown) => {
        this.ruleBusy.set(null);
        this.fail(err, "La règle n'a pas pu être posée. Rien n'a changé.");
      },
    });
  }

  removeRule(item: RadarCoverageItem): void {
    if (this.ruleBusy() !== null) {
      return;
    }
    const hostId = this.hostId();
    this.ruleBusy.set(item.ref);
    const known = this.postedRules.get(item.ref);
    const done = () => {
      this.ruleBusy.set(null);
      this.postedRules.delete(item.ref);
      this.setItemRule(item.ref, null);
    };
    const failed = (err: unknown) => {
      this.ruleBusy.set(null);
      this.fail(err, "La règle n'a pas pu être retirée. Rien n'a changé.");
    };
    if (known) {
      this.radar.removeThreadRule(hostId, known).subscribe({ next: done, error: failed });
      return;
    }
    this.radar.threadRules(hostId).subscribe({
      next: (rules) => {
        const match = rules.find((rule) => rule.conversationRef === item.ref);
        if (!match) {
          done();
          return;
        }
        this.radar.removeThreadRule(hostId, match.id).subscribe({ next: done, error: failed });
      },
      error: failed,
    });
  }

  gestureLabel(gesture: 'IGNORE' | 'READ_CHANNEL'): string {
    return gesture === 'IGNORE' ? 'Ignorer ce fil' : 'Lire ce canal';
  }

  plural(count: number, one: string, many: string): string {
    return count > 1 ? many : one;
  }

  private setItemRule(ref: string, rule: string | null): void {
    const brief = this.brief();
    const sync = brief?.lastSync;
    if (!brief || !sync?.summary) {
      return;
    }
    const items = sync.summary.items.map((item) => (item.ref === ref ? { ...item, rule } : item));
    this.brief.set({ ...brief, lastSync: { ...sync, summary: { ...sync.summary, items } } });
  }

  private refreshWhileRunning(): void {
    this.timer = null;
    this.load();
  }

  private clearTimer(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private fail(err: unknown, fallback: string): void {
    this.snackBar.open(httpErrorMessage(err, fallback), 'Fermer', { duration: 6000, panelClass: 'snack-error' });
  }
}
