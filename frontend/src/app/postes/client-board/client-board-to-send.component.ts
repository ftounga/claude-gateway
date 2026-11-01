import { Component, effect, inject, input, signal } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';

import { JarvisToSend, JarvisToSendList, toSendLabel } from '../../core/models/jarvis.models';
import { ago } from '../../core/models/routine.models';
import { JarvisService } from '../../core/services/jarvis.service';

/**
 * **« À envoyer (n) » dans le tableau du client** (F-231 / SF-231-06, D11) : les encarts mail ou message proposés
 * ces 7 derniers jours, tous sujets du poste confondus, **pas encore marqués envoyés** (F-211). Rien ne se perd
 * entre la voix et l'envoi : Jarvis prépare, le PO envoie. Un clic ouvre le sujet, où l'encart porte [Copier] et
 * [Marquer envoyé]. Absent quand il n'y a rien à envoyer : le tableau reste calme.
 */
@Component({
  selector: 'app-client-board-to-send',
  imports: [MatIconModule, RouterLink],
  template: `
    @if (list(); as l) {
      @if (l.count > 0) {
        <section class="cbs" aria-labelledby="cbs-title">
          <h3 id="cbs-title" class="cbs__title">
            <mat-icon aria-hidden="true">outgoing_mail</mat-icon> À envoyer ({{ l.count }})
          </h3>
          <ul class="cbs__list">
            @for (item of l.items; track item.messageId + '#' + item.blockIndex) {
              <li class="cbs__item">
                <a class="cbs__link" [routerLink]="['/atelier', item.workspaceId]">
                  <span class="cbs__name">{{ label(item) }}</span>
                  <span class="cbs__meta">{{ item.workspaceName }} · {{ when(item.proposedAt) }}@if (item.viaJarvis) { · 🎙 via Jarvis}</span>
                </a>
              </li>
            }
          </ul>
        </section>
      }
    }
  `,
  styles: `
    :host { display: block; }
    .cbs {
      margin: 0 0 var(--cg-space-3); padding: var(--cg-space-2) var(--cg-space-3);
      border: 1px solid var(--cg-divider); border-left: 4px solid var(--cg-orange-2); border-radius: 8px;
      font-family: var(--cg-font-body);
    }
    .cbs__title {
      display: flex; align-items: center; gap: var(--cg-space-1); margin: 0; font-size: 14px; font-weight: 600;
    }
    .cbs__title mat-icon { color: var(--cg-orange-2); }
    .cbs__list { list-style: none; margin: var(--cg-space-1) 0 0; padding: 0; }
    .cbs__item { padding: var(--cg-space-1) 0; border-top: 1px solid var(--cg-divider); font-size: 13px; }
    .cbs__item:first-child { border-top: none; }
    .cbs__link { display: flex; flex-direction: column; min-width: 0; color: inherit; text-decoration: none; }
    .cbs__link:hover .cbs__name, .cbs__link:focus-visible .cbs__name { text-decoration: underline; }
    .cbs__name { font-weight: 600; overflow-wrap: anywhere; }
    .cbs__meta { opacity: 0.8; overflow-wrap: anywhere; }
  `,
})
export class ClientBoardToSendComponent {
  private readonly jarvis = inject(JarvisService);

  /** Le poste du tableau. */
  readonly hostId = input<string | null>(null);

  readonly list = signal<JarvisToSendList | null>(null);

  constructor() {
    effect(() => {
      const hostId = this.hostId();
      if (!hostId) {
        this.list.set(null);
        return;
      }
      this.jarvis.toSend(hostId).subscribe({
        next: (list) => this.list.set(list),
        // Le compteur n'est jamais une raison de casser le tableau : la section reste absente.
        error: () => this.list.set(null),
      });
    });
  }

  label(item: JarvisToSend): string {
    return toSendLabel(item);
  }

  when(iso: string): string {
    return ago(iso);
  }
}
