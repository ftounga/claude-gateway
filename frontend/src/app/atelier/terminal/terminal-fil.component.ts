import { Component, DestroyRef, computed, inject, input, signal, effect, untracked } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';

import { FilSubAgent, FilTask, PosteTurns } from '../../core/models/terminal-fil.models';
import { TerminalFilService } from '../../core/services/terminal-fil.service';
import { FinishSoundService } from '../../core/services/finish-sound.service';

/** Relecture après chaque réponse, onglet visible. */
export const FIL_POLL_MS = 5_000;
/** Onglet caché : on attend plus longtemps, sans appel. */
export const FIL_HIDDEN_POLL_MS = 15_000;
/** Le bandeau de fin s'efface seul après ce délai. */
export const FIL_BANNER_MS = 30_000;
/** Mini-barres de l'en-tête replié. */
const MAX_MINI_BARS = 6;

/** L'outil courant, dit en français. */
const TOOL_LABELS: Record<string, string> = {
  read: 'Lecture',
  write: 'Écriture',
  list: 'Liste',
  search: 'Recherche',
  bash: 'Commande',
  explore: 'Exploration',
  task: 'Sous-tâche',
  recall: 'Rappel',
  video: 'Vidéo',
};

export function toolLabel(tool: string | null | undefined): string | null {
  if (!tool) {
    return null;
  }
  return TOOL_LABELS[tool] ?? tool;
}

/** Les trois compteurs de l'en-tête. */
export function filCounts(tasks: FilTask[]): { waiting: number; running: number; done: number } {
  let waiting = 0;
  let running = 0;
  let done = 0;
  for (const t of tasks) {
    if (t.state === 'waiting') {
      waiting++;
    } else if (t.state === 'running') {
      running++;
    } else {
      done++;
    }
  }
  return { waiting, running, done };
}

/** « 45 s », « 3 min », « 1 h 05 ». */
export function durationLabel(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) {
    return `${s} s`;
  }
  const m = Math.floor(s / 60);
  if (m < 60) {
    return `${m} min`;
  }
  const h = Math.floor(m / 60);
  return `${h} h ${String(m % 60).padStart(2, '0')}`;
}

/** Durée d'une tâche, sur l'horloge serveur : jusqu'à la fin, ou jusqu'à maintenant. */
export function taskDuration(task: { startedAt: number; endedAt?: number | null }, serverNow: number): string {
  return durationLabel((task.endedAt ?? serverNow) - task.startedAt);
}

/** Largeur de la barre, en pourcentage ; `null` = barre indéterminée (pas de plan). */
export function barPercent(task: FilTask): number | null {
  if (task.state === 'done' || task.state === 'failed' || task.state === 'interrupted') {
    return 100;
  }
  if (!task.planTotal) {
    return null;
  }
  return Math.round((100 * Math.min(task.planDone, task.planTotal)) / task.planTotal);
}

/** Ce que la colonne « état » dit de la tâche. */
export function stateLabel(task: FilTask): string {
  switch (task.state) {
    case 'waiting':
      return task.waiting === 'question' ? 'attend votre réponse' : 'attend votre accord';
    case 'done':
      return '✓ terminé';
    case 'failed':
      return 'échec';
    case 'interrupted':
      return 'interrompu';
    default:
      return toolLabel(task.tool) ?? 'en cours';
  }
}

/** Le verbe du bandeau de fin. */
export function finishVerb(task: FilTask): string {
  switch (task.state) {
    case 'failed':
      return "s'est arrêté";
    case 'interrupted':
      return 'a été interrompu';
    default:
      return 'est terminé';
  }
}

/**
 * Les tâches qui viennent de finir **ailleurs** : finies, pas encore connues comme finies, et pas celle
 * du terminal courant. `known` est complété au passage.
 */
export function newlyFinished(tasks: FilTask[], known: Set<string>): FilTask[] {
  const fresh: FilTask[] = [];
  for (const t of tasks) {
    if (!isFinished(t) || known.has(t.turnId)) {
      continue;
    }
    known.add(t.turnId);
    if (!t.here) {
      fresh.push(t);
    }
  }
  return fresh;
}

function isFinished(task: FilTask): boolean {
  return task.state === 'done' || task.state === 'failed' || task.state === 'interrupted';
}

/**
 * **La bande des tâches** (F-191 / SF-191-02) : au-dessus de la saisie, tout ce qui tourne sur le poste
 * — les sujets et la racine. Repliée par défaut, absente quand rien ne tourne ni n'a fini depuis dix
 * minutes. Un clic ouvre le sujet ; rien ne s'interrompt d'ici.
 *
 * <p>Lecture <b>après</b> chaque réponse (jamais deux en vol) : aucune minuterie n'existe tant que la
 * première réponse n'est pas arrivée.</p>
 */
@Component({
  selector: 'app-terminal-fil',
  template: `
    @if (banner(); as b) {
      <div class="fin" role="status">
        <span class="fin__ck" [class.fin__ck--ko]="b.state !== 'done'" aria-hidden="true">{{ b.state === 'done' ? '✓' : '!' }}</span>
        <span class="fin__text"><b>{{ b.workspaceName }}</b> · « {{ b.demand || '…' }} » {{ verb(b) }} · {{ duration(b) }}</span>
        <button type="button" class="fin__open" (click)="openFinished(b)">Ouvrir</button>
        <button type="button" class="fin__close" aria-label="Fermer" (click)="closeBanner()">✕</button>
      </div>
    }
    @if (tasks().length) {
      <section class="fil" [attr.data-open]="open()" aria-label="Tâches en cours du poste">
        <button type="button" class="fil__head" (click)="toggle()" [attr.aria-expanded]="open()">
          <span class="fil__count">
            @if (counts().waiting) {
              <span class="pill pill--wait"><span class="dot"></span>{{ counts().waiting }} {{ counts().waiting > 1 ? 'attendent' : 'attend' }} votre accord</span>
            }
            @if (counts().running) {
              <span class="pill"><span class="dot"></span>{{ counts().running }} en cours</span>
            }
            @if (counts().done) {
              <span class="pill pill--ok">✓ {{ counts().done }} {{ counts().done > 1 ? 'terminées' : 'terminée' }}</span>
            }
          </span>
          @if (!open()) {
            <span class="mini" aria-hidden="true">
              @for (t of miniBars(); track t.turnId) {
                <span [class.ok]="finished(t)" [class.wait]="t.state === 'waiting'"><b [style.width.%]="bar(t) ?? 40"></b></span>
              }
            </span>
          }
          <span class="chev" aria-hidden="true">▾</span>
        </button>
        @if (open()) {
          <ul class="rows">
            @for (t of tasks(); track t.turnId) {
              <li>
                <button type="button" class="row" [class.row--here]="t.here" [class.row--static]="t.here"
                  (click)="openTask(t)"
                  [attr.aria-label]="t.workspaceName + ' : ' + state(t) + (t.here ? ' (ce terminal)' : '. Ouvrir le sujet.')">
                  <span class="subj">
                    <b>{{ t.workspaceName }}</b>
                    <small>{{ t.here ? 'ici' : (t.root ? 'racine' : 'sujet') }}</small>
                  </span>
                  <span class="task">
                    <span class="t">{{ t.demand || '…' }}</span>
                    <span class="bar" [class.ok]="finished(t)" [class.wait]="t.state === 'waiting'" [class.indet]="bar(t) === null">
                      <b [style.width.%]="bar(t)"></b>
                    </span>
                  </span>
                  <span class="state">
                    @if (t.state === 'running' && t.tool) {
                      <span class="tool">{{ state(t) }}</span>
                    } @else {
                      <span [class.ok]="t.state === 'done'" [class.wait]="t.state === 'waiting'">{{ state(t) }}</span>
                    }
                    @if (t.planTotal) {
                      <span class="steps">{{ t.planDone }}/{{ t.planTotal }}</span>
                    }
                  </span>
                  <span class="time">{{ duration(t) }}</span>
                  @if (t.subagents.length) {
                    <span class="subs">
                      @for (s of t.subagents; track $index) {
                        <span class="sub">
                          <span aria-hidden="true">↳</span>
                          <span class="sub__label">{{ s.label || s.kind }}</span>
                          <span class="bar" [class.ok]="s.state === 'done'" [class.indet]="s.state === 'running'"><b [style.width.%]="s.state === 'done' ? 100 : null"></b></span>
                          <em>{{ s.state === 'done' ? 'terminé' : subDuration(s) }}</em>
                        </span>
                      }
                    </span>
                  }
                </button>
              </li>
            }
          </ul>
        }
      </section>
    }
  `,
  styles: `
    :host { display: block; }
    .fin {
      display: flex; align-items: center; gap: 8px; margin: 0 0 8px; padding: 8px 12px;
      background: var(--cg-navy-2); border: 1px solid color-mix(in srgb, var(--cg-success) 40%, transparent);
      border-radius: 8px; color: #fff; font: 13px 'Inter', sans-serif;
    }
    .fin__ck { color: #5FD08F; font-weight: 700; }
    .fin__ck--ko { color: #F9A825; }
    .fin__text { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--cg-divider); }
    .fin__text b { color: #fff; font-weight: 600; }
    .fin__open, .fin__close { background: none; border: 0; cursor: pointer; font: inherit; padding: 0 4px; }
    .fin__open { color: var(--cg-orange-2, #F0954F); font-weight: 600; }
    .fin__close { color: #9FB0C4; }
    .fin__open:focus-visible, .fin__close:focus-visible { outline: 2px solid var(--cg-accent); outline-offset: 2px; }
    .fil {
      margin: 0 0 8px;
      background: var(--cg-navy-2);
      border: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent);
      border-radius: 8px;
      overflow: hidden;
      color: #fff;
      font-family: 'Inter', sans-serif;
    }
    .fil__head {
      width: 100%; display: flex; align-items: center; gap: 8px;
      padding: 8px 12px; background: none; border: 0; color: inherit;
      font: 500 13px 'Inter', sans-serif; cursor: pointer; text-align: left;
    }
    .fil__head:focus-visible, .row:focus-visible { outline: 2px solid var(--cg-accent); outline-offset: -2px; }
    .fil__count { display: inline-flex; gap: 8px; flex-wrap: wrap; }
    .pill {
      display: inline-flex; align-items: center; gap: 4px; font-size: 12px; padding: 2px 8px;
      border-radius: 999px; background: color-mix(in srgb, var(--cg-accent) 14%, transparent);
      color: var(--cg-accent); font-variant-numeric: tabular-nums;
    }
    .pill--ok { background: color-mix(in srgb, var(--cg-success) 16%, transparent); color: #5FD08F; }
    .pill--wait { background: color-mix(in srgb, #F9A825 16%, transparent); color: #F9A825; }
    .dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
    .chev { margin-left: auto; color: var(--cg-divider); transition: transform .2s; font-size: 12px; }
    .fil[data-open="true"] .chev { transform: rotate(180deg); }
    .mini { display: flex; gap: 4px; margin-left: 4px; }
    .mini span { width: 28px; height: 4px; border-radius: 2px; background: color-mix(in srgb, var(--cg-divider) 15%, transparent); overflow: hidden; position: relative; }
    .mini span b { position: absolute; inset: 0 auto 0 0; background: var(--cg-accent); border-radius: 2px; }
    .mini span.ok b { background: var(--cg-success); }
    .mini span.wait b { background: #F9A825; }
    .rows { list-style: none; margin: 0; padding: 0; border-top: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent); max-height: 40vh; overflow-y: auto; }
    .rows li + li { border-top: 1px solid color-mix(in srgb, var(--cg-divider) 14%, transparent); }
    .row {
      width: 100%; display: grid; grid-template-columns: 148px minmax(0, 1fr) 168px 64px; gap: 12px;
      align-items: center; padding: 8px 12px; background: none; border: 0; color: inherit;
      font: 13px 'Inter', sans-serif; text-align: left; cursor: pointer;
    }
    .row:hover { background: rgba(255, 255, 255, .03); }
    .row--static { cursor: default; }
    .row--here { box-shadow: inset 3px 0 0 var(--cg-accent); }
    .subj { display: grid; gap: 0; min-width: 0; }
    .subj b { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .subj small { color: #9FB0C4; font-size: 11px; }
    .task { min-width: 0; display: grid; gap: 4px; }
    .t { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--cg-divider); }
    .bar { display: block; height: 4px; border-radius: 2px; background: color-mix(in srgb, var(--cg-divider) 12%, transparent); overflow: hidden; position: relative; }
    .bar b { position: absolute; inset: 0 auto 0 0; border-radius: 2px; background: var(--cg-accent); transition: width .6s ease; }
    .bar.ok b { background: var(--cg-success); }
    .bar.wait b { background: #F9A825; }
    .bar.indet b { width: 30%; animation: fil-slide 1.6s ease-in-out infinite; }
    @keyframes fil-slide { 0% { left: -30%; } 100% { left: 100%; } }
    .state { display: flex; align-items: center; gap: 8px; font-family: 'JetBrains Mono', monospace; font-size: 11.5px; color: #9FB0C4; min-width: 0; }
    .state .tool { color: var(--cg-divider); padding: 0 4px; border-radius: 4px; background: rgba(255, 255, 255, .06); white-space: nowrap; }
    .state .ok { color: #5FD08F; }
    .state .wait { color: #F9A825; }
    .time { font-family: 'JetBrains Mono', monospace; font-size: 12px; color: #9FB0C4; text-align: right; font-variant-numeric: tabular-nums; }
    .subs { grid-column: 2 / -1; display: grid; gap: 4px; }
    .sub { display: grid; grid-template-columns: 12px minmax(0, 1fr) 88px 52px; gap: 8px; align-items: center; font-size: 12px; color: #9FB0C4; }
    .sub__label { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .sub em { font-style: normal; font-family: 'JetBrains Mono', monospace; font-size: 11px; text-align: right; }
    @media (max-width: 640px) {
      .row { grid-template-columns: minmax(0, 1fr) auto; gap: 4px 8px; }
      .row .task { grid-column: 1 / -1; grid-row: 2; }
      .row .state { grid-column: 1 / -1; grid-row: 3; }
      .row .time { grid-column: 2; grid-row: 1; }
      .subs { grid-column: 1 / -1; }
      .sub { grid-template-columns: 12px minmax(0, 1fr) 48px; }
      .sub .bar { display: none; }
      .mini { display: none; }
    }
    @media (prefers-reduced-motion: reduce) {
      .bar.indet b { animation: none; left: 0; width: 100%; opacity: .5; }
      .bar b, .chev { transition: none; }
    }
  `,
})
export class TerminalFilComponent {
  /** Le terminal courant : la bande montre tout son poste. */
  readonly workspaceId = input<string | null>(null);

  private readonly service = inject(TerminalFilService);
  private readonly router = inject(Router);
  private readonly sound = inject(FinishSoundService);

  readonly tasks = signal<FilTask[]>([]);
  readonly open = signal(false);
  /** Instant serveur de la dernière lecture, et écart avec l'horloge locale à cet instant. */
  private readonly serverTime = signal(0);
  readonly counts = computed(() => filCounts(this.tasks()));
  readonly miniBars = computed(() => this.tasks().slice(0, MAX_MINI_BARS));
  /** La dernière tâche finie ailleurs, annoncée par le bandeau (F-191 / SF-191-03). */
  readonly banner = signal<FilTask | null>(null);
  /** Tours déjà connus comme finis : on n'annonce une fin qu'une fois. */
  private readonly knownFinished = new Set<string>();
  private firstRead = true;
  private bannerTimer?: ReturnType<typeof setTimeout>;

  private pending?: Subscription;
  private timer?: ReturnType<typeof setTimeout>;
  private stopped = false;

  constructor() {
    effect(() => {
      const id = this.workspaceId();
      untracked(() => this.restart(id));
    });
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  toggle(): void {
    this.open.update((v) => !v);
  }

  openTask(task: FilTask): void {
    if (task.here) {
      return;
    }
    void this.router.navigate(['/atelier', task.workspaceId]);
  }

  openFinished(task: FilTask): void {
    this.closeBanner();
    void this.router.navigate(['/atelier', task.workspaceId]);
  }

  closeBanner(): void {
    this.banner.set(null);
    if (this.bannerTimer !== undefined) {
      clearTimeout(this.bannerTimer);
      this.bannerTimer = undefined;
    }
  }

  verb(task: FilTask): string {
    return finishVerb(task);
  }

  finished(task: FilTask): boolean {
    return isFinished(task);
  }

  bar(task: FilTask): number | null {
    return barPercent(task);
  }

  state(task: FilTask): string {
    return stateLabel(task);
  }

  duration(task: FilTask): string {
    return taskDuration(task, this.serverTime());
  }

  subDuration(sub: FilSubAgent): string {
    return durationLabel(this.serverTime() - sub.startedAt);
  }

  /** Nouveau terminal : on repart de zéro, lecture immédiate. */
  private restart(id: string | null): void {
    this.stop();
    this.stopped = false;
    this.tasks.set([]);
    this.open.set(false);
    this.closeBanner();
    this.knownFinished.clear();
    this.firstRead = true;
    if (id) {
      this.fetch(id);
    }
  }

  private fetch(id: string): void {
    this.pending = this.service.turns(id).subscribe({
      next: (res: PosteTurns) => {
        this.serverTime.set(res?.serverTime ?? Date.now());
        const turns = Array.isArray(res?.turns) ? res.turns : [];
        this.tasks.set(turns);
        this.announce(turns);
        this.schedule(id);
      },
      error: (err: unknown) => {
        if (err instanceof HttpErrorResponse && (err.status === 403 || err.status === 404)) {
          // Terminal hors de portée : on se tait, et l'on ne relit plus.
          this.tasks.set([]);
          return;
        }
        // Panne passagère : dernier état connu, relecture au cycle suivant.
        this.schedule(id);
      },
    });
  }

  /** Bandeau et son pour ce qui vient de finir ailleurs ; la première lecture ne dit rien. */
  private announce(turns: FilTask[]): void {
    const fresh = newlyFinished(turns, this.knownFinished);
    if (this.firstRead) {
      this.firstRead = false;
      return;
    }
    if (!fresh.length) {
      return;
    }
    this.closeBanner();
    this.banner.set(fresh[0]);
    this.bannerTimer = setTimeout(() => {
      this.bannerTimer = undefined;
      this.banner.set(null);
    }, FIL_BANNER_MS);
    this.sound.playIfEnabled();
  }

  private schedule(id: string): void {
    if (this.stopped) {
      return;
    }
    const hidden = typeof document !== 'undefined' && document.visibilityState === 'hidden';
    this.timer = setTimeout(() => {
      this.timer = undefined;
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
        this.schedule(id);
        return;
      }
      this.fetch(id);
    }, hidden ? FIL_HIDDEN_POLL_MS : FIL_POLL_MS);
  }

  private stop(): void {
    this.stopped = true;
    this.pending?.unsubscribe();
    this.pending = undefined;
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
    if (this.bannerTimer !== undefined) {
      clearTimeout(this.bannerTimer);
      this.bannerTimer = undefined;
    }
  }
}
