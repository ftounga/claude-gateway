import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { MarkdownPipe } from '../shared/markdown.pipe';
import { MessageSegmentsPipe } from '../shared/message-segments.pipe';
import { httpErrorMessage, MAX_UPLOAD_BYTES, oversizeMessage } from '../shared/http-error.util';
import { CopyBlockComponent } from '../chat/copy-block/copy-block.component';
import { AtelierService } from '../core/services/atelier.service';
import {
  AtelierAction,
  AtelierRole,
  WorkspaceSummary,
} from '../core/models/atelier.models';

/** Élément du fil de conversation de l'Atelier : un tour (message + éventuelles actions fichier). */
export interface AtelierThreadItem {
  id: string;
  role: AtelierRole;
  content: string;
  actions: AtelierAction[];
}

/**
 * Écran « Atelier » (F-28, Claude Code Lite). L'utilisateur téléverse un projet `.zip` et discute
 * avec Claude qui lit/édite les fichiers du workspace. Flux unique de conversation (façon Claude
 * Code) + panneau « Fichiers » repliable pour prévisualiser/éditer un fichier.
 *
 * <p>Consomme l'API F-28 via {@link AtelierService} ; ne communique jamais directement avec un
 * fournisseur IA. Isolation `user_id` garantie côté backend.</p>
 */
@Component({
  selector: 'app-atelier',
  imports: [
    FormsModule,
    MatToolbarModule,
    MatListModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MarkdownPipe,
    MessageSegmentsPipe,
    CopyBlockComponent,
  ],
  templateUrl: './atelier.component.html',
  styleUrl: './atelier.component.scss',
})
export class AtelierComponent implements OnInit {
  private readonly atelier = inject(AtelierService);
  private readonly snackBar = inject(MatSnackBar);

  readonly workspaces = signal<WorkspaceSummary[]>([]);
  readonly activeWorkspaceId = signal<string | null>(null);
  readonly tree = signal<string[]>([]);
  readonly messages = signal<AtelierThreadItem[]>([]);

  /** Saisie du composer (liaison bidirectionnelle simple, façon Claude Code). */
  readonly draft = signal('');

  readonly creating = signal(false);
  readonly submitting = signal(false);

  /** Panneau « Fichiers » repliable + aperçu/édition du fichier sélectionné. */
  readonly filesPanelOpen = signal(false);
  readonly selectedFilePath = signal<string | null>(null);
  readonly fileContent = signal('');
  readonly fileLoading = signal(false);
  readonly fileSaving = signal(false);

  readonly activeName = computed(() => {
    const id = this.activeWorkspaceId();
    return this.workspaces().find((w) => w.id === id)?.name ?? '';
  });

  ngOnInit(): void {
    this.loadWorkspaces();
  }

  private loadWorkspaces(): void {
    this.atelier.listWorkspaces().subscribe({
      next: (list) => this.workspaces.set(list),
      error: () => this.notifyError('Impossible de charger les projets.'),
    });
  }

  /** Téléverse l'archive `.zip` sélectionnée → crée le workspace, l'ouvre et rafraîchit la liste. */
  onZipPicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    // Contrôle client : inutile d'envoyer une archive manifestement hors limite (l'ingress la
    // couperait avec un 413 opaque). On explique la cause et l'action corrective immédiatement.
    if (file.size > MAX_UPLOAD_BYTES) {
      this.notifyError(oversizeMessage(file.size));
      return;
    }
    this.creating.set(true);
    this.atelier.createWorkspace(file).subscribe({
      next: (workspace) => {
        this.creating.set(false);
        this.workspaces.update((list) => [
          { id: workspace.id, name: workspace.name, createdAt: workspace.createdAt },
          ...list.filter((w) => w.id !== workspace.id),
        ]);
        this.activeWorkspaceId.set(workspace.id);
        this.tree.set(workspace.files);
        this.messages.set([]);
        this.resetFilePanel();
        this.snackBar.open('Projet importé.', 'Fermer', { duration: 3000 });
      },
      error: (err) => {
        this.creating.set(false);
        this.notifyError(
          httpErrorMessage(err, "L'import du projet a échoué. Vérifiez qu'il s'agit d'une archive .zip."),
        );
      },
    });
  }

  /** Ouvre un workspace : charge l'historique de conversation puis l'arborescence. */
  selectWorkspace(workspace: WorkspaceSummary): void {
    if (this.activeWorkspaceId() === workspace.id) {
      return;
    }
    this.activeWorkspaceId.set(workspace.id);
    this.messages.set([]);
    this.tree.set([]);
    this.resetFilePanel();
    this.loadHistory(workspace.id);
    this.refreshTree(workspace.id);
  }

  private loadHistory(id: string): void {
    this.atelier.getHistory(id).subscribe({
      next: (history) =>
        this.messages.set(
          history.map((m) => ({ id: m.id, role: m.role, content: m.content, actions: [] })),
        ),
      error: () => this.notifyError("Impossible de charger l'historique de conversation."),
    });
  }

  private refreshTree(id: string): void {
    this.atelier.getWorkspace(id).subscribe({
      next: (detail) => this.tree.set(detail.files),
      error: () => this.notifyError("Impossible de charger l'arborescence du projet."),
    });
  }

  /** Envoie le message courant ; à la réponse, ajoute le tour et rafraîchit l'arborescence. */
  send(): void {
    const id = this.activeWorkspaceId();
    const content = this.draft().trim();
    if (!id || this.submitting() || content.length === 0) {
      return;
    }
    const userItem: AtelierThreadItem = {
      id: `local-user-${Date.now()}`,
      role: 'USER',
      content,
      actions: [],
    };
    this.messages.update((current) => [...current, userItem]);
    this.draft.set('');
    this.submitting.set(true);

    this.atelier.chat(id, content).subscribe({
      next: (response) => {
        this.submitting.set(false);
        this.messages.update((current) => [
          ...current,
          {
            id: response.messageId,
            role: 'ASSISTANT',
            content: response.reply,
            actions: response.actions ?? [],
          },
        ]);
        // Un tour a pu écrire des fichiers : rafraîchir l'arborescence (et l'aperçu ouvert).
        this.refreshTree(id);
        const openPath = this.selectedFilePath();
        if (openPath && (response.actions ?? []).some((a) => a.type === 'write' && a.path === openPath)) {
          this.openFile(openPath);
        }
      },
      error: (err) => {
        this.submitting.set(false);
        // Retire le message utilisateur optimiste : rien n'a été persisté côté serveur.
        this.messages.update((current) => current.filter((m) => m.id !== userItem.id));
        this.notifyError(httpErrorMessage(err, "Le message n'a pas pu être envoyé. Veuillez réessayer."));
      },
    });
  }

  /** Ouvre/ferme le panneau « Fichiers ». */
  toggleFilesPanel(): void {
    this.filesPanelOpen.update((open) => !open);
  }

  /** Charge le contenu d'un fichier dans l'aperçu éditable. */
  openFile(path: string): void {
    const id = this.activeWorkspaceId();
    if (!id) {
      return;
    }
    this.selectedFilePath.set(path);
    this.fileLoading.set(true);
    this.atelier.getFile(id, path).subscribe({
      next: (file) => {
        this.fileContent.set(file.content);
        this.fileLoading.set(false);
      },
      error: () => {
        this.fileLoading.set(false);
        this.notifyError('Impossible de charger le fichier.');
      },
    });
  }

  /** Enregistre le contenu édité du fichier sélectionné. */
  saveFile(): void {
    const id = this.activeWorkspaceId();
    const path = this.selectedFilePath();
    if (!id || !path || this.fileSaving()) {
      return;
    }
    this.fileSaving.set(true);
    this.atelier.writeFile(id, path, this.fileContent()).subscribe({
      next: () => {
        this.fileSaving.set(false);
        this.snackBar.open('Fichier enregistré.', 'Fermer', { duration: 2000 });
      },
      error: () => {
        this.fileSaving.set(false);
        this.notifyError("L'enregistrement du fichier a échoué.");
      },
    });
  }

  /** Icône Material pour une action fichier (lecture / écriture). */
  actionIcon(type: string): string {
    return type === 'write' ? 'edit' : 'visibility';
  }

  /** Libellé humain d'une action fichier. */
  actionLabel(action: AtelierAction): string {
    return action.type === 'write' ? `${action.path} modifié` : `${action.path} lu`;
  }

  private resetFilePanel(): void {
    this.selectedFilePath.set(null);
    this.fileContent.set('');
    this.fileLoading.set(false);
  }

  private notifyError(message: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 4000, panelClass: 'snack-error' });
  }
}
