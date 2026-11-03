import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import {
  SITUATION_IDLE_CHOICES,
  SITUATION_IDLE_KEY,
  Situation,
  SituationAttente,
  SituationEnvoi,
  SituationEnvoiGroup,
  groupEnvois,
  situationIdleThreshold,
} from '../../core/models/situation.models';
import { SituationService } from '../../core/services/situation.service';
import { MessageSendsService } from '../../core/services/message-sends.service';
import { humanFileSize } from '../../shared/http-error.util';
import { splitEssentialWithFallback } from './essential';

/** Le filtre de la carte (SF-225-03) : tout, un chantier, ou ce qui n'en a pas (« Au fil de l'eau »). */
export type SituationFilter = 'all' | 'loose' | number;

/**
 * **Le point de situation** (F-225 / SF-225-02, correctif SF-225-03) — « où on en était » : les chantiers et leur
 * étape, ce qui attend (qui, à qui, depuis quand) rangé par chantier, les mails et messages proposés — regroupés
 * par type, destinataire et objet, envoyés ou non, le geste [Marquer envoyé] annulable —, la dernière demande et
 * l'essentiel de la réponse, les pièces fournies, les suites. Un filtre par chantier resserre les trois listes qui
 * en dépendent. Une lecture, aucun tour. Rendu dans le cadre SOMBRE d'un panneau du fil : encres du terminal sombre
 * (DESIGN_SYSTEM §21), contrastes AA.
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
        <p class="sit__lede">Dernier échange {{ ago(hoursSince(s.lastActivityAt)) }}.</p>
      } @else {
        <p class="sit__lede">Rien encore dans ce terminal.</p>
      }

      @if (filters().length > 1) {
        <div class="sit__filters" role="group" aria-label="Filtrer par chantier">
          @for (f of filters(); track f.key) {
            <button type="button" class="sit__chip" [class.sit__chip--on]="isFilter(f.value)"
              [attr.aria-pressed]="isFilter(f.value)" [attr.data-filter]="f.key" (click)="filter.set(f.value)">
              {{ f.label }}
            </button>
          }
        </div>
      }

      @if (shownChantiers().length > 0) {
        <section class="sit__block" aria-label="Chantiers ouverts">
          <h4 class="sit__title">Chantiers</h4>
          <ul class="sit__list">
            @for (c of shownChantiers(); track c.number) {
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
          @if (sendError(); as message) {
            <p class="sit__note sit__note--error" role="alert">{{ message }}</p>
          }
          <ul class="sit__list">
            @for (g of pending(); track g.key) {
              <li class="sit__item sit__item--pending" [attr.data-send]="g.key">
                <span>{{ envoiLabel(g.latest) }} : <strong class="sit__warn">pas marqué envoyé</strong></span>
                @if (g.latest.viaJarvis) {
                  <span class="sit__muted"> · 🎙 via Jarvis</span>
                }
                @if (g.count > 1) {
                  <span class="sit__muted"> · proposé {{ g.count }} fois</span>
                }
                @if (g.latest.attenteStatus) {
                  <span class="sit__muted"> · attente {{ attenteLabel(g.latest.attenteStatus) }}</span>
                }
                <span class="sit__actions">
                  @if (!readOnly()) {
                    <button mat-stroked-button type="button" class="sit__mark" [disabled]="busy() === g.key"
                      (click)="mark(g)">Marquer envoyé</button>
                  }
                  <button mat-button type="button" class="sit__see" [attr.aria-expanded]="shown() === g.key"
                    (click)="toggle(g)">Voir</button>
                </span>
                @if (shown() === g.key) {
                  <p class="sit__excerpt">{{ g.latest.subject ? 'Objet : ' + g.latest.subject + ' — ' : '' }}{{ g.latest.excerpt || '(texte vide)' }}</p>
                }
              </li>
            }
            @for (g of done(); track g.key) {
              <li class="sit__item sit__item--sent" [attr.data-send]="g.key">
                <span class="sit__muted">{{ envoiLabel(g.latest) }} : envoyé{{ g.count > 1 ? ' · proposé ' + g.count + ' fois' : '' }}</span>
                @if (!readOnly()) {
                  <span class="sit__actions">
                    <button mat-button type="button" class="sit__undo" [disabled]="busy() === g.key"
                      [attr.aria-label]="'Annuler « envoyé » pour ' + envoiLabel(g.latest)"
                      (click)="undo(g)">Annuler</button>
                  </span>
                }
              </li>
            }
          </ul>
        </section>
      }

      @if (shownAttenteGroups().length > 0) {
        <section class="sit__block" aria-label="Ce qui attend">
          <h4 class="sit__title">Ce qui attend</h4>
          <!-- F-227 / SF-227-03 : groupé par chantier, puis « Au fil de l'eau » — comme le tableau du client. -->
          @for (g of shownAttenteGroups(); track g.key) {
            @if (shownAttenteGroups().length > 1 || g.chantier !== null) {
              <h5 class="sit__sub" [attr.data-group]="g.key">{{ g.label }}</h5>
            }
            <ul class="sit__list">
              @for (a of g.items; track a.id) {
                <li class="sit__item">
                  {{ a.description }}
                  <span class="sit__muted"> · {{ a.status === 'DEMANDE' ? 'demandée' : 'à faire' }}{{ a.to ? ' · ' + a.to : '' }}{{ a.since ? ' · depuis le ' + day(a.since) : '' }}</span>
                </li>
              }
            </ul>
          }
        </section>
      }

      @if (s.exchange; as x) {
        <section class="sit__block" aria-label="Dernier échange">
          <h4 class="sit__title">Dernier échange</h4>
          @if (x.request) {
            <p class="sit__item"><span class="sit__muted">Vous{{ x.viaJarvis ? ' (🎙 via Jarvis)' : '' }} : </span>{{ clip(x.request, 280) }}</p>
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
          d'inactivité (votre dernier message ou votre dernière visite sur cet appareil)
        </label>
      </p>
    }
    }
  `,
  styles: `
    :host { display: block; color: var(--cg-divider); font-size: 13px; }
    .sit__note, .sit__lede { margin: 0 0 var(--cg-space-2); color: var(--cg-terminal-ink-2); }
    .sit__note--error { color: var(--cg-orange-2); }
    .sit__filters { display: flex; flex-wrap: wrap; gap: var(--cg-space-1); margin: 0 0 var(--cg-space-2); }
    .sit__chip { padding: var(--cg-space-1) var(--cg-space-2); border: 1px solid var(--cg-terminal-ink-2); border-radius: 999px;
      background: transparent; color: var(--cg-divider); font: inherit; font-size: 12px; cursor: pointer; }
    .sit__chip:focus-visible { outline: 2px solid var(--cg-orange-2); outline-offset: 2px; }
    .sit__chip--on { border-color: var(--cg-orange-2); color: var(--cg-orange-2); font-weight: 600; }
    .sit__block { margin: 0 0 var(--cg-space-3); }
    .sit__title { margin: 0 0 var(--cg-space-1); font: 600 13px var(--cg-font-body); color: var(--cg-surface); }
    .sit__list { margin: 0; padding: 0; list-style: none; }
    .sit__sub { margin: var(--cg-space-1) 0; font: 600 12px var(--cg-font-body); color: var(--cg-terminal-ink-2); }
    .sit__item { margin: 0 0 var(--cg-space-1); overflow-wrap: anywhere; }
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
  private readonly sends = inject(MessageSendsService);

  readonly workspaceId = input<string | null>(null);
  readonly readOnly = input(false);
  /** Le chantier du fil affiché (volet du split) : le filtre s'y place d'emblée (SF-225-03). */
  readonly chantier = input<number | null>(null);
  /** La lecture déjà faite par la pop-up (F-225 / SF-225-04) : affichée telle quelle, sans seconde lecture. */
  readonly preloaded = input<Situation | null>(null);

  /**
   * Un envoi vient d'être marqué ou annulé ICI (SF-225-03) : le terminal relit ses envois marqués et le compte
   * des attentes — la bulle du fil dit la même chose que la carte.
   */
  readonly sendsChanged = output<void>();

  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly data = signal<Situation | null>(null);
  readonly shown = signal<string | null>(null);
  readonly busy = signal<string | null>(null);
  readonly sendError = signal<string | null>(null);
  readonly threshold = signal<number>(situationIdleThreshold());
  readonly choices = SITUATION_IDLE_CHOICES;
  readonly filter = signal<SituationFilter>('all');

  private readonly initialFilter = effect(() => {
    const chantier = this.chantier();
    untracked(() => this.filter.set(chantier ?? 'all'));
  });

  /**
   * « Ce qui attend », groupé (F-227 / SF-227-03) : un groupe par chantier ouvert qui a des attentes, dans l'ordre des
   * chantiers, puis « Au fil de l'eau » (sans chantier, ou rangée sous un chantier qui n'est plus ouvert).
   */
  readonly attenteGroups = computed<AttenteGroup[]>(() => {
    const s = this.data();
    if (!s) {
      return [];
    }
    const groups: AttenteGroup[] = [];
    const placed = new Set<string>();
    for (const c of s.chantiers) {
      const items = s.attentes.filter((a) => a.chantierNumber === c.number);
      if (items.length > 0) {
        items.forEach((a) => placed.add(a.id));
        groups.push({ key: `c${c.number}`, chantier: c.number,
          label: `Chantier ${c.number}${c.title ? ' — ' + c.title : ''}`, items });
      }
    }
    const loose = s.attentes.filter((a) => !placed.has(a.id));
    if (loose.length > 0) {
      groups.push({ key: 'loose', chantier: null, label: 'Au fil de l’eau', items: loose });
    }
    return groups;
  });

  /** Les numéros des chantiers ouverts : un mail proposé dans un chantier clos se range au fil de l'eau. */
  private readonly openNumbers = computed(() => new Set((this.data()?.chantiers ?? []).map((c) => c.number)));

  /** Les mails et messages, regroupés par type, destinataire et objet (SF-225-03). */
  readonly envoiGroups = computed(() => groupEnvois(this.data()?.envois ?? []));

  /** Les filtres offerts : Tout, chaque chantier ouvert, et « Au fil de l'eau » s'il y a de quoi. */
  readonly filters = computed<{ key: string; label: string; value: SituationFilter }[]>(() => {
    const s = this.data();
    if (!s || s.chantiers.length === 0) {
      return [];
    }
    const list: { key: string; label: string; value: SituationFilter }[] = [{ key: 'all', label: 'Tout', value: 'all' }];
    for (const c of s.chantiers) {
      list.push({ key: `c${c.number}`, label: `Ch.${c.number}${c.title ? ' · ' + c.title : ''}`, value: c.number });
    }
    const hasLoose = this.attenteGroups().some((g) => g.chantier === null)
      || this.envoiGroups().some((g) => this.isLoose(g));
    if (hasLoose) {
      list.push({ key: 'loose', label: 'Au fil de l’eau', value: 'loose' });
    }
    return list;
  });

  readonly shownChantiers = computed(() => {
    const f = this.filter();
    const chantiers = this.data()?.chantiers ?? [];
    return f === 'all' ? chantiers : f === 'loose' ? [] : chantiers.filter((c) => c.number === f);
  });

  readonly shownAttenteGroups = computed(() => {
    const f = this.filter();
    const groups = this.attenteGroups();
    return f === 'all' ? groups : groups.filter((g) => (f === 'loose' ? g.chantier === null : g.chantier === f));
  });

  private readonly shownEnvoiGroups = computed(() => {
    const f = this.filter();
    const groups = this.envoiGroups();
    if (f === 'all') {
      return groups;
    }
    return groups.filter((g) => (f === 'loose' ? this.isLoose(g) : g.chantiers.includes(f)));
  });

  readonly pending = computed(() => this.shownEnvoiGroups().filter((g) => !g.sent));
  readonly done = computed(() => this.shownEnvoiGroups().filter((g) => !!g.sent));

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
    const preloaded = this.preloaded();
    untracked(() => {
      if (preloaded) {
        this.data.set(preloaded);
        this.state.set('ready');
        return;
      }
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

  isFilter(value: SituationFilter): boolean {
    return this.filter() === value;
  }

  envoiLabel(e: SituationEnvoi): string {
    const what = e.kind === 'MAIL' ? 'Mail' : 'Message';
    return e.recipient ? `${what} à ${e.recipient}` : (e.subject ? `${what} « ${e.subject} »` : what);
  }

  attenteLabel(status: string): string {
    return status === 'DEMANDE' ? 'demandée' : status === 'A_FAIRE' ? 'à faire' : status === 'FAIT' ? 'faite' : 'close';
  }

  toggle(g: SituationEnvoiGroup): void {
    this.shown.set(this.shown() === g.key ? null : g.key);
  }

  /** [Marquer envoyé] : la proposition la plus récente du groupe, le geste F-211 — la carte suit la réponse. */
  mark(g: SituationEnvoiGroup): void {
    const id = this.workspaceId();
    if (!id || this.readOnly() || this.busy()) {
      return;
    }
    const target = g.latest;
    this.busy.set(g.key);
    this.sendError.set(null);
    this.sends.mark(id, target.messageId, target.blockIndex).subscribe({
      next: (send) => {
        this.busy.set(null);
        this.patchSent(target, send.sentAt ?? new Date().toISOString());
        this.sendsChanged.emit();
      },
      error: () => {
        this.busy.set(null);
        this.sendError.set('Impossible de marquer ce message comme envoyé.');
      },
    });
  }

  /** [Annuler] : retire la marque « envoyé » (geste F-211 existant) ; l'attente liée revient si elle n'a pas bougé. */
  undo(g: SituationEnvoiGroup): void {
    const id = this.workspaceId();
    const target = g.sent;
    if (!id || !target || this.readOnly() || this.busy()) {
      return;
    }
    this.busy.set(g.key);
    this.sendError.set(null);
    this.sends.cancel(id, target.messageId, target.blockIndex).subscribe({
      next: () => {
        this.busy.set(null);
        this.patchSent(target, null);
        this.sendsChanged.emit();
      },
      error: () => {
        this.busy.set(null);
        this.sendError.set('Impossible d’annuler.');
      },
    });
  }

  private patchSent(target: SituationEnvoi, sentAt: string | null): void {
    this.data.update((s) => s ? {
      ...s,
      envois: s.envois.map((x) => (x.messageId === target.messageId && x.blockIndex === target.blockIndex
        ? { ...x, sentAt } : x)),
    } : s);
  }

  /** Proposé au fil commun, ou dans un chantier qui n'est plus ouvert : « Au fil de l'eau ». */
  private isLoose(g: SituationEnvoiGroup): boolean {
    return g.chantiers.some((lane) => lane === null || !this.openNumbers().has(lane));
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

  hoursSince(iso: string | null): number | null {
    const at = iso ? Date.parse(iso) : NaN;
    return isNaN(at) ? null : Math.max(0, Math.floor((Date.now() - at) / 3_600_000));
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

/** Un groupe de « Ce qui attend » (F-227 / SF-227-03). */
interface AttenteGroup {
  key: string;
  chantier: number | null;
  label: string;
  items: SituationAttente[];
}
