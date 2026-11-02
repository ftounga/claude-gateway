import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar } from '@angular/material/snack-bar';

import { ConfirmDialogComponent, ConfirmDialogData } from '../chat/confirm-dialog/confirm-dialog.component';
import {
  JarvisPortraitClient, PORTRAIT_MAX_TOKENS, Portrait, PortraitError, PortraitSection, PortraitSectionKey,
  PortraitSouvenir, counterLabel, draftChars, portraitErrorMessage, portraitTokens,
} from './jarvis-portrait';

/**
 * **Le volet « Mon portrait »** (F-241 / SF-241-01) — dans les réglages de `/jarvis`.
 *
 * Sept sections libres (mention « sensible », date de mise à jour, marque « à revoir » au-delà de 12 mois), la liste
 * des souvenirs (modifier, effacer, intégrer au portrait), un compteur de taille, l'interrupteur « Jarvis se sert de
 * mon portrait » et « Tout effacer » derrière une confirmation (`MatDialog`). Rien n'est retenu sans être visible ici.
 * Le parent ne l'affiche **jamais** en présentation.
 */
@Component({
  selector: 'app-jarvis-portrait',
  imports: [DatePipe, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatMenuModule,
    MatSlideToggleModule],
  template: `
    <section class="jp" aria-label="Mon portrait">
      <header class="jp__head">
        <h2 class="jp__title">Mon portrait</h2>
        <span class="jp__counter" [class.jp__counter--over]="over()" aria-live="polite">{{ counter() }}</span>
        <button mat-icon-button type="button" class="jp__close" aria-label="Fermer le portrait" (click)="closed.emit()">
          <mat-icon>close</mat-icon>
        </button>
      </header>
      @if (loading()) {
        <p class="jp__hint">Chargement…</p>
      } @else {
      @if (portrait(); as p) {
        <p class="jp__hint">
          Ce que Jarvis sait de vous, pour converser — jamais pour un message à un tiers, jamais en présentation.
          Rien n'est retenu sans apparaître ici.
        </p>
        <mat-slide-toggle class="jp__enabled" [checked]="p.enabled" (change)="toggleEnabled($event.checked)">
          Jarvis se sert de mon portrait
        </mat-slide-toggle>
        @for (s of p.sections; track s.key) {
          <div class="jp__section">
            <div class="jp__section-head">
              <span class="jp__section-title">{{ s.title }}</span>
              @if (s.sensitive) { <span class="jp__tag">sensible</span> }
              @if (s.stale) { <span class="jp__tag jp__tag--stale">à revoir</span> }
              @if (s.updatedAt) { <span class="jp__date">mis à jour le {{ s.updatedAt | date: 'dd/MM/yyyy' }}</span> }
            </div>
            <mat-form-field appearance="outline" class="jp__field" subscriptSizing="dynamic">
              <textarea matInput rows="2" [value]="drafts()[s.key] ?? ''" [attr.aria-label]="s.title"
                        [placeholder]="placeholder(s.key)" (input)="edit(s.key, $any($event.target).value)"></textarea>
            </mat-form-field>
          </div>
        }
        <div class="jp__actions">
          <button mat-flat-button color="primary" type="button" [disabled]="!dirty() || over() || saving()"
                  (click)="save()">Enregistrer</button>
          @if (over()) {
            <span class="jp__warn" role="alert">Au-delà de {{ maxTokens }} jetons : raccourcissez une section.</span>
          }
        </div>

        <h3 class="jp__subtitle">Souvenirs ({{ p.souvenirs.length }} / 30)</h3>
        @if (p.souvenirs.length === 0) {
          <p class="jp__hint">Aucun souvenir. Jarvis vous demandera toujours avant d'en retenir un.</p>
        }
        <ul class="jp__souvenirs">
          @for (m of p.souvenirs; track m.id) {
            <li class="jp__souvenir">
              @if (editingId() === m.id) {
                <mat-form-field appearance="outline" class="jp__field" subscriptSizing="dynamic">
                  <input matInput #fix [value]="m.text" maxlength="200" aria-label="Modifier le souvenir"
                         (keydown.enter)="saveSouvenir(m, fix.value)" (keydown.escape)="editingId.set(null)" />
                </mat-form-field>
                <button mat-button type="button" (click)="saveSouvenir(m, fix.value)">OK</button>
              } @else {
                <span class="jp__souvenir-text">S{{ m.number }} — {{ m.text }}</span>
                <span class="jp__date">{{ m.notedOn | date: 'dd/MM/yyyy' }}</span>
                @if (m.sensitive) { <span class="jp__tag">sensible</span> }
                @if (m.stale) { <span class="jp__tag jp__tag--stale">à revoir</span> }
                <span class="jp__souvenir-actions">
                  <button mat-icon-button type="button" aria-label="Modifier" (click)="editingId.set(m.id)">
                    <mat-icon>edit</mat-icon>
                  </button>
                  <button mat-icon-button type="button" [matMenuTriggerFor]="into" aria-label="Intégrer au portrait">
                    <mat-icon>move_down</mat-icon>
                  </button>
                  <mat-menu #into="matMenu">
                    @for (s of p.sections; track s.key) {
                      <button mat-menu-item type="button" (click)="integrate(m, s.key)">{{ s.title }}</button>
                    }
                  </mat-menu>
                  <button mat-icon-button type="button" aria-label="Effacer le souvenir" (click)="forget(m)">
                    <mat-icon>delete</mat-icon>
                  </button>
                </span>
              }
            </li>
          }
        </ul>
        <div class="jp__add">
          <mat-form-field appearance="outline" class="jp__field" subscriptSizing="dynamic">
            <mat-label>Ajouter un souvenir</mat-label>
            <input matInput #added maxlength="200" (keydown.enter)="add(added)" />
          </mat-form-field>
          <button mat-button type="button" (click)="add(added)">Ajouter</button>
        </div>

        <div class="jp__danger">
          <button mat-stroked-button color="warn" type="button" (click)="eraseAll()">
            <mat-icon aria-hidden="true">delete_forever</mat-icon> Tout effacer
          </button>
        </div>
      } @else {
        <p class="jp__hint" role="alert">{{ error() }}</p>
      }
      }
    </section>
  `,
  styles: [`
    :host { display: block; }
    .jp {
      position: fixed; z-index: 4; top: calc(var(--cg-space-2) + 52px); right: var(--cg-space-4);
      width: min(560px, calc(100vw - 2 * var(--cg-space-4))); max-height: calc(100dvh - 120px); overflow: auto;
      box-sizing: border-box; padding: var(--cg-space-4); display: flex; flex-direction: column; gap: var(--cg-space-2);
      background: color-mix(in srgb, var(--cg-navy-2) 70%, transparent); color: var(--cg-cream);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent); border-radius: 8px;
      backdrop-filter: blur(10px); font-family: var(--cg-font-body); overflow-wrap: anywhere;
    }
    .jp__head { display: flex; align-items: center; gap: var(--cg-space-2); }
    .jp__title { margin: 0; flex: 1; font-family: var(--cg-font-heading); font-size: 18px; color: var(--cg-cream); }
    .jp__subtitle { margin: var(--cg-space-2) 0 0; font-family: var(--cg-font-heading); font-size: 15px; }
    .jp__counter { font-family: var(--cg-font-mono); font-size: 12px; }
    .jp__counter--over, .jp__warn { color: var(--cg-orange-2); }
    .jp__close { color: var(--cg-cream); }
    .jp__hint { margin: 0; font-size: 13px; opacity: .8; }
    .jp__section-head { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--cg-space-2); }
    .jp__section-title { font-weight: 600; font-size: 14px; }
    .jp__tag { font-size: 12px; padding: 0 var(--cg-space-1); border: 1px solid color-mix(in srgb, var(--cg-cream) 35%, transparent); border-radius: 4px; }
    .jp__tag--stale { color: var(--cg-orange-2); border-color: var(--cg-orange-2); }
    .jp__date { font-size: 12px; opacity: .7; }
    .jp__field { width: 100%; }
    .jp__actions, .jp__add, .jp__danger { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cg-space-2); }
    .jp__add .jp__field { flex: 1; min-width: 0; }
    .jp__souvenirs { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--cg-space-1); }
    .jp__souvenir { display: flex; flex-wrap: wrap; align-items: center; gap: var(--cg-space-2); }
    .jp__souvenir-text { flex: 1 1 200px; font-size: 14px; }
    .jp__souvenir-actions { display: flex; }
    .jp__souvenir-actions button { color: var(--cg-cream); }
    .jp__danger { justify-content: flex-end; margin-top: var(--cg-space-2); }
    @media (max-width: 767px) {
      .jp { right: var(--cg-space-2); left: var(--cg-space-2); width: auto; padding: var(--cg-space-3); }
    }
  `],
})
export class JarvisPortraitComponent implements OnInit {
  private readonly client = inject(JarvisPortraitClient);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);

  readonly closed = output<void>();

  readonly maxTokens = PORTRAIT_MAX_TOKENS;
  readonly portrait = signal<Portrait | null>(null);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly drafts = signal<Record<string, string>>({});
  readonly editingId = signal<string | null>(null);

  readonly chars = computed(() => draftChars(this.drafts(), this.portrait()?.souvenirs ?? []));
  readonly counter = computed(() => counterLabel(this.chars()));
  readonly over = computed(() => portraitTokens(this.chars()) > PORTRAIT_MAX_TOKENS);
  readonly dirty = computed(() => {
    const p = this.portrait();
    const d = this.drafts();
    return !!p && p.sections.some((s) => (d[s.key] ?? '') !== s.text);
  });

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.client.get().subscribe({
      next: (p) => this.apply(p),
      error: (err: PortraitError) => {
        this.loading.set(false);
        this.portrait.set(null);
        this.error.set(portraitErrorMessage(err));
      },
    });
  }

  placeholder(key: PortraitSectionKey): string {
    return key === 'DATES' ? 'JJ/MM[/AAAA] — libellé, une date par ligne' : '';
  }

  edit(key: PortraitSectionKey, value: string): void {
    this.drafts.update((d) => ({ ...d, [key]: value }));
  }

  save(): void {
    const p = this.portrait();
    if (!p || this.over()) {
      return;
    }
    const d = this.drafts();
    const changed: Partial<Record<PortraitSectionKey, string>> = {};
    for (const s of p.sections) {
      if ((d[s.key] ?? '') !== s.text) {
        changed[s.key] = d[s.key] ?? '';
      }
    }
    this.saving.set(true);
    this.client.update(changed).subscribe({
      next: (fresh) => {
        this.saving.set(false);
        this.apply(fresh);
        this.snack.open('Portrait enregistré.', undefined, { duration: 2500 });
      },
      error: (err: PortraitError) => {
        this.saving.set(false);
        this.snack.open(portraitErrorMessage(err), 'OK', { duration: 6000 });
      },
    });
  }

  toggleEnabled(on: boolean): void {
    this.client.update(null, on).subscribe({
      next: (fresh) => this.apply(fresh, true),
      error: (err: PortraitError) => this.snack.open(portraitErrorMessage(err), 'OK', { duration: 6000 }),
    });
  }

  add(field: HTMLInputElement): void {
    const text = field.value.trim();
    if (!text) {
      return;
    }
    this.client.addSouvenir(text).subscribe({
      next: () => {
        field.value = '';
        this.refreshKeepingDrafts();
      },
      error: (err: PortraitError) => this.snack.open(portraitErrorMessage(err), 'OK', { duration: 6000 }),
    });
  }

  saveSouvenir(m: PortraitSouvenir, text: string): void {
    const clean = text.trim();
    this.editingId.set(null);
    if (!clean || clean === m.text) {
      return;
    }
    this.client.patchSouvenir(m.id, { text: clean }).subscribe({
      next: () => this.refreshKeepingDrafts(),
      error: (err: PortraitError) => this.snack.open(portraitErrorMessage(err), 'OK', { duration: 6000 }),
    });
  }

  forget(m: PortraitSouvenir): void {
    this.client.deleteSouvenir(m.id).subscribe({
      next: () => this.refreshKeepingDrafts(),
      error: (err: PortraitError) => this.snack.open(portraitErrorMessage(err), 'OK', { duration: 6000 }),
    });
  }

  integrate(m: PortraitSouvenir, key: PortraitSectionKey): void {
    this.client.integrate(m.id, key).subscribe({
      next: (fresh) => this.apply(fresh),
      error: (err: PortraitError) => this.snack.open(portraitErrorMessage(err), 'OK', { duration: 6000 }),
    });
  }

  eraseAll(): void {
    const data: ConfirmDialogData = {
      title: 'Tout effacer ?',
      message: 'Le portrait et tous les souvenirs seront supprimés définitivement. Jarvis ne saura plus rien de vous.',
      confirmLabel: 'Tout effacer',
    };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (!ok) {
        return;
      }
      this.client.eraseAll().subscribe({
        next: () => {
          this.reload();
          this.snack.open('Tout est effacé.', undefined, { duration: 2500 });
        },
        error: (err: PortraitError) => this.snack.open(portraitErrorMessage(err), 'OK', { duration: 6000 }),
      });
    });
  }

  private refreshKeepingDrafts(): void {
    this.client.get().subscribe({ next: (p) => this.apply(p, true), error: () => undefined });
  }

  /** Pose le portrait reçu ; `keepDrafts` garde ce que le PO est en train d'écrire. */
  private apply(p: Portrait, keepDrafts = false): void {
    this.loading.set(false);
    this.error.set('');
    this.portrait.set(p);
    if (!keepDrafts) {
      const d: Record<string, string> = {};
      for (const s of p.sections as PortraitSection[]) {
        d[s.key] = s.text;
      }
      this.drafts.set(d);
    }
  }
}
