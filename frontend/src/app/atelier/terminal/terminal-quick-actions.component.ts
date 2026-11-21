import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  QuickAction,
  QuickActionsService,
  isSuggestedNow,
  lastRunLabel,
  suggestionLabel,
} from '../../core/services/quick-actions.service';

/** La ligne d'aide sous une action : où elle part, comment, et quand elle a tourné pour la dernière fois. */
export function quickActionMeta(action: QuickAction, now: Date = new Date()): string {
  const where = action.targetMissing
    ? 'sujet introuvable : ici'
    : action.targetName ? `→ ${action.targetName}` : 'ici';
  const how = action.clickMode === 'ENVOYER' ? 'envoi direct' : 'dans la saisie';
  const parts = [where, how];
  const suggestion = suggestionLabel(action);
  if (suggestion) {
    parts.push(suggestion);
  }
  parts.push(lastRunLabel(action.lastRunAt, now));
  return parts.join(' · ');
}

/**
 * **Le bouton « Actions rapides »** (F-275 / SF-275-03) : une seule icône dans la saisie (F-207 : rien de plus à
 * l'écran). Le menu déroule les workflows configurés — suggérés d'abord, puis mes actions, puis ceux que déclarent les
 * skills — et un clic le lance (`launch`), le terminal entier s'occupant d'aller dans le bon sujet.
 *
 * <p>Au chargement, seules les actions personnalisées sont lues (sans lecture du poste) pour allumer le point de
 * suggestion ; la liste complète est relue à chaque ouverture du menu : une action créée par l'agent y apparaît sans
 * recharger.</p>
 */
@Component({
  selector: 'app-terminal-quick-actions',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule],
  template: `
    <button
      mat-icon-button
      type="button"
      class="quick-actions__trigger"
      [class.quick-actions__trigger--suggest]="suggestedCount() > 0"
      [matMenuTriggerFor]="quickMenu"
      (menuOpened)="loadFull()"
      [disabled]="disabled()"
      [attr.aria-label]="triggerLabel()"
      [matTooltip]="triggerLabel()"
    >
      <mat-icon>playlist_play</mat-icon>
      @if (suggestedCount() > 0) {
        <span class="quick-actions__dot" aria-hidden="true"></span>
      }
    </button>
    <mat-menu #quickMenu="matMenu" class="quick-actions-menu" yPosition="above" xPosition="after">
      <p class="quick-actions__title">Actions rapides</p>
      @if (loading() && actions().length === 0) {
        <p class="quick-actions__note" role="status">Lecture des actions…</p>
      }
      @if (suggested().length > 0) {
        <p class="quick-actions__section">Suggéré maintenant</p>
        @for (action of suggested(); track 'sug-' + action.key) {
          <button mat-menu-item type="button" class="quick-actions__item quick-actions__item--suggest"
                  (click)="launch.emit(action)">
            <mat-icon class="quick-actions__icon" aria-hidden="true">schedule</mat-icon>
            <span class="quick-actions__text">
              <span class="quick-actions__label">{{ action.label }}</span>
              <span class="quick-actions__meta">{{ meta(action) }}</span>
            </span>
          </button>
        }
      }
      @if (mine().length > 0) {
        <p class="quick-actions__section">Mes actions</p>
        @for (action of mine(); track action.key) {
          <button mat-menu-item type="button" class="quick-actions__item" (click)="launch.emit(action)">
            <mat-icon class="quick-actions__icon" aria-hidden="true">{{ action.clickMode === 'ENVOYER' ? 'send' : 'edit_note' }}</mat-icon>
            <span class="quick-actions__text">
              <span class="quick-actions__label">{{ action.label }}</span>
              <span class="quick-actions__meta">{{ meta(action) }}</span>
            </span>
          </button>
        }
      }
      @if (fromSkills().length > 0) {
        <p class="quick-actions__section">Depuis vos skills</p>
        @for (action of fromSkills(); track action.key) {
          <button mat-menu-item type="button" class="quick-actions__item" (click)="launch.emit(action)">
            <mat-icon class="quick-actions__icon" aria-hidden="true">{{ action.clickMode === 'ENVOYER' ? 'send' : 'edit_note' }}</mat-icon>
            <span class="quick-actions__text">
              <span class="quick-actions__label">{{ action.label }}</span>
              <span class="quick-actions__meta">/{{ action.skill }} · {{ meta(action) }}</span>
            </span>
          </button>
        }
      }
      @if (!loading() && actions().length === 0) {
        <p class="quick-actions__note">
          {{ failed() ? 'Actions illisibles pour le moment.' : 'Aucune action rapide ici.' }}
          Demandez à l'agent « ajoute une action rapide… », ou déclarez « Déclenché par « … » » dans l'en-tête d'un skill.
        </p>
      }
    </mat-menu>
  `,
  styles: `
    :host {
      display: inline-flex;
      flex: 0 0 auto;
    }

    .quick-actions__trigger {
      position: relative;
      color: var(--cg-text-secondary);
    }

    .quick-actions__trigger--suggest {
      color: var(--cg-orange-2);
    }

    .quick-actions__dot {
      position: absolute;
      top: 8px;
      right: 8px;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: var(--cg-orange-2);
    }
  `,
})
export class TerminalQuickActionsComponent {
  private readonly service = inject(QuickActionsService);

  /** Le terminal affiché. */
  readonly workspaceId = input.required<string>();
  /** Incrémenté par le terminal en fin de tour : la suggestion se relit (une action vient peut-être d'être créée). */
  readonly refresh = input(0);
  readonly disabled = input(false);

  /** L'utilisateur lance une action : le terminal entier s'en charge. */
  readonly launch = output<QuickAction>();

  readonly actions = signal<QuickAction[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  /** L'heure de référence de la suggestion, relevée à chaque lecture. */
  private readonly now = signal(new Date());
  /** Le terminal dont les actions de skills sont à l'écran. */
  private skillsOf: string | null = null;

  readonly suggested = computed(() => this.actions().filter((a) => isSuggestedNow(a, this.now())));
  readonly suggestedCount = computed(() => this.suggested().length);
  readonly mine = computed(() => this.actions().filter((a) => a.source === 'PERSO'));
  readonly fromSkills = computed(() => this.actions().filter((a) => a.source === 'SKILL'));
  readonly triggerLabel = computed(() => this.suggestedCount() > 0
    ? `Actions rapides — ${this.suggestedCount()} suggérée${this.suggestedCount() > 1 ? 's' : ''} maintenant`
    : 'Actions rapides');

  /** Le terminal ou le tour change : la liste légère (sans skills) se relit pour la suggestion. */
  private readonly light = effect(() => {
    const id = this.workspaceId();
    this.refresh();
    untracked(() => {
      if (id !== this.shownFor) {
        // Un autre terminal : rien de l'ancien ne reste à l'écran.
        this.shownFor = id;
        this.skillsOf = null;
        this.actions.set([]);
      }
      this.load(id, false);
    });
  });

  /** Le terminal dont la liste est à l'écran. */
  private shownFor: string | null = null;

  /** Ouverture du menu : la liste complète, skills compris. */
  loadFull(): void {
    this.load(this.workspaceId(), true);
  }

  meta(action: QuickAction): string {
    return quickActionMeta(action, this.now());
  }

  private load(id: string, withSkills: boolean): void {
    if (!id) {
      return;
    }
    this.loading.set(true);
    this.service.list(id, withSkills).subscribe({
      next: (list) => {
        if (id !== this.workspaceId()) {
          return;
        }
        this.now.set(new Date());
        // La liste légère ne remplace pas une liste complète déjà lue pour CE terminal : elle en garde les skills.
        const keptSkills = this.skillsOf === id ? this.actions().filter((a) => a.source === 'SKILL') : [];
        if (withSkills) {
          this.skillsOf = id;
        }
        this.actions.set(withSkills ? list : [...list, ...keptSkills]);
        this.failed.set(false);
        this.loading.set(false);
      },
      error: () => {
        if (id !== this.workspaceId()) {
          return;
        }
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }
}
