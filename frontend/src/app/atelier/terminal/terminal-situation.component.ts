import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import {
  SITUATION_IDLE_CHOICES,
  SITUATION_IDLE_KEY,
  Situation,
  SituationEnvoi,
  situationIdleThreshold,
} from '../../core/models/situation.models';
import { SituationService } from '../../core/services/situation.service';
import { humanFileSize } from '../../shared/http-error.util';
import { splitEssentialWithFallback } from './essential';

/**
 * **Le point de situation** (F-225 / SF-225-02) — « où on en était » : les chantiers et leur étape, ce qui attend
 * (qui, à qui, depuis quand), les mails et messages proposés — envoyés ou non —, la dernière demande et l'essentiel
 * de la réponse, les pièces fournies, les suites. Une lecture, aucun tour. Rendu dans le cadre SOMBRE d'un panneau du
 * fil : encres du terminal sombre (DESIGN_SYSTEM §21), contrastes AA.
 */
@Component({
  selector: 'app-terminal-situation',
  imports: [MatButtonModule, MatIconModule],
  template: `
    @if (state() === 'loading') {
      <p class="sit__note">Lecture du point de situation…</p>
    } @else if (state() === 'error') {
      <p class="sit__note sit__note--error">Le point de situation n'a pas pu être lu.</p>
    } @else {
    @if (data(); as s) {
      @if (s.lastActivityAt) {
        <p class="sit__lede">Dernier échange {{ ago(s.idleHours) }}.</p>
      } @else {
        <p class="sit__lede">Rien encore dans ce terminal.</p>
      }

      @if (s.chantiers.length > 0) {
        <section class="sit__block" aria-label="Chantiers ouverts">
          <h4 class="sit__title">Chantiers</h4>
          <ul class="sit__list">
            @for (c of s.chantiers; track c.number) {
              <li class="sit__item">
                <strong>Chantier {{ c.number }}{{ c.title ? ' — ' + c.title : '' }}</strong>
                <span class="sit__muted"> · {{ c.phaseLabel }} · {{ c.done }}/{{ c.total }}{{ c.stale ? ' · en sommeil' : '' }}</span>
                @if (c.planAwaitingValidation) {
                  <div class="sit__await">Le plan attend votre validation.</div>
                } @else if (c.diagnosisPending) {
                  <div class="sit__await">Le diagnostic attend « Planifier ».</div>
                } @else if (c.nextStep) {
                  <div class="sit__muted">Ensuite : {{ c.nextRank }}. {{ c.nextStep }}</div>
                }
                @if (c.who) {
                  <div class="sit__muted">{{ c.who }}{{ c.whoDetail ? ' — ' + c.whoDetail : '' }}</div>
                }
              </li>
            }
          </ul>
        </section>
      }

      @if (pending().length > 0 || done().length > 0) {
        <section class="sit__block" aria-label="Mails et messages proposés">
          <h4 class="sit__title">Mails et messages (7 jours)</h4>
          <ul class="sit__list">
            @for (e of pending(); track key(e)) {
              <li class="sit__item sit__item--pending" [attr.data-send]="key(e)">
                <span>{{ envoiLabel(e) }} : <strong class="sit__warn">pas marqué envoyé</strong></span>
                @if (e.attenteStatus) {
                  <span class="sit__muted"> · attente {{ attenteLabel(e.attenteStatus) }}</span>
                }
                <span class="sit__actions">
                  @if (!readOnly()) {
                    <button mat-stroked-button type="button" class="sit__mark" (click)="mark(e)">Marquer envoyé</button>
                  }
                  <button mat-button type="button" class="sit__see" [attr.aria-expanded]="shown() === key(e)"
                    (click)="toggle(e)">Voir</button>
                </span>
                @if (shown() === key(e)) {
                  <p class="sit__excerpt">{{ e.subject ? 'Objet : ' + e.subject + ' — ' : '' }}{{ e.excerpt || '(texte vide)' }}</p>
                }
              </li>
            }
            @for (e of done(); track key(e)) {
              <li class="sit__item sit__muted">{{ envoiLabel(e) }} : envoyé</li>
            }
          </ul>
        </section>
      }

      @if (s.attentes.length > 0) {
        <section class="sit__block" aria-label="Ce qui attend">
          <h4 class="sit__title">Ce qui attend</h4>
          <ul class="sit__list">
            @for (a of s.attentes; track a.id) {
              <li class="sit__item">
                {{ a.description }}
                <span class="sit__muted"> · {{ a.status === 'DEMANDE' ? 'demandé' : 'à faire' }}{{ a.to ? ' · ' + a.to : '' }}{{ a.since ? ' · depuis le ' + day(a.since) : '' }}</span>
              </li>
            }
          </ul>
        </section>
      }

      @if (s.exchange; as x) {
        <section class="sit__block" aria-label="Dernier échange">
          <h4 class="sit__title">Dernier échange</h4>
          @if (x.request) {
            <p class="sit__item"><span class="sit__muted">Vous : </span>{{ clip(x.request, 280) }}</p>
          }
          @if (essential(); as answer) {
            <p class="sit__item"><span class="sit__muted">Réponse : </span>{{ clip(answer, 600) }}</p>
          }
        </section>
      }

      @if (s.pieces.length > 0) {
        <section class="sit__block" aria-label="Pièces fournies">
          <h4 class="sit__title">Pièces fournies</h4>
          <ul class="sit__list">
            @for (p of s.pieces.slice(0, 8); track p.id) {
              <li class="sit__item">{{ fileName(p.path) }} <span class="sit__muted">· {{ size(p.sizeBytes) }}</span></li>
            }
          </ul>
        </section>
      }

      @if (s.suites.length > 0) {
        <section class="sit__block" aria-label="Suites proposées">
          <h4 class="sit__title">Suites proposées</h4>
          <ul class="sit__list">
            @for (suite of s.suites; track suite.title) {
              <li class="sit__item">{{ suite.title }}</li>
            }
          </ul>
        </section>
      }

      <p class="sit__setting">
        <label>S'ouvre à l'entrée après
          <select class="sit__select" [value]="threshold()" (change)="setThreshold($any($event.target).value)">
            @for (choice of choices; track choice.hours) {
              <option [value]="choice.hours" [selected]="choice.hours === threshold()">{{ choice.label }}</option>
            }
          </select>
          d'inactivité
        </label>
      </p>
    }
    }
  `,
  styles: `
    :host { display: block; color: var(--cg-divider); font-size: 13px; }
    .sit__note, .sit__lede { margin: 0 0 var(--cg-space-2); color: var(--cg-terminal-ink-2); }
    .sit__note--error { color: var(--cg-orange-2); }
    .sit__block { margin: 0 0 var(--cg-space-3); }
    .sit__title { margin: 0 0 var(--cg-space-1); font: 600 13px var(--cg-font-body); color: var(--cg-surface); }
    .sit__list { margin: 0; padding: 0; list-style: none; }
    .sit__item { margin: 0 0 var(--cg-space-1); }
    .sit__muted { color: var(--cg-terminal-ink-2); }
    .sit__await, .sit__warn { color: var(--cg-orange-2); }
    .sit__actions { display: inline-flex; flex-wrap: wrap; gap: var(--cg-space-1); margin-left: var(--cg-space-2); }
    .sit__excerpt { margin: var(--cg-space-1) 0 0; padding-left: var(--cg-space-2); border-left: 2px solid var(--cg-terminal-ink-2);
      color: var(--cg-divider); white-space: pre-wrap; }
    .sit__setting { margin: var(--cg-space-2) 0 0; color: var(--cg-terminal-ink-2); font-size: 12px; }
    .sit__select { margin: 0 var(--cg-space-1); background: var(--cg-navy-2); color: var(--cg-divider);
      border: 1px solid var(--cg-terminal-ink-2); border-radius: 4px; font: inherit; }
  `,
})
export class TerminalSituationComponent {
  private readonly service = inject(SituationService);

  readonly workspaceId = input<string | null>(null);
  readonly readOnly = input(false);

  /** [Marquer envoyé] : le terminal fait le geste existant (F-211) — la carte se met à jour aussitôt. */
  readonly markSent = output<{ messageId: string; index: number }>();

  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly data = signal<Situation | null>(null);
  readonly shown = signal<string | null>(null);
  readonly threshold = signal<number>(situationIdleThreshold());
  readonly choices = SITUATION_IDLE_CHOICES;

  readonly pending = computed(() => (this.data()?.envois ?? []).filter((e) => !e.sentAt));
  readonly done = computed(() => (this.data()?.envois ?? []).filter((e) => !!e.sentAt));

  /** L'essentiel de la dernière réponse (F-126 : marqueurs, sinon premier paragraphe). */
  readonly essential = computed(() => {
    const reply = this.data()?.exchange?.reply;
    if (!reply) {
      return null;
    }
    const split = splitEssentialWithFallback(reply);
    return split.essential ?? split.detail;
  });

  private readonly load = effect(() => {
    const id = this.workspaceId();
    untracked(() => {
      if (!id) {
        this.state.set('error');
        return;
      }
      this.state.set('loading');
      this.service.situation(id).subscribe({
        next: (s) => {
          this.data.set(s);
          this.state.set('ready');
        },
        error: () => this.state.set('error'),
      });
    });
  });

  key(e: SituationEnvoi): string {
    return `${e.messageId}#${e.blockIndex}`;
  }

  envoiLabel(e: SituationEnvoi): string {
    const what = e.kind === 'MAIL' ? 'Mail' : 'Message';
    return e.recipient ? `${what} à ${e.recipient}` : (e.subject ? `${what} « ${e.subject} »` : what);
  }

  attenteLabel(status: string): string {
    return status === 'DEMANDE' ? 'demandée' : status === 'A_FAIRE' ? 'à faire' : status === 'FAIT' ? 'faite' : 'close';
  }

  toggle(e: SituationEnvoi): void {
    this.shown.set(this.shown() === this.key(e) ? null : this.key(e));
  }

  mark(e: SituationEnvoi): void {
    this.markSent.emit({ messageId: e.messageId, index: e.blockIndex });
    const now = new Date().toISOString();
    this.data.update((s) => s ? {
      ...s,
      envois: s.envois.map((x) => (this.key(x) === this.key(e) ? { ...x, sentAt: now } : x)),
    } : s);
  }

  setThreshold(raw: string): void {
    const hours = Number(raw);
    this.threshold.set(hours);
    try {
      localStorage.setItem(SITUATION_IDLE_KEY, String(hours));
    } catch {
      // stockage indisponible : le réglage vaut pour la page
    }
  }

  ago(hours: number | null): string {
    if (hours === null) {
      return '';
    }
    if (hours < 1) {
      return "il y a moins d'une heure";
    }
    return hours < 48 ? `il y a ${hours} h` : `il y a ${Math.floor(hours / 24)} jours`;
  }

  day(iso: string): string {
    const d = new Date(iso);
    return isNaN(d.getTime()) ? iso : d.toLocaleDateString('fr-FR');
  }

  clip(text: string, max: number): string {
    const flat = text.replace(/\s+/g, ' ').trim();
    return flat.length <= max ? flat : `${flat.slice(0, max - 1)}…`;
  }

  fileName(path: string): string {
    return path.split('/').pop() ?? path;
  }

  size(bytes: number): string {
    return humanFileSize(bytes);
  }
}
