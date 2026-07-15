import { Component, NgZone, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { MarkdownPipe } from '../shared/markdown.pipe';
import { MessageSegmentsPipe } from '../shared/message-segments.pipe';
import { CopyBlockComponent } from './copy-block/copy-block.component';
import {
  LibraryPickerDialogComponent,
  PickedLibraryDocument,
} from './library-picker/library-picker-dialog.component';
import { ChatService } from '../core/services/chat.service';
import { ExportService } from '../core/services/export.service';
import { UploadService } from '../core/services/upload.service';
import { ChatMessage, ConversationFile, ConversationSummary } from '../core/models/chat.models';
import { ExportFormat } from '../core/models/export.models';

/** Pièce jointe en cours de préparation dans le composer (état local avant envoi). */
export interface ComposerAttachment {
  localId: string;
  filename: string;
  sizeBytes: number;
  serverId?: string;
  status: 'uploading' | 'ready' | 'error';
}
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from './confirm-dialog/confirm-dialog.component';

/**
 * Écran de chat : liste des conversations (gauche), fil de messages (centre), sélecteur de modèle
 * et saisie. Consomme l'API F-02 via {@link ChatService} ; ne communique jamais directement avec
 * un fournisseur IA. Réponse affichée en un bloc (backend non-streamé en V1).
 */
@Component({
  selector: 'app-chat',
  imports: [
    ReactiveFormsModule,
    MatToolbarModule,
    MatListModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatChipsModule,
    MatIconModule,
    MatMenuModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    MatDialogModule,
    MarkdownPipe,
    MessageSegmentsPipe,
    CopyBlockComponent,
  ],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.scss',
})
export class ChatComponent implements OnInit {
  private readonly chatService = inject(ChatService);
  private readonly exportService = inject(ExportService);
  private readonly uploadService = inject(UploadService);
  private readonly fb = inject(FormBuilder);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly zone = inject(NgZone);

  readonly conversations = signal<ConversationSummary[]>([]);
  readonly messages = signal<ChatMessage[]>([]);
  readonly models = signal<string[]>([]);
  readonly selectedModel = signal<string>('');
  readonly activeConversationId = signal<string | null>(null);
  readonly submitting = signal(false);
  readonly attachments = signal<ComposerAttachment[]>([]);

  /** Documents de la bibliothèque personnelle sélectionnés pour import comme contexte (F-24). */
  readonly libraryDocs = signal<PickedLibraryDocument[]>([]);

  /** Dossier de fichiers de la conversation (F-23) : ouverture du panneau + fichiers chargés. */
  readonly filesPanelOpen = signal(false);
  readonly conversationFiles = signal<ConversationFile[]>([]);
  readonly filesLoading = signal(false);

  /** Vrai tant qu'au moins une pièce jointe est en cours de téléversement (bloque l'envoi). */
  readonly uploading = computed(() => this.attachments().some((a) => a.status === 'uploading'));

  readonly activeTitle = computed(() => {
    const id = this.activeConversationId();
    const conversation = this.conversations().find((c) => c.id === id);
    return conversation?.title ?? 'Nouvelle conversation';
  });

  readonly form = this.fb.nonNullable.group({
    message: ['', [Validators.required]],
  });

  ngOnInit(): void {
    this.chatService.getModels().subscribe({
      next: (res) => {
        this.models.set(res.models);
        this.selectedModel.set(res.defaultModel);
      },
      error: () => this.notifyError('Impossible de charger les modèles.'),
    });
    this.loadConversations();
  }

  private loadConversations(): void {
    this.chatService.listConversations().subscribe({
      next: (list) => this.conversations.set(list),
      error: () => this.notifyError('Impossible de charger les conversations.'),
    });
  }

  /** Démarre une nouvelle conversation (vide le fil ; la sélection du modèle reste éditable). */
  startNewConversation(): void {
    this.activeConversationId.set(null);
    this.messages.set([]);
    this.attachments.set([]);
    this.libraryDocs.set([]);
    this.filesPanelOpen.set(false);
    this.conversationFiles.set([]);
  }

  /** Charge le détail d'une conversation existante. */
  selectConversation(conversation: ConversationSummary): void {
    this.activeConversationId.set(conversation.id);
    this.selectedModel.set(conversation.model);
    this.libraryDocs.set([]);
    this.filesPanelOpen.set(false);
    this.conversationFiles.set([]);
    this.chatService.getConversation(conversation.id).subscribe({
      next: (detail) => this.messages.set(detail.messages),
      error: () => this.notifyError('Impossible de charger la conversation.'),
    });
  }

  /**
   * Téléverse les fichiers sélectionnés via {@link UploadService} (mock en test). Chaque fichier
   * apparaît immédiatement comme puce « en cours », puis « prête » ou « en erreur ».
   */
  onFilesPicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = input.files;
    if (!files || files.length === 0) {
      return;
    }
    for (const file of Array.from(files)) {
      const localId = `att-${Date.now()}-${Math.random().toString(36).slice(2)}`;
      this.attachments.update((list) => [
        ...list,
        { localId, filename: file.name, sizeBytes: file.size, status: 'uploading' },
      ]);
      this.uploadService.uploadFile(file).subscribe({
        next: (res) =>
          this.attachments.update((list) =>
            list.map((a) =>
              a.localId === localId ? { ...a, serverId: res.id, status: 'ready' } : a,
            ),
          ),
        error: () => {
          this.attachments.update((list) =>
            list.map((a) => (a.localId === localId ? { ...a, status: 'error' } : a)),
          );
          this.notifyError(`Le fichier « ${file.name} » n’a pas pu être téléversé.`);
        },
      });
    }
    // Réinitialise l'input pour permettre de re-sélectionner le même fichier.
    input.value = '';
  }

  /** Retire une pièce jointe du composer (avant envoi). */
  removeAttachment(localId: string): void {
    this.attachments.update((list) => list.filter((a) => a.localId !== localId));
  }

  /**
   * Ouvre le dialogue de sélection de documents de la bibliothèque (F-24). Les documents choisis
   * sont fusionnés avec la sélection courante (sans doublon) et injectés comme contexte à l'envoi.
   */
  openLibraryPicker(): void {
    this.dialog
      .open(LibraryPickerDialogComponent, { width: '560px', autoFocus: false })
      .afterClosed()
      .subscribe((picked: PickedLibraryDocument[] | undefined) => {
        if (!picked || picked.length === 0) {
          return;
        }
        this.libraryDocs.update((current) => {
          const known = new Set(current.map((d) => d.id));
          return [...current, ...picked.filter((d) => !known.has(d.id))];
        });
      });
  }

  /** Retire un document de bibliothèque de la sélection courante (avant envoi). */
  removeLibraryDoc(id: string): void {
    this.libraryDocs.update((list) => list.filter((d) => d.id !== id));
  }

  /** Envoie le message courant et affiche la réponse assistant. */
  send(): void {
    if (this.form.invalid || this.submitting() || this.uploading()) {
      return;
    }
    const content = this.form.getRawValue().message.trim();
    if (content.length === 0) {
      return;
    }
    const attachmentIds = this.attachments()
      .filter((a) => a.status === 'ready' && a.serverId)
      .map((a) => a.serverId as string);
    const libraryDocumentIds = this.libraryDocs().map((d) => d.id);

    const conversationId = this.activeConversationId();
    const now = new Date().toISOString();
    // Affichage optimiste : message utilisateur + placeholder assistant rempli au fil du flux.
    const optimistic: ChatMessage = {
      id: `local-${Date.now()}`,
      role: 'USER',
      content,
      model: null,
      createdAt: now,
    };
    const assistantId = `local-assistant-${Date.now()}`;
    const assistant: ChatMessage = {
      id: assistantId,
      role: 'ASSISTANT',
      content: '',
      model: this.selectedModel() || null,
      createdAt: now,
    };
    this.messages.update((current) => [...current, optimistic, assistant]);
    this.form.reset({ message: '' });
    this.submitting.set(true);
    let streamedAny = false;

    // Streaming SSE : les fragments alimentent le placeholder assistant en temps réel (SF-02-05).
    // Les callbacks passent par NgZone pour déclencher la détection de changement (fetch hors zone).
    void this.chatService.streamMessage(
      {
        conversationId,
        message: content,
        model: this.selectedModel() || null,
        ...(attachmentIds.length > 0 ? { attachmentIds } : {}),
        ...(libraryDocumentIds.length > 0 ? { libraryDocumentIds } : {}),
      },
      {
        onToken: (text) =>
          this.zone.run(() => {
            streamedAny = true;
            this.messages.update((current) =>
              current.map((m) => (m.id === assistantId ? { ...m, content: m.content + text } : m)),
            );
          }),
        onDone: (done) =>
          this.zone.run(() => {
            this.submitting.set(false);
            this.attachments.set([]);
            this.libraryDocs.set([]);
            this.messages.update((current) =>
              current.map((m) =>
                m.id === assistantId ? { ...m, id: done.messageId, model: done.model } : m,
              ),
            );
            const isNew = conversationId === null;
            this.activeConversationId.set(done.conversationId);
            if (isNew) {
              this.loadConversations();
            } else {
              this.refreshConversationOrder();
            }
            // Si le dossier de fichiers est ouvert, refléter les pièces jointes de ce tour (F-23).
            if (this.filesPanelOpen()) {
              this.loadConversationFiles();
            }
          }),
        onError: (code) =>
          this.zone.run(() => {
            this.submitting.set(false);
            // Retire le placeholder assistant ; retire aussi le message utilisateur si rien n'a été
            // streamé (échec de pré-vol : rien n'a été persisté côté serveur).
            this.messages.update((current) =>
              current.filter((m) => m.id !== assistantId && (streamedAny || m.id !== optimistic.id)),
            );
            this.notifyError(this.mapStreamError(code));
          }),
      },
    );
  }

  /** Traduit un code d'erreur du flux SSE de chat en message utilisateur lisible. */
  private mapStreamError(code: string): string {
    switch (code) {
      case 'quota_exceeded':
        return 'Quota de consommation atteint. Rachetez des tokens ou attendez la prochaine période.';
      case 'unsupported_model':
        return 'Le modèle sélectionné n’est pas disponible.';
      case 'document_not_ready':
        return 'Un document importé est encore en cours de traitement. Réessayez dans un instant.';
      case 'not_found':
        return 'Conversation ou pièce jointe introuvable.';
      case 'provider_unavailable':
      case 'provider_error':
        return 'Le service IA est momentanément indisponible. Veuillez réessayer.';
      default:
        return 'Le message n’a pas pu être envoyé. Veuillez réessayer.';
    }
  }

  private refreshConversationOrder(): void {
    this.chatService.listConversations().subscribe({
      next: (list) => this.conversations.set(list),
    });
  }

  /** Ouvre un dialog de confirmation puis supprime la conversation. */
  confirmDelete(conversation: ConversationSummary, event: Event): void {
    event.stopPropagation();
    const data: ConfirmDialogData = {
      title: 'Supprimer la conversation',
      message: `« ${conversation.title} » et ses messages seront définitivement supprimés.`,
      confirmLabel: 'Supprimer',
    };
    this.dialog
      .open(ConfirmDialogComponent, { data, width: '420px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          this.deleteConversation(conversation);
        }
      });
  }

  private deleteConversation(conversation: ConversationSummary): void {
    this.chatService.deleteConversation(conversation.id).subscribe({
      next: () => {
        this.conversations.update((list) => list.filter((c) => c.id !== conversation.id));
        if (this.activeConversationId() === conversation.id) {
          this.startNewConversation();
        }
        this.snackBar.open('Conversation supprimée.', 'Fermer', { duration: 3000 });
      },
      error: () => this.notifyError('La suppression a échoué.'),
    });
  }

  /** Exporte la conversation active au format demandé et déclenche le téléchargement (F-14). */
  exportConversation(format: ExportFormat): void {
    const id = this.activeConversationId();
    if (!id) {
      return;
    }
    this.exportService.exportConversation(id, format).subscribe({
      next: (response) =>
        this.exportService.triggerDownload(response, `conversation-${id}.${format === 'pdf' ? 'pdf' : 'md'}`),
      error: () => this.notifyError('L’export a échoué. Veuillez réessayer.'),
    });
  }

  /** Ouvre/ferme le dossier de fichiers de la conversation active (F-23) ; charge au premier affichage. */
  toggleFilesPanel(): void {
    const opening = !this.filesPanelOpen();
    this.filesPanelOpen.set(opening);
    if (opening) {
      this.loadConversationFiles();
    }
  }

  /** Charge les fichiers rattachés à la conversation active via `GET /api/conversations/{id}/files`. */
  loadConversationFiles(): void {
    const id = this.activeConversationId();
    if (!id) {
      return;
    }
    this.filesLoading.set(true);
    this.chatService.getConversationFiles(id).subscribe({
      next: (files) => {
        this.conversationFiles.set(files);
        this.filesLoading.set(false);
      },
      error: () => {
        this.filesLoading.set(false);
        this.notifyError('Impossible de charger les fichiers de la conversation.');
      },
    });
  }

  /** Taille lisible d'un fichier (o / Ko / Mo), sans dépendance de formatage. */
  formatFileSize(bytes: number): string {
    if (bytes < 1024) {
      return `${bytes} o`;
    }
    if (bytes < 1024 * 1024) {
      return `${(bytes / 1024).toFixed(1)} Ko`;
    }
    return `${(bytes / (1024 * 1024)).toFixed(1)} Mo`;
  }

  private notifyError(message: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 4000, panelClass: 'snack-error' });
  }
}
