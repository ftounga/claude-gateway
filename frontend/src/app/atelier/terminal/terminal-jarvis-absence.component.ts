import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSnackBar } from '@angular/material/snack-bar';

import { JarvisAbsence, JarvisToSend, toSendLabel } from '../../core/models/jarvis.models';
import { ago } from '../../core/models/routine.models';
import { JarvisService } from '../../core/services/jarvis.service';
import { MessageSendsService } from '../../core/services/message-sends.service';

/**
 * **« Pendant ton absence, via Jarvis »** (F-231 / SF-231-06, D10, D11) — en tête du fil, au retour sur un terminal
 * où Jarvis a agi depuis la dernière visite de cet appareil.
 *
 * <p>Rien à réconcilier : tout est déjà dans le fil, au même endroit que si le PO avait tapé. L'encart résume —
 * les demandes dites à la voix et la phrase de chaque réponse, les attentes créées, et surtout **les messages à
 * envoyer** : Jarvis prépare, le PO envoie ([Copier], puis [Marquer envoyé], F-211). Absent quand Jarvis n'a rien
 * fait ici ; refermable.</p>
 */
@Component({
  selector: 'app-terminal-jarvis-absence',
  imports: [MatButtonModule, MatIconModule],
  template: `
    @if (visible(); as a) {
      <section class="jabs" aria-labelledby="jabs-title">
        <div class="jabs__head">
          <h3 id="jabs-title" class="jabs__title">
            <mat-icon aria-hidden="true">graphic_eq</mat-icon> Pendant ton absence, via Jarvis
          </h3>
          <button mat-icon-button type="button" class="jabs__close" aria-label="Fermer l'encart"
                  (click)="dismissed.set(true)">
            <mat-icon>close</mat-icon>
          </button>
        </div>
        <ul class="jabs__turns">
          @for (turn of a.turns; track turn.messageId) {
            <li class="jabs__turn">
              <span class="jabs__request">🎙 « {{ turn.request }} »</span>
              @if (turn.reply) { <span class="jabs__reply">{{ turn.reply }}</span> }
              <span class="jabs__meta">{{ when(turn.at) }}@if (turn.chantierNumber) { · chantier {{ turn.chantierNumber }}}@if (turn.brain === 'FAST') { · réponse rapide} @else if (turn.brain === 'DELEGATED') { · délégué à Opus}</span>
            </li>
          }
        </ul>
        @if (a.attentes.length) {
          <p class="jabs__label">Attentes créées</p>
          <ul class="jabs__list">
            @for (attente of a.attentes; track attente.id) {
              <li>{{ attente.description }}</li>
            }
          </ul>
        }
        @if (pending().length) {
          <p class="jabs__label">À envoyer ({{ pending().length }}) — Jarvis prépare, tu envoies</p>
          <ul class="jabs__list jabs__list--send">
            @for (item of pending(); track item.messageId + '#' + item.blockIndex) {
              <li class="jabs__send">
                <span class="jabs__send-label">{{ label(item) }}@if (item.personal) {
                  <!-- F-241 / SF-241-03 (D18) — l'encart reprend un élément du portrait. -->
                  <span class="jabs__personal"> · élément personnel — à relire</span>
                }</span>
                <span class="jabs__send-actions">
                  <button mat-stroked-button type="button" (click)="copy(item)">
                    <mat-icon>content_copy</mat-icon> Copier
                  </button>
                  <button mat-stroked-button type="button" [disabled]="readOnly() || marking() === key(item)"
                          (click)="markSent(item)">
                    <mat-icon>done</mat-icon> Marquer envoyé
                  </button>
                </span>
              </li>
            }
          </ul>
        }
      </section>
    }
  `,
  styles: `
    :host { display: block; }
    .jabs {
      margin: var(--cg-space-2) 0; padding: var(--cg-space-2) var(--cg-space-1) var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-divider); border-left: 4px solid var(--cg-accent); border-radius: 8px;
      font-family: var(--cg-font-body); color: inherit;
    }
    .jabs__head { display: flex; align-items: center; justify-content: space-between; gap: var(--cg-space-2); }
    .jabs__title {
      display: flex; align-items: center; gap: var(--cg-space-1); margin: 0; font-size: 14px; font-weight: 600;
    }
    .jabs__title mat-icon { color: var(--cg-accent); }
    .jabs__close { --mdc-icon-button-icon-color: inherit; color: inherit; flex: none; }
    .jabs__turns, .jabs__list { list-style: none; margin: var(--cg-space-1) 0 0; padding: 0; }
    .jabs__turn { display: flex; flex-direction: column; padding: var(--cg-space-1) 0; font-size: 13px; }
    .jabs__turn + .jabs__turn { border-top: 1px solid var(--cg-divider); }
    .jabs__request { font-weight: 600; overflow-wrap: anywhere; }
    .jabs__reply { overflow-wrap: anywhere; }
    .jabs__meta { font-size: 12px; opacity: 0.8; }
    .jabs__label { margin: var(--cg-space-2) 0 0; font-size: 12px; font-weight: 600; opacity: 0.85; }
    .jabs__list li { font-size: 13px; padding: var(--cg-space-1) 0; overflow-wrap: anywhere; }
    .jabs__send { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between;
      gap: var(--cg-space-1) var(--cg-space-2); }
    .jabs__send-label { min-width: 0; overflow-wrap: anywhere; }
    .jabs__personal { font-weight: 600; text-decoration: underline dotted; }
    .jabs__send-actions { display: flex; flex-wrap: wrap; gap: var(--cg-space-1); }
  `,
})
export class TerminalJarvisAbsenceComponent {
  private readonly jarvis = inject(JarvisService);
  private readonly sends = inject(MessageSendsService);
  private readonly snackBar = inject(MatSnackBar);

  /** Le terminal ouvert. */
  readonly workspaceId = input<string | null>(null);
  /**
   * La dernière visite de ce terminal sur cet appareil, lue AVANT d'inscrire celle-ci (SF-225-03). `undefined` : pas
   * encore connue — rien n'est demandé. `null` : jamais venu (la passerelle regarde alors les dernières 24 h).
   */
  readonly since = input<string | null | undefined>(undefined);
  readonly readOnly = input(false);

  readonly absence = signal<JarvisAbsence | null>(null);
  readonly dismissed = signal(false);
  readonly marking = signal<string | null>(null);
  private readonly sent = signal<ReadonlySet<string>>(new Set());

  readonly visible = computed(() => {
    const a = this.absence();
    return a && a.turns.length > 0 && !this.dismissed() ? a : null;
  });

  readonly pending = computed(() =>
    (this.absence()?.toSend ?? []).filter((item) => item.sentAt === null && !this.sent().has(this.key(item))));

  constructor() {
    effect(() => {
      const workspaceId = this.workspaceId();
      const since = this.since();
      this.absence.set(null);
      this.dismissed.set(false);
      this.sent.set(new Set());
      if (!workspaceId || since === undefined) {
        return;
      }
      this.jarvis.absence(workspaceId, since).subscribe({
        next: (absence) => {
          if (this.workspaceId() === workspaceId) {
            this.absence.set(absence);
          }
        },
        // L'encart n'est jamais une raison de casser le terminal : il reste absent.
        error: () => this.absence.set(null),
      });
    });
  }

  key(item: JarvisToSend): string {
    return `${item.messageId}#${item.blockIndex}`;
  }

  label(item: JarvisToSend): string {
    return toSendLabel(item);
  }

  when(iso: string): string {
    return ago(iso);
  }

  async copy(item: JarvisToSend): Promise<void> {
    const text = [item.subject ? `Objet : ${item.subject}` : null, item.excerpt ?? ''].filter((l) => l !== null)
      .join('\n\n');
    try {
      await navigator.clipboard.writeText(text);
      this.snackBar.open('Message copié.', undefined, { duration: 2500 });
    } catch {
      this.snackBar.open('Copie impossible sur cet appareil.', undefined, { duration: 3500 });
    }
  }

  markSent(item: JarvisToSend): void {
    const workspaceId = this.workspaceId();
    if (!workspaceId || this.readOnly()) {
      return;
    }
    const key = this.key(item);
    this.marking.set(key);
    this.sends.mark(workspaceId, item.messageId, item.blockIndex).subscribe({
      next: () => {
        this.marking.set(null);
        this.sent.update((set) => new Set([...set, key]));
        this.snackBar.open('Marqué envoyé.', undefined, { duration: 2500 });
      },
      error: () => {
        this.marking.set(null);
        this.snackBar.open('Le marquage a échoué : réessaie.', undefined, { duration: 3500 });
      },
    });
  }
}
