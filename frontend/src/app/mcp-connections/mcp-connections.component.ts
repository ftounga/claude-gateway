import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { HttpErrorResponse } from '@angular/common/http';

import { McpConnectionsService } from '../core/services/mcp-connections.service';
import {
  CreatedMcpToken,
  MCP_SCOPES,
  McpHostOption,
  McpJournalEntry,
  McpToken,
} from '../core/models/mcp-connections.models';
import {
  RevokeTokenDialogComponent,
  RevokeTokenDialogData,
} from './revoke-token-dialog.component';

/**
 * Écran « IA connectées » (F-112 / SF-112-03) : créer un jeton personnel MCP (nom, périmètres, postes,
 * expiration obligatoire), le voir une seule fois, lister et révoquer ses jetons, lire le journal MCP.
 *
 * <p>Conforme au design system : MatCard, MatFormField `outline`, MatSnackBar, confirmation destructive
 * via MatDialog, couleurs de la charte uniquement. L'isolation `user_id` est portée par le backend.</p>
 */
@Component({
  selector: 'app-mcp-connections',
  imports: [
    ReactiveFormsModule,
    DatePipe,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatCheckboxModule,
  ],
  templateUrl: './mcp-connections.component.html',
  styleUrl: './mcp-connections.component.scss',
})
export class McpConnectionsComponent implements OnInit {
  private readonly service = inject(McpConnectionsService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  readonly scopeOptions = MCP_SCOPES;
  readonly expiryChoices = [7, 30, 60, 90];

  readonly tokens = signal<McpToken[]>([]);
  readonly hosts = signal<McpHostOption[]>([]);
  readonly journal = signal<McpJournalEntry[]>([]);
  readonly createdSecret = signal<CreatedMcpToken | null>(null);
  readonly submitting = signal(false);

  readonly selectedScopes = signal<Set<string>>(new Set(['compte:lire']));
  readonly selectedHosts = signal<Set<string>>(new Set());

  readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(120)],
    }),
    expiresInDays: new FormControl(30, {
      nonNullable: true,
      validators: [Validators.required],
    }),
  });

  ngOnInit(): void {
    this.reload();
    this.service.hosts().subscribe({
      next: (hosts) => this.hosts.set(hosts),
      error: () => this.hosts.set([]),
    });
  }

  private reload(): void {
    this.service.listTokens().subscribe({
      next: (tokens) => this.tokens.set(tokens),
      error: () => this.notify('Impossible de charger les jetons.'),
    });
    this.service.journal().subscribe({
      next: (entries) => this.journal.set(entries),
      error: () => this.journal.set([]),
    });
  }

  scopeLabel(value: string): string {
    return this.scopeOptions.find((s) => s.value === value)?.label ?? value;
  }

  hostName(id: string): string {
    return this.hosts().find((h) => h.id === id)?.name ?? id;
  }

  toggleScope(value: string, checked: boolean): void {
    const next = new Set(this.selectedScopes());
    if (checked) {
      next.add(value);
    } else {
      next.delete(value);
    }
    this.selectedScopes.set(next);
  }

  toggleHost(id: string, checked: boolean): void {
    const next = new Set(this.selectedHosts());
    if (checked) {
      next.add(id);
    } else {
      next.delete(id);
    }
    this.selectedHosts.set(next);
  }

  create(): void {
    if (this.form.invalid || this.selectedScopes().size === 0) {
      this.notify('Un nom, au moins un périmètre et une expiration sont requis.');
      return;
    }
    this.submitting.set(true);
    this.createdSecret.set(null);
    this.service
      .createToken({
        name: this.form.controls.name.value.trim(),
        scopes: [...this.selectedScopes()],
        hostIds: [...this.selectedHosts()],
        expiresInDays: this.form.controls.expiresInDays.value,
      })
      .subscribe({
        next: (created) => {
          this.submitting.set(false);
          this.createdSecret.set(created);
          this.form.controls.name.reset('');
          this.selectedHosts.set(new Set());
          this.reload();
        },
        error: (err: HttpErrorResponse) => {
          this.submitting.set(false);
          this.notify(err.error?.message ?? 'Création impossible.');
        },
      });
  }

  copySecret(): void {
    const secret = this.createdSecret()?.secret;
    if (secret && navigator.clipboard) {
      navigator.clipboard
        .writeText(secret)
        .then(() => this.notify('Jeton copié.'))
        .catch(() => this.notify('Copie impossible — sélectionnez le jeton à la main.'));
    }
  }

  dismissSecret(): void {
    this.createdSecret.set(null);
  }

  revoke(token: McpToken): void {
    const data: RevokeTokenDialogData = { tokenName: token.name };
    this.dialog
      .open(RevokeTokenDialogComponent, { data, width: '440px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          this.service.revokeToken(token.id).subscribe({
            next: () => {
              this.notify('Jeton révoqué.');
              this.reload();
            },
            error: () => this.notify('Révocation impossible.'),
          });
        }
      });
  }

  private notify(message: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 5000 });
  }
}
