import { Component, ElementRef, computed, effect, inject, signal, untracked, viewChildren } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';

import { Situation } from '../../core/models/situation.models';
import { SituationService } from '../../core/services/situation.service';
import { SituationEssentialLine, SituationEssentialTarget, situationEssentials } from './situation-essentials';
import { TerminalSituationComponent } from './terminal-situation.component';

/** Ce que la pop-up reçoit : le terminal, la lecture déjà faite à l'entrée (sinon elle lit), le volet. */
export interface SituationDialogData {
  workspaceId: string | null;
  readOnly: boolean;
  /** Le chantier du volet (split) : l'essentiel et le filtre du détail s'y placent. */
  chantier: number | null;
  /** La lecture faite pour décider de l'ouverture à l'entrée : pas de seconde lecture. */
  situation?: Situation | null;
  /** Un envoi marqué/annulé dans le détail : le terminal relit ses envois (SF-225-03). */
  onSendsChanged?: () => void;
}

/** À la fermeture par une ligne de l'essentiel : où aller. `undefined` = fermée sans suite. */
export type SituationDialogResult = SituationEssentialTarget | undefined;

type SituationTab = 'essential' | 'detail';

/**
 * **Le point de situation en pop-up** (F-225 / SF-225-04) — « où j'en étais, qu'ai-je fait » d'un coup d'œil.
 * Onglet « L'essentiel » : 1 à 5 lignes dérivées de la lecture existante (aucun appel modèle), chacune cliquable
 * vers le chantier, le fil ou le détail. Onglet « Détail » : la carte complète d'avant (SF-225-02/03), sans rien
 * perdre. `MatDialog` : `role=dialog`, `aria-modal`, focus piégé et rendu ; Échap, croix, clic dehors ferment.
 * Fond du terminal sombre (DESIGN_SYSTEM §21) : le détail garde ses encres.
 */
@Component({
  selector: 'app-terminal-situation-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, TerminalSituationComponent],
  template: `
    <header class="sitd__head">
      <h2 mat-dialog-title class="sitd__title" id="sitd-title">Point de situation</h2>
      <button mat-icon-button type="button" class="sitd__close" mat-dialog-close aria-label="Fermer">
        <mat-icon>close</mat-icon>
      </button>
    </header>
    <div class="sitd__tabs" role="tablist" aria-label="Point de situation">
      @for (t of tabs; track t.key; let i = $index) {
        <button #tabButton type="button" role="tab" class="sitd__tab" [class.sitd__tab--on]="tab() === t.key"
          [id]="'sitd-tab-' + t.key" [attr.aria-selected]="tab() === t.key" [attr.aria-controls]="'sitd-panel-' + t.key"
          [attr.tabindex]="tab() === t.key ? 0 : -1" (click)="select(t.key)" (keydown)="onTabKey($event, i)">
          {{ t.label }}
        </button>
      }
    </div>
    <mat-dialog-content class="sitd__body">
      @if (tab() === 'essential') {
        <div role="tabpanel" id="sitd-panel-essential" aria-labelledby="sitd-tab-essential" class="sitd__panel">
          @if (state() === 'loading') {
            <p class="sitd__note">Lecture du point de situation…</p>
          } @else if (state() === 'error') {
            <p class="sitd__note sitd__note--error" role="alert">Le point de situation n'a pas pu être lu.</p>
          } @else {
            <ul class="sitd__lines">
              @for (line of lines(); track $index) {
                <li class="sitd__line" [attr.data-line]="line.key">
                  @if (line.target) {
                    <button type="button" class="sitd__go" (click)="go(line.target)" [attr.aria-label]="ariaOf(line)">
                      @if (line.label) {
                        <span class="sitd__label">{{ line.label }}</span>
                      }
                      <span class="sitd__text">{{ line.text }}</span>
                      <mat-icon class="sitd__arrow" aria-hidden="true">{{ line.target.kind === 'detail' ? 'subject' : 'arrow_forward' }}</mat-icon>
                    </button>
                  } @else {
                    <span class="sitd__static"><span class="sitd__text">{{ line.text }}</span></span>
                  }
                </li>
              }
            </ul>
          }
        </div>
      } @else {
        <div role="tabpanel" id="sitd-panel-detail" aria-labelledby="sitd-tab-detail" class="sitd__panel">
          <app-terminal-situation [workspaceId]="data.workspaceId" [readOnly]="data.readOnly" [chantier]="data.chantier"
            [preloaded]="situation()" (sendsChanged)="onSendsChanged()" />
        </div>
      }
    </mat-dialog-content>
  `,
  styles: `
    :host { display: block; background: var(--cg-primary); color: var(--cg-divider);
      --mdc-icon-button-icon-color: var(--cg-divider);
      --mdc-text-button-label-text-color: var(--cg-orange-2);
      --mdc-outlined-button-label-text-color: var(--cg-divider);
      --mdc-outlined-button-outline-color: var(--cg-terminal-ink-2);
      --mdc-dialog-supporting-text-color: var(--cg-divider); }
    .sitd__head { display: flex; align-items: center; gap: var(--cg-space-2); padding: var(--cg-space-3) var(--cg-space-2) 0 var(--cg-space-4); }
    .sitd__title { flex: 1; margin: 0; padding: 0; font: 600 18px var(--cg-font-heading, var(--cg-font-body)); color: var(--cg-surface); }
    .sitd__title::before { display: none; }
    .sitd__close:focus-visible, .sitd__tab:focus-visible, .sitd__go:focus-visible { outline: 2px solid var(--cg-orange-2); outline-offset: 2px; }
    .sitd__tabs { display: flex; gap: var(--cg-space-1); padding: var(--cg-space-2) var(--cg-space-4) 0;
      border-bottom: 1px solid color-mix(in srgb, var(--cg-divider) 20%, transparent); }
    .sitd__tab { padding: var(--cg-space-2) var(--cg-space-3); border: 0; border-bottom: 2px solid transparent; background: transparent;
      color: var(--cg-terminal-ink-2); font: 600 14px var(--cg-font-body); cursor: pointer; margin-bottom: -1px; }
    .sitd__tab--on { color: var(--cg-orange-2); border-bottom-color: var(--cg-accent); }
    .sitd__body { padding: var(--cg-space-3) var(--cg-space-4) var(--cg-space-4) !important; max-height: min(70vh, 640px); color: var(--cg-divider); }
    .sitd__note { margin: 0; color: var(--cg-terminal-ink-2); }
    .sitd__note--error { color: var(--cg-orange-2); }
    .sitd__lines { margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: var(--cg-space-1); }
    .sitd__go, .sitd__static { display: grid; grid-template-columns: 9.5em minmax(0, 1fr) auto; align-items: baseline; gap: var(--cg-space-2);
      width: 100%; padding: var(--cg-space-2); border: 0; border-radius: 8px; background: transparent; color: var(--cg-divider);
      font: 400 14px/1.45 var(--cg-font-body); text-align: left; }
    .sitd__go { cursor: pointer; }
    .sitd__go:hover { background: color-mix(in srgb, var(--cg-divider) 8%, transparent); }
    .sitd__static { grid-template-columns: minmax(0, 1fr); color: var(--cg-terminal-ink-2); }
    .sitd__label { color: var(--cg-orange-2); font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: .04em; }
    .sitd__text { min-width: 0; overflow-wrap: anywhere; }
    .sitd__arrow { font-size: 18px; width: 18px; height: 18px; color: var(--cg-terminal-ink-2); align-self: center; }
    @media (max-width: 599px) {
      .sitd__head { padding-left: var(--cg-space-3); }
      .sitd__tabs { padding: var(--cg-space-2) var(--cg-space-3) 0; }
      .sitd__body { padding: var(--cg-space-2) var(--cg-space-3) var(--cg-space-3) !important; max-height: 75vh; }
      .sitd__go, .sitd__static { grid-template-columns: minmax(0, 1fr) auto; row-gap: 2px; }
      .sitd__label { grid-column: 1 / -1; }
    }
  `,
})
export class TerminalSituationDialogComponent {
  readonly data = inject<SituationDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<TerminalSituationDialogComponent, SituationDialogResult>>(MatDialogRef);
  private readonly service = inject(SituationService);

  readonly tabs: readonly { key: SituationTab; label: string }[] = [
    { key: 'essential', label: 'L’essentiel' },
    { key: 'detail', label: 'Détail' },
  ];
  readonly tab = signal<SituationTab>('essential');
  readonly situation = signal<Situation | null>(this.data.situation ?? null);
  readonly state = signal<'loading' | 'ready' | 'error'>(this.data.situation ? 'ready' : 'loading');
  readonly lines = computed<SituationEssentialLine[]>(() => {
    const s = this.situation();
    return s ? situationEssentials(s, this.data.chantier) : [];
  });

  private readonly tabButtons = viewChildren<ElementRef<HTMLButtonElement>>('tabButton');

  private readonly load = effect(() => {
    untracked(() => {
      if (this.data.situation) {
        return;
      }
      if (!this.data.workspaceId) {
        this.state.set('error');
        return;
      }
      this.service.situation(this.data.workspaceId).subscribe({
        next: (s) => {
          this.situation.set(s);
          this.state.set('ready');
        },
        error: () => this.state.set('error'),
      });
    });
  });

  select(tab: SituationTab): void {
    this.tab.set(tab);
  }

  /** Onglets au clavier (motif ARIA « tabs ») : ←/→ passent à l'autre, Début/Fin aux extrêmes. */
  onTabKey(event: KeyboardEvent, index: number): void {
    const count = this.tabs.length;
    let next: number | null = null;
    if (event.key === 'ArrowRight') {
      next = (index + 1) % count;
    } else if (event.key === 'ArrowLeft') {
      next = (index - 1 + count) % count;
    } else if (event.key === 'Home') {
      next = 0;
    } else if (event.key === 'End') {
      next = count - 1;
    }
    if (next === null) {
      return;
    }
    event.preventDefault();
    this.select(this.tabs[next].key);
    this.tabButtons()[next]?.nativeElement.focus();
  }

  /** Une ligne cliquée : le détail s'ouvre ici ; le chantier ou le fil ferment la pop-up et y mènent. */
  go(target: SituationEssentialTarget): void {
    if (target.kind === 'detail') {
      this.select('detail');
      return;
    }
    this.ref.close(target);
  }

  ariaOf(line: SituationEssentialLine): string {
    const where = line.target?.kind === 'chantier' ? `aller au chantier ${line.target.number}`
      : line.target?.kind === 'detail' ? 'voir le détail' : 'aller au fil';
    return `${line.label ? line.label + ' : ' : ''}${line.text} — ${where}`;
  }

  onSendsChanged(): void {
    this.data.onSendsChanged?.();
  }
}
