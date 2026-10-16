import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { HttpErrorResponse } from '@angular/common/http';

import { ConfirmDialogComponent, ConfirmDialogData } from '../../chat/confirm-dialog/confirm-dialog.component';
import { AccesObtenu, AccesRequest, expiryLabel } from '../../core/models/terminal-actions.models';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';

/** « 02/10/2026 » à partir d'une date ISO `aaaa-mm-jj`. */
export function isoDay(iso: string | null): string {
  if (!iso || iso.length < 10) {
    return '';
  }
  return `${iso.slice(8, 10)}/${iso.slice(5, 7)}/${iso.slice(0, 4)}`;
}

/**
 * **La vue « Accès » du panneau des attentes** (F-180 / SF-180-04, décision D4) : le registre de ce qui a
 * été obtenu — système, compte, quand, par qui, jusqu'à quand. **Jamais un secret** : le serveur refuse tout
 * mot de passe ou jeton, et l'écran le rappelle sous le formulaire.
 *
 * <p>La suppression se confirme par un dialogue (design system) ; un échec remet l'écran et le dit.</p>
 */
@Component({
  selector: 'app-terminal-acces-view',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatTooltipModule],
  template: `
    <div class="acces__tools">
      <p class="acces__lead">Ce qui a déjà été obtenu sur ce poste. L'agent le voit et ne le redemande plus.</p>
      <button mat-stroked-button type="button" class="acces__add" (click)="startAdd()">
        <mat-icon>add</mat-icon>Ajouter un accès
      </button>
    </div>

    @if (editing()) {
      <form class="acces__form" (submit)="$event.preventDefault(); save()">
        <mat-form-field appearance="outline" class="acces__field">
          <mat-label>Système</mat-label>
          <input matInput name="system" [(ngModel)]="form.system" maxlength="120" required placeholder="VPN, forge CAPFM…">
        </mat-form-field>
        <mat-form-field appearance="outline" class="acces__field">
          <mat-label>Compte ou profil</mat-label>
          <input matInput name="account" [(ngModel)]="form.account" maxlength="120">
        </mat-form-field>
        <mat-form-field appearance="outline" class="acces__field">
          <mat-label>Par qui</mat-label>
          <input matInput name="grantedBy" [(ngModel)]="form.grantedBy" maxlength="120">
        </mat-form-field>
        <mat-form-field appearance="outline" class="acces__field acces__field--date">
          <mat-label>Obtenu le</mat-label>
          <input matInput type="date" name="obtainedAt" [(ngModel)]="form.obtainedAt">
        </mat-form-field>
        <mat-form-field appearance="outline" class="acces__field acces__field--date">
          <mat-label>Expire le (facultatif)</mat-label>
          <input matInput type="date" name="expiresAt" [(ngModel)]="form.expiresAt">
        </mat-form-field>
        <p class="acces__nosecret">Aucun mot de passe ni jeton : le registre dit qu'un accès existe, pas comment s'en servir.</p>
        <div class="acces__gestures">
          <button mat-flat-button type="submit" class="acces__save" [disabled]="!(form.system ?? '').trim() || busy()">Enregistrer</button>
          <button mat-button type="button" (click)="editing.set(null)">Annuler</button>
        </div>
      </form>
    }

    @if (failed()) {
      <p class="acces__muted">Le registre n'a pas pu être chargé.</p>
    } @else if (!entries().length) {
      <p class="acces__muted">Aucun accès au registre pour l'instant.</p>
    }
    @for (a of entries(); track a.id) {
      <article class="acces__entry" [class.acces__entry--expired]="a.expired">
        <div class="acces__head">
          <p class="acces__system">{{ a.system }}@if (a.account) { <span class="acces__account"> · {{ a.account }}</span> }</p>
          @if (expiry(a)) {
            <span class="acces__expiry" [class.acces__expiry--late]="a.expired">{{ expiry(a) }}</span>
          }
          <button mat-icon-button type="button" class="acces__edit" matTooltip="Modifier" aria-label="Modifier l'accès"
            (click)="startEdit(a)"><mat-icon>edit</mat-icon></button>
          <button mat-icon-button type="button" class="acces__delete" matTooltip="Supprimer" aria-label="Supprimer l'accès"
            (click)="remove(a)"><mat-icon>delete</mat-icon></button>
        </div>
        <p class="acces__muted">obtenu le {{ day(a.obtainedAt) }}{{ a.grantedBy ? ' par ' + a.grantedBy : '' }}</p>
      </article>
    }
  `,
  styles: `
    :host { display: block; }

    .acces__tools {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      margin-bottom: var(--cg-space-2);
    }

    .acces__lead,
    .acces__muted,
    .acces__nosecret {
      flex: 1;
      margin: var(--cg-space-1) 0 0;
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .acces__nosecret {
      flex-basis: 100%;
    }

    .acces__form {
      display: flex;
      flex-wrap: wrap;
      gap: var(--cg-space-2);
      margin-bottom: var(--cg-space-3);
      padding: var(--cg-space-2);
      border-radius: 8px;
      background: var(--cg-surface-2);
    }

    .acces__field {
      flex: 1 1 200px;
    }

    .acces__field--date {
      flex: 0 1 180px;
    }

    .acces__gestures {
      display: flex;
      gap: var(--cg-space-2);
    }

    .acces__entry {
      padding: var(--cg-space-2);
      border: 1px solid var(--cg-divider);
      border-radius: 8px;
      margin-bottom: var(--cg-space-2);
      background: var(--cg-surface);
    }

    .acces__entry--expired {
      border-color: var(--cg-gold-ink);
    }

    .acces__head {
      display: flex;
      align-items: center;
      gap: var(--cg-space-2);
    }

    .acces__system {
      flex: 1;
      margin: 0;
      font-weight: 600;
      overflow-wrap: anywhere;
    }

    .acces__account {
      font-weight: 400;
      color: var(--cg-text-secondary);
    }

    .acces__expiry {
      padding: 0 var(--cg-space-2);
      border-radius: 12px;
      background: var(--cg-surface-2);
      color: var(--cg-text-secondary);
      font-size: 12px;
      white-space: nowrap;
    }

    .acces__expiry--late {
      background: var(--cg-orange-2);
      color: var(--cg-navy);
      font-weight: 600;
    }
  `,
})
export class TerminalAccesViewComponent implements OnInit {
  readonly workspaceId = input.required<string>();

  private readonly service = inject(TerminalActionsService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  readonly entries = signal<AccesObtenu[]>([]);
  readonly failed = signal(false);
  readonly busy = signal(false);
  /** `'new'` pour un ajout, l'identifiant pour une modification, `null` : pas de formulaire. */
  readonly editing = signal<string | null>(null);

  form: AccesRequest = {};

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.service.acces(this.workspaceId()).subscribe({
      next: list => {
        this.entries.set(list ?? []);
        this.failed.set(false);
      },
      error: () => this.failed.set(true),
    });
  }

  day(iso: string | null): string {
    return isoDay(iso);
  }

  expiry(a: AccesObtenu): string {
    return expiryLabel(a.expiresAt);
  }

  startAdd(): void {
    this.form = { system: '', account: '', grantedBy: '', obtainedAt: new Date().toISOString().slice(0, 10), expiresAt: null };
    this.editing.set('new');
  }

  startEdit(a: AccesObtenu): void {
    this.form = { system: a.system, account: a.account ?? '', grantedBy: a.grantedBy ?? '',
      obtainedAt: a.obtainedAt, expiresAt: a.expiresAt };
    this.editing.set(a.id);
  }

  save(): void {
    const id = this.editing();
    const body: AccesRequest = {
      system: (this.form.system ?? '').trim(),
      account: (this.form.account ?? '').trim(),
      grantedBy: (this.form.grantedBy ?? '').trim(),
      obtainedAt: this.form.obtainedAt || null,
      expiresAt: this.form.expiresAt || null,
    };
    if (!body.system || !id) {
      return;
    }
    this.busy.set(true);
    const call = id === 'new'
      ? this.service.addAcces(this.workspaceId(), body)
      : this.service.updateAcces(this.workspaceId(), id, body);
    call.subscribe({
      next: saved => {
        this.entries.update(list => (id === 'new' ? [saved, ...list] : list.map(x => (x.id === id ? saved : x))));
        this.editing.set(null);
        this.busy.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        // Le refus d'un secret est dit tel quel : c'est la raison la plus probable d'un 400 ici.
        const message = err?.status === 400 && err.error?.message ? err.error.message
          : "L'accès n'a pas pu être enregistré.";
        this.snackBar.open(message, 'Fermer', { duration: 6000 });
      },
    });
  }

  remove(a: AccesObtenu): void {
    this.dialog
      .open<ConfirmDialogComponent, ConfirmDialogData, boolean>(ConfirmDialogComponent, {
        data: {
          title: 'Retirer cet accès du registre ?',
          message: `« ${a.system} » ne sera plus connu de l'agent, qui pourra le redemander.`,
          confirmLabel: 'Retirer',
        },
        width: '480px', maxWidth: '95vw',
      })
      .afterClosed()
      .subscribe(confirmed => {
        if (confirmed !== true) {
          return;
        }
        const before = this.entries();
        this.entries.set(before.filter(x => x.id !== a.id));
        this.service.deleteAcces(this.workspaceId(), a.id).subscribe({
          error: () => {
            this.entries.set(before);
            this.snackBar.open("L'accès n'a pas pu être retiré.", 'Fermer', { duration: 5000 });
          },
        });
      });
  }
}
