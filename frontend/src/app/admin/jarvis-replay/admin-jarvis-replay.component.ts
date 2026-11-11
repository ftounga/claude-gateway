import { DecimalPipe } from '@angular/common';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AdminJarvisReplayService, Replay, ReplaySession, ReplayTurn, ReplayVerdict } from './admin-jarvis-replay.service';

/** Le libellé français d'un verdict (cadrage F-255 §3). */
export function verdictLabel(code: string): string {
  switch (code) {
    case 'ENTENDU': return 'Entendu';
    case 'PERDU': return 'Perdu';
    case 'REPETE': return 'Répété';
    case 'LENT': return 'Lent';
    case 'MAUVAIS_SUJET': return 'Mauvais sujet';
    case 'MONTRE': return 'Montré';
    case 'VOICI_SANS_AFFICHAGE': return '« Voici » sans affichage';
    case 'DELEGATION_MUETTE': return 'Délégation muette';
    case 'REPLI_TRANSCRIPTION': return 'Repli de transcription';
    case 'MESURE_ABSENTE': return 'Mesure absente';
    default: return code;
  }
}

/** Le ton d'un verdict : bien (vert), à surveiller (ambre), défaut (rouge) — badges du design system §5. */
export function verdictTone(code: string): 'ok' | 'warn' | 'bad' {
  switch (code) {
    case 'ENTENDU':
    case 'MONTRE':
      return 'ok';
    case 'PERDU':
    case 'MAUVAIS_SUJET':
    case 'VOICI_SANS_AFFICHAGE':
    case 'DELEGATION_MUETTE':
      return 'bad';
    default:
      return 'warn';
  }
}

/** Des millisecondes en secondes françaises (« 4,6 s »), ou « — ». */
export function seconds(ms: number | null | undefined): string {
  return ms === null || ms === undefined ? '—' : `${(ms / 1000).toFixed(1).replace('.', ',')} s`;
}

/** Un taux (0..1) en pourcentage entier, ou « — ». */
export function percent(rate: number | null | undefined): string {
  return rate === null || rate === undefined ? '—' : `${Math.round(rate * 100)} %`;
}

const PARIS_DATE = new Intl.DateTimeFormat('fr-FR', {
  timeZone: 'Europe/Paris', weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
});
const PARIS_TIME = new Intl.DateTimeFormat('fr-FR', {
  timeZone: 'Europe/Paris', hour: '2-digit', minute: '2-digit', second: '2-digit',
});

/** Une date ISO en heure de Paris (« lun. 9 nov., 17:11 »). */
export function parisDate(iso: string): string {
  return PARIS_DATE.format(new Date(iso));
}

/** Une heure ISO en heure de Paris (« 17:24:03 »). */
export function parisTime(iso: string): string {
  return PARIS_TIME.format(new Date(iso));
}

/**
 * Section **Rejeu des séances Jarvis** de l'administration (F-255 / SF-255-03).
 *
 * <p>Ce qu'elle sert : savoir, après une séance, <b>ce qui s'est vraiment passé</b> tour par tour — ce qui a été dit
 * (réécoutable quand l'énoncé a été gardé), compris, répondu, délégué, montré — et <b>comparer</b> les séances entre
 * elles pour voir si un correctif améliore vraiment. Rien n'est jugé ici : les verdicts et le bilan sont des règles
 * serveur (sans IA), rendues telles quelles. Ce n'est pas l'écran de Jarvis : une page d'administration.</p>
 */
@Component({
  selector: 'app-admin-jarvis-replay',
  imports: [DecimalPipe, MatButtonModule, MatCardModule, MatIconModule],
  template: `
    <mat-card class="replay">
      <mat-card-header>
        <mat-card-title>Rejeu des séances Jarvis</mat-card-title>
        <mat-card-subtitle>
          Chaque séance des 7 derniers jours, tour par tour : ce que vous avez dit, ce que Jarvis a compris, répondu et
          montré, le temps de chaque étape, et un verdict calculé sans IA.
        </mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        @if (loading()) {
          <p class="replay__note">Chargement…</p>
        } @else if (failed()) {
          <p class="replay__note">Les séances n'ont pas pu être chargées.</p>
        } @else if (!sessions().length) {
          <p class="replay__note">Aucune séance vocale ces 7 derniers jours.</p>
        } @else {
          <div class="replay__table-wrap">
            <table class="replay__table">
              <thead>
                <tr>
                  <th scope="col">Séance</th>
                  <th scope="col" class="num">Tours</th>
                  <th scope="col" class="num">Perdus</th>
                  <th scope="col" class="num">Répétés</th>
                  <th scope="col" class="num">Lents</th>
                  <th scope="col" class="num">Premier son (médiane)</th>
                  <th scope="col" class="num">Mauvais sujet</th>
                  <th scope="col" class="num">« Voici » sans affichage</th>
                  <th scope="col" class="num">Replis</th>
                  <th scope="col"><span class="cdk-visually-hidden">Ouvrir</span></th>
                </tr>
              </thead>
              <tbody>
                @for (s of sessions(); track s.from) {
                  <tr [class.replay__row--on]="s.from === selected()">
                    <td>{{ parisDate(s.from) }}<span class="replay__muted"> · {{ s.pages }} page(s)</span></td>
                    <td class="num">{{ s.turns }}</td>
                    <td class="num">{{ s.bilan.lost }} <span class="replay__muted">({{ percent(s.bilan.lostRate) }})</span></td>
                    <td class="num">{{ s.bilan.repeated }}</td>
                    <td class="num">{{ s.bilan.slow }}</td>
                    <td class="num">{{ seconds(s.bilan.firstSoundP50) }}</td>
                    <td class="num">{{ s.bilan.wrongSubject }}</td>
                    <td class="num">{{ s.bilan.voiciWithoutShow }}</td>
                    <td class="num">{{ s.bilan.streamFallbacks }} <span class="replay__muted">({{ percent(s.bilan.streamFallbackRate) }})</span></td>
                    <td>
                      <button mat-stroked-button type="button" (click)="open(s)" [disabled]="opening()"
                              [attr.aria-label]="'Rejouer la séance du ' + parisDate(s.from)">Rejouer</button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }

        @if (replay(); as r) {
          <section class="replay__session" aria-label="Séance rejouée">
            <h3 class="replay__title">Séance du {{ parisDate(r.from) }} — {{ r.turns.length }} tours</h3>
            @if (r.bilan; as b) {
              <p class="replay__bilan">
                {{ b.heard }} entendus, {{ b.lost }} perdus ({{ percent(b.lostRate) }}), {{ b.repeated }} répétés,
                {{ b.slow }} lents (premier son médian {{ seconds(b.firstSoundP50) }}, p90 {{ seconds(b.firstSoundP90) }}),
                {{ b.wrongSubject }} mauvais sujet, {{ b.shown }} montrés, {{ b.voiciWithoutShow }} « Voici » sans
                affichage, {{ b.delegations }} délégations ({{ b.silentDelegations }} muettes), {{ b.streamFallbacks }}
                replis de transcription, {{ b.unmeasured }} sans mesure.
              </p>
              <p class="replay__muted">
                Lent = premier son au-delà de {{ r.thresholds.slowFirstSoundMs / 1000 | number: '1.0-1' }} s ; répété =
                même réponse qu'un des {{ r.thresholds.repeatWindow }} tours précédents.
              </p>
            }
            <ol class="replay__turns">
              @for (t of r.turns; track t.number) {
                <li class="replay__turn">
                  <div class="replay__turn-head">
                    <span class="replay__num">{{ t.number }}</span>
                    <span class="replay__time">{{ parisTime(t.at) }}</span>
                    @for (v of t.verdicts; track v.code) {
                      <span class="replay__chip replay__chip--{{ verdictTone(v.code) }}" [attr.title]="v.detail">
                        {{ verdictLabel(v.code) }}
                      </span>
                    }
                    @if (t.correlation === 'ESTIMEE') {
                      <span class="replay__muted">rattachement estimé</span>
                    }
                  </div>
                  <dl class="replay__facts">
                    <dt>Vous</dt>
                    <dd>
                      @if (t.heard.userText || t.heard.finalText) {
                        « {{ t.heard.userText || t.heard.finalText }} »
                      } @else if (t.heard.rejected) {
                        <span class="replay__muted">rien retenu ({{ t.heard.rejected }})</span>
                      } @else {
                        <span class="replay__muted">—</span>
                      }
                      @if (t.heard.captureId) {
                        <button mat-button type="button" class="replay__listen" (click)="listen(t.heard.captureId)"
                                [attr.aria-label]="'Réécouter le tour ' + t.number">
                          <mat-icon aria-hidden="true">play_arrow</mat-icon> Réécouter
                        </button>
                      }
                    </dd>
                    @if (t.heard.streamText && t.heard.streamText !== t.heard.finalText) {
                      <dt>Flux</dt>
                      <dd class="replay__muted">« {{ t.heard.streamText }} »</dd>
                    }
                    @if (t.reply) {
                      <dt>Jarvis</dt>
                      <dd>« {{ t.reply }} »</dd>
                    }
                    @for (d of t.delegations; track d.at) {
                      <dt>Délégué</dt>
                      <dd>
                        vers <strong>{{ d.subject?.name || 'sujet inconnu' }}</strong>
                        @if (d.chantier) { · chantier {{ d.chantier }} }
                        @if (d.label) { — « {{ d.label }} » }
                        @if (d.reply) { <br><span class="replay__muted">{{ d.reply }}</span> }
                      </dd>
                    }
                    @if (t.shows.length) {
                      <dt>Écran</dt>
                      <dd>
                        @for (s of t.shows; track $index) {
                          <span>{{ s.action }} {{ s.refused ? '— refusé' + (s.reason ? ' (' + s.reason + ')' : '') : '— montré' }}</span>
                        }
                      </dd>
                    }
                    <dt>Temps</dt>
                    <dd>
                      @if (t.measured) {
                        transcription {{ seconds(t.steps.transcriptMs) }} · premier son {{ seconds(t.steps.firstSoundMs) }}
                        · fin {{ seconds(t.steps.doneMs) }}
                        @if (t.brain) { <span class="replay__muted"> · {{ t.brain }}</span> }
                        @if (t.speculation) { <span class="replay__muted"> · réponse provisoire {{ t.speculation }}</span> }
                      } @else {
                        <span class="replay__muted">aucune mesure reçue</span>
                      }
                    </dd>
                  </dl>
                  @if (detailOf(t); as detail) {
                    <p class="replay__muted replay__details">{{ detail }}</p>
                  }
                  <details class="replay__journal">
                    <summary>Journal d'écoute ({{ t.events.length }})</summary>
                    <pre>{{ t.events.join('\\n') }}</pre>
                  </details>
                </li>
              }
            </ol>
          </section>
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    .replay__note, .replay__muted { color: var(--cg-text-secondary); }
    .replay__table-wrap { overflow-x: auto; }
    .replay__table { width: 100%; border-collapse: collapse; font-size: 14px; }
    .replay__table th, .replay__table td {
      padding: var(--cg-space-2); border-bottom: 1px solid var(--cg-divider); text-align: left; white-space: nowrap;
    }
    .replay__table .num { text-align: right; }
    .replay__table tbody tr:hover { background: var(--cg-bg); }
    .replay__row--on { background: var(--cg-surface-2); }
    .replay__session { margin-top: var(--cg-space-4); }
    .replay__title { font-family: var(--cg-font-heading); margin: 0 0 var(--cg-space-2); }
    .replay__bilan { margin: 0 0 var(--cg-space-1); }
    .replay__turns { list-style: none; padding: 0; margin: var(--cg-space-3) 0 0; display: flex; flex-direction: column;
      gap: var(--cg-space-2); }
    .replay__turn { border: 1px solid var(--cg-divider); border-radius: 8px; padding: var(--cg-space-2) var(--cg-space-3); }
    .replay__turn-head { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cg-space-2); }
    .replay__num { font-weight: 600; min-width: 24px; }
    .replay__time { font-family: var(--cg-font-mono); font-size: 13px; }
    .replay__chip { border-radius: 12px; padding: 0 var(--cg-space-2); font-size: 12px; line-height: 24px; }
    .replay__chip--ok { background: #E8F5E9; color: #16A34A; }
    .replay__chip--warn { background: #FFF8E1; color: var(--cg-gold-ink); }
    .replay__chip--bad { background: #FFEBEE; color: #DC2626; }
    .replay__facts { display: grid; grid-template-columns: max-content 1fr; gap: var(--cg-space-1) var(--cg-space-3);
      margin: var(--cg-space-2) 0 0; }
    .replay__facts dt { color: var(--cg-text-secondary); }
    .replay__facts dd { margin: 0; overflow-wrap: anywhere; }
    .replay__listen { margin-left: var(--cg-space-1); }
    .replay__details { margin: var(--cg-space-1) 0 0; font-size: 13px; }
    .replay__journal { margin-top: var(--cg-space-1); }
    .replay__journal pre { font-family: var(--cg-font-mono); font-size: 12px; white-space: pre-wrap; margin: var(--cg-space-1) 0 0; }
  `,
})
export class AdminJarvisReplayComponent implements OnInit, OnDestroy {
  private readonly api = inject(AdminJarvisReplayService);
  private readonly snack = inject(MatSnackBar);

  readonly sessions = signal<ReplaySession[]>([]);
  readonly loading = signal(true);
  readonly failed = signal(false);
  readonly replay = signal<Replay | null>(null);
  readonly selected = signal<string | null>(null);
  readonly opening = signal(false);
  private audioUrl: string | null = null;
  private audio: HTMLAudioElement | null = null;

  protected readonly verdictLabel = verdictLabel;
  protected readonly verdictTone = verdictTone;
  protected readonly seconds = seconds;
  protected readonly percent = percent;
  protected readonly parisDate = parisDate;
  protected readonly parisTime = parisTime;

  ngOnInit(): void {
    this.api.sessions().subscribe({
      next: (sessions) => {
        this.sessions.set(sessions);
        this.loading.set(false);
      },
      error: () => {
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  ngOnDestroy(): void {
    this.stopAudio();
  }

  open(session: ReplaySession): void {
    this.opening.set(true);
    this.selected.set(session.from);
    this.api.replay(session.from, session.to).subscribe({
      next: (replay) => {
        this.replay.set(replay);
        this.opening.set(false);
      },
      error: () => {
        this.opening.set(false);
        this.snack.open('La séance n’a pas pu être rejouée.', 'OK', { duration: 4000 });
      },
    });
  }

  /** Les détails des verdicts qui en portent (« premier son à 4,6 s », « parti vers waf… »). */
  detailOf(turn: ReplayTurn): string | null {
    const details = turn.verdicts.filter((v: ReplayVerdict) => v.detail && v.code !== 'ENTENDU')
      .map((v) => `${verdictLabel(v.code)} : ${v.detail}`);
    return details.length ? details.join(' · ') : null;
  }

  listen(captureId: string): void {
    this.api.audio(captureId).subscribe({
      next: (blob) => {
        this.stopAudio();
        this.audioUrl = URL.createObjectURL(blob);
        this.audio = new Audio(this.audioUrl);
        this.audio.onended = () => this.stopAudio();
        void this.audio.play().catch(() => this.stopAudio());
      },
      error: () => this.snack.open('L’énoncé n’est plus disponible (gardé 7 jours).', 'OK', { duration: 4000 }),
    });
  }

  stopAudio(): void {
    this.audio?.pause();
    this.audio = null;
    if (this.audioUrl) {
      URL.revokeObjectURL(this.audioUrl);
      this.audioUrl = null;
    }
  }
}
