import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatListModule } from '@angular/material/list';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatToolbarModule } from '@angular/material/toolbar';

import { ChatService } from '../core/services/chat.service';
import { ChatMessage, ConversationSummary } from '../core/models/chat.models';
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
    MatIconModule,
    MatProgressBarModule,
    MatDialogModule,
  ],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.scss',
})
export class ChatComponent implements OnInit {
  private readonly chatService = inject(ChatService);
  private readonly fb = inject(FormBuilder);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  readonly conversations = signal<ConversationSummary[]>([]);
  readonly messages = signal<ChatMessage[]>([]);
  readonly models = signal<string[]>([]);
  readonly selectedModel = signal<string>('');
  readonly activeConversationId = signal<string | null>(null);
  readonly submitting = signal(false);

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
  }

  /** Charge le détail d'une conversation existante. */
  selectConversation(conversation: ConversationSummary): void {
    this.activeConversationId.set(conversation.id);
    this.selectedModel.set(conversation.model);
    this.chatService.getConversation(conversation.id).subscribe({
      next: (detail) => this.messages.set(detail.messages),
      error: () => this.notifyError('Impossible de charger la conversation.'),
    });
  }

  /** Envoie le message courant et affiche la réponse assistant. */
  send(): void {
    if (this.form.invalid || this.submitting()) {
      return;
    }
    const content = this.form.getRawValue().message.trim();
    if (content.length === 0) {
      return;
    }

    const conversationId = this.activeConversationId();
    // Affichage optimiste du message utilisateur.
    const optimistic: ChatMessage = {
      id: `local-${Date.now()}`,
      role: 'USER',
      content,
      model: null,
      createdAt: new Date().toISOString(),
    };
    this.messages.update((current) => [...current, optimistic]);
    this.form.reset({ message: '' });
    this.submitting.set(true);

    this.chatService
      .sendMessage({
        conversationId,
        message: content,
        model: this.selectedModel() || null,
      })
      .subscribe({
        next: (response) => {
          this.submitting.set(false);
          this.messages.update((current) => [...current, response.message]);
          const isNew = conversationId === null;
          this.activeConversationId.set(response.conversationId);
          if (isNew) {
            this.loadConversations();
          } else {
            this.refreshConversationOrder();
          }
        },
        error: () => {
          this.submitting.set(false);
          // Retire le message optimiste en cas d'échec pour rester cohérent.
          this.messages.update((current) => current.filter((m) => m.id !== optimistic.id));
          this.notifyError('Le message n’a pas pu être envoyé. Veuillez réessayer.');
        },
      });
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

  private notifyError(message: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 4000, panelClass: 'snack-error' });
  }
}
