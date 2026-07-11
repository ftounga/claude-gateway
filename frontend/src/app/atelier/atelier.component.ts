import { HttpErrorResponse } from '@angular/common/http';
import { Component, NgZone, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { MarkdownPipe } from '../shared/markdown.pipe';
import { MessageSegmentsPipe } from '../shared/message-segments.pipe';
import { httpErrorMessage, MAX_UPLOAD_BYTES, oversizeMessage } from '../shared/http-error.util';
import { CopyBlockComponent } from '../chat/copy-block/copy-block.component';
import {
  LibraryPickerDialogComponent,
  PickedLibraryDocument,
} from '../chat/library-picker/library-picker-dialog.component';
import { ApiKeyService } from '../core/services/api-key.service';
import { AtelierService } from '../core/services/atelier.service';
import { ProviderMode } from '../core/models/api-key.models';
import {
  AtelierAction,
  AtelierAgentStreamAction,
  AtelierRole,
  AtelierStreamAction,
  WorkspaceSummary,
} from '../core/models/atelier.models';

/** Mode de l'agent : « Édition » (Phase 1, lecture/écriture) ou « Exécution » (Phase 2, sandbox). */
export type AtelierAgentMode = 'edit' | 'exec';

/**
 * Extensions texte/code acceptées à l'ajout d'un fichier depuis le PC (SF-28-13). Le workspace est
 * textuel (`readFile`/`writeFile` = String) : les binaires (PDF, image) passent par la bibliothèque
 * après OCR. Sert à la fois d'attribut `accept` et de garde-fou client anti-binaire.
 */
export const WORKSPACE_TEXT_EXTENSIONS: readonly string[] = [
  'txt', 'md', 'markdown', 'js', 'ts', 'tsx', 'jsx', 'java', 'py', 'json', 'html', 'htm', 'css',
  'scss', 'sass', 'less', 'xml', 'yml', 'yaml', 'sh', 'bash', 'go', 'rb', 'php', 'c', 'cpp', 'cc',
  'h', 'hpp', 'cs', 'kt', 'kts', 'rs', 'swift', 'sql', 'toml', 'ini', 'cfg', 'conf', 'properties',
  'env', 'gradle', 'csv', 'tsv', 'vue', 'svelte', 'pl', 'r', 'lua', 'dart', 'scala', 'gql',
  'graphql', 'proto', 'log', 'text',
];

/** Attribut `accept` du sélecteur de fichier PC, dérivé de {@link WORKSPACE_TEXT_EXTENSIONS}. */
export const WORKSPACE_TEXT_ACCEPT = WORKSPACE_TEXT_EXTENSIONS.map((e) => `.${e}`).join(',');

/** Élément du fil de conversation de l'Atelier : un tour (message + éventuelles actions fichier). */
export interface AtelierThreadItem {
  id: string;
  role: AtelierRole;
  content: string;
  actions: AtelierAction[];
  /** Chemins des fichiers modifiés par une session d'exécution (mode « Exécution », SF-28-11). */
  changedFiles?: string[];
}

/** Tour assistant « en cours » pendant le streaming : étapes relayées + commentaire partiel. */
export interface AtelierStreamingItem {
  steps: AtelierStreamAction[];
  text: string;
}

/**
 * Tour assistant « en cours » du mode « Exécution » (SF-28-11) : état de la session, étapes d'outils
 * (bash, tests…) relayées au fil de l'eau, et commentaire partiel de l'agent.
 */
export interface AtelierExecStreamingItem {
  status: string;
  steps: AtelierAgentStreamAction[];
  text: string;
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
    MatButtonToggleModule,
    MatIconModule,
    MatMenuModule,
    MatProgressBarModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
    RouterLink,
    MarkdownPipe,
    MessageSegmentsPipe,
    CopyBlockComponent,
  ],
  templateUrl: './atelier.component.html',
  styleUrl: './atelier.component.scss',
})
export class AtelierComponent implements OnInit {
  private readonly atelier = inject(AtelierService);
  private readonly apiKeyService = inject(ApiKeyService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly zone = inject(NgZone);
  private readonly dialog = inject(MatDialog);

  /** Attribut `accept` du sélecteur de fichier PC (texte/code uniquement, SF-28-13). */
  readonly workspaceTextAccept = WORKSPACE_TEXT_ACCEPT;

  /**
   * Accès refusé par le backend (403 `atelier_forbidden`, SF-28-06) : l'utilisateur n'est pas Gold.
   * Déclenche l'affichage du panneau d'upsell au lieu de l'écran Atelier.
   */
  readonly accessDenied = signal(false);

  /** Mode d'exécution effectif (indicateur de tête d'écran) : BYOK « vos tokens » vs Hosted « inclus ». */
  readonly providerMode = signal<ProviderMode>('HOSTED');

  /** Clé masquée (`sk-…last4`) à afficher en mode BYOK, si disponible. */
  readonly maskedKey = signal<string | null>(null);

  readonly workspaces = signal<WorkspaceSummary[]>([]);
  readonly activeWorkspaceId = signal<string | null>(null);
  readonly tree = signal<string[]>([]);
  readonly messages = signal<AtelierThreadItem[]>([]);

  /** Tour assistant « en cours » (étapes + texte partiel) affiché pendant le streaming (SF-28-05). */
  readonly streaming = signal<AtelierStreamingItem | null>(null);

  /**
   * Mode de l'agent (SF-28-11). « Édition » (défaut, Phase 1) : Claude lit/édite les fichiers.
   * « Exécution » (Phase 2) : Claude exécute réellement (bash, tests, build) dans un sandbox hébergé.
   */
  readonly agentMode = signal<AtelierAgentMode>('edit');

  /** Tour assistant « en cours » du mode « Exécution » (état + étapes d'outils + texte partiel). */
  readonly execStreaming = signal<AtelierExecStreamingItem | null>(null);

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
      next: (list) => {
        this.workspaces.set(list);
        // Accès accordé : charger le mode d'exécution pour l'indicateur de tête d'écran.
        this.loadProviderMode();
      },
      error: (err) => {
        // 403 `atelier_forbidden` (non-Gold) : upsell silencieux, sans snackbar d'erreur (SF-28-06).
        if (this.isAtelierForbidden(err)) {
          this.accessDenied.set(true);
          return;
        }
        this.notifyError('Impossible de charger les projets.');
      },
    });
  }

  /** Vrai si l'erreur est le 403 de gating Gold renvoyé par le backend (`atelier_forbidden`). */
  private isAtelierForbidden(err: unknown): boolean {
    return (
      err instanceof HttpErrorResponse &&
      err.status === 403 &&
      (err.error as { error?: string } | null)?.error === 'atelier_forbidden'
    );
  }

  /**
   * Charge le statut de clé pour déterminer le mode d'exécution affiché.
   * BYOK si le mode renvoyé vaut `BYOK` **ou** si une clé est présente (`maskedKey` non-null) : le
   * backend ne facture pas ces tokens sur le quota Hosted, l'utilisateur doit le voir clairement.
   * Échec silencieux → repli sur Hosted (non bloquant, l'écran reste utilisable).
   */
  private loadProviderMode(): void {
    this.apiKeyService.getStatus().subscribe({
      next: (status) => {
        this.maskedKey.set(status.maskedKey);
        this.providerMode.set(status.mode === 'BYOK' || status.maskedKey !== null ? 'BYOK' : 'HOSTED');
      },
      error: () => this.providerMode.set('HOSTED'),
    });
  }

  /** Redirige vers l'écran de facturation pour souscrire l'offre Gold. */
  goToBilling(): void {
    this.router.navigate(['/billing']);
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

  /**
   * Ajoute un fichier **texte/code** du PC au workspace actif (SF-28-13). Les binaires (PDF, image)
   * sont refusés côté client avec un message orientant vers la bibliothèque : le workspace est
   * textuel et l'OCR relève de la bibliothèque (F-08), pas de l'Atelier (Provider-First). Lecture
   * via {@link FileReader#readAsText} puis `writeFile` (endpoint existant `PUT /file`).
   */
  async onWorkspaceFilePicked(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    const id = this.activeWorkspaceId();
    if (!file || !id) {
      return;
    }
    if (this.isBinaryWorkspaceFile(file)) {
      this.notifyError("Les fichiers binaires (PDF, image) s'ajoutent via la bibliothèque après OCR.");
      return;
    }
    let content: string;
    try {
      content = await this.readAsText(file);
    } catch {
      this.notifyError('Impossible de lire le fichier sélectionné.');
      return;
    }
    this.atelier.writeFile(id, file.name, content).subscribe({
      next: () => {
        this.refreshTree(id);
        this.snackBar.open('Fichier ajouté.', 'Fermer', { duration: 3000 });
      },
      error: (err) => this.notifyError(httpErrorMessage(err, "L'ajout du fichier a échoué.")),
    });
  }

  /**
   * Ouvre le sélecteur de documents de la bibliothèque (réutilise {@link LibraryPickerDialogComponent}
   * de F-24) et importe le texte des documents choisis dans le workspace actif via `importLibrary`
   * (SF-28-13). L'isolation (workspace possédé + documents possédés) est garantie côté backend.
   */
  openWorkspaceLibraryPicker(): void {
    const id = this.activeWorkspaceId();
    if (!id) {
      return;
    }
    this.dialog
      .open(LibraryPickerDialogComponent, { width: '560px', autoFocus: false })
      .afterClosed()
      .subscribe((picked: PickedLibraryDocument[] | undefined) => {
        if (!picked || picked.length === 0) {
          return;
        }
        this.atelier.importLibrary(id, picked.map((d) => d.id)).subscribe({
          next: (detail) => {
            this.tree.set(detail.files);
            this.snackBar.open(
              picked.length > 1 ? 'Documents importés.' : 'Document importé.',
              'Fermer',
              { duration: 3000 },
            );
          },
          error: (err) => this.notifyError(httpErrorMessage(err, 'Import impossible.')),
        });
      });
  }

  /**
   * Détecte un fichier binaire (à refuser à l'ajout PC) : type MIME image/audio/vidéo/PDF/archive/
   * binaire générique, ou extension absente de la liste texte/code autorisée quand le MIME n'est pas
   * `text/*`. Conservateur : en cas de doute, on oriente vers la bibliothèque.
   */
  private isBinaryWorkspaceFile(file: File): boolean {
    const type = (file.type || '').toLowerCase();
    if (type.startsWith('image/') || type.startsWith('audio/') || type.startsWith('video/')) {
      return true;
    }
    if (type === 'application/pdf' || type === 'application/zip' || type === 'application/octet-stream') {
      return true;
    }
    const dot = file.name.lastIndexOf('.');
    const ext = dot >= 0 ? file.name.slice(dot + 1).toLowerCase() : '';
    return !WORKSPACE_TEXT_EXTENSIONS.includes(ext) && !type.startsWith('text/');
  }

  /** Lit un fichier en texte (UTF-8) via {@link FileReader}, sous forme de promesse testable. */
  private readAsText(file: File): Promise<string> {
    return new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result ?? ''));
      reader.onerror = () => reject(reader.error ?? new Error('read_error'));
      reader.readAsText(file);
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

  /**
   * Envoie le message courant en **streaming** (SF-28-05) : affiche les étapes (lecture/écriture/…)
   * et le commentaire au fil de l'eau, puis remplace par la réponse finale et rafraîchit l'arborescence.
   * Le flux tourne hors zone Angular (fetch) : chaque mise à jour de signal passe par {@link NgZone}.
   */
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

    // Mode « Exécution » (Phase 2, SF-28-11) : délègue au flux d'agent (sandbox hébergé). Le mode
    // « Édition » (défaut, Phase 1) reste strictement inchangé ci-dessous.
    if (this.agentMode() === 'exec') {
      this.sendExec(id, content, userItem);
      return;
    }

    this.streaming.set({ steps: [], text: '' });

    void this.atelier.streamChat(id, content, {
      onAction: (action) =>
        this.zone.run(() => {
          this.streaming.update((current) =>
            current ? { ...current, steps: [...current.steps, action] } : current,
          );
        }),
      onText: (text) =>
        this.zone.run(() => {
          this.streaming.update((current) =>
            current ? { ...current, text: current.text + text } : current,
          );
        }),
      onDone: (done) =>
        this.zone.run(() => {
          this.submitting.set(false);
          this.streaming.set(null);
          this.messages.update((current) => [
            ...current,
            {
              id: done.messageId,
              role: 'ASSISTANT',
              content: done.reply,
              actions: done.actions ?? [],
            },
          ]);
          // Un tour a pu écrire des fichiers : rafraîchir l'arborescence (et l'aperçu ouvert).
          this.refreshTree(id);
          const openPath = this.selectedFilePath();
          if (openPath && (done.actions ?? []).some((a) => a.type === 'write' && a.path === openPath)) {
            this.openFile(openPath);
          }
        }),
      onError: (code) =>
        this.zone.run(() => {
          this.submitting.set(false);
          this.streaming.set(null);
          // Retire le message utilisateur optimiste : rien n'a été persisté côté serveur.
          this.messages.update((current) => current.filter((m) => m.id !== userItem.id));
          this.notifyError(this.streamErrorMessage(code));
        }),
    });
  }

  /** Traduit un code d'erreur de flux en message utilisateur lisible (SF-28-05). */
  private streamErrorMessage(code: string): string {
    switch (code) {
      case 'quota_exceeded':
        return 'Quota de consommation atteint. Rachetez des tokens ou attendez la prochaine période.';
      case 'workspace_not_found':
        return 'Projet introuvable.';
      case 'provider_unavailable':
      case 'provider_error':
        return 'Le service IA est momentanément indisponible.';
      default:
        return "Le message n'a pas pu être envoyé. Veuillez réessayer.";
    }
  }

  /** Bascule le mode de l'agent (« Édition » ⇄ « Exécution »), sauf pendant un envoi en cours. */
  setAgentMode(mode: AtelierAgentMode): void {
    if (this.submitting()) {
      return;
    }
    this.agentMode.set(mode);
  }

  /**
   * Envoie le message courant en mode **Exécution** (SF-28-11) : relaie l'état de session, les étapes
   * d'outils (bash, tests…) et le commentaire au fil de l'eau, puis ajoute la réponse finale et la
   * liste des fichiers modifiés, et rafraîchit l'arborescence. Le flux tourne hors zone Angular
   * (fetch) : chaque mise à jour de signal passe par {@link NgZone}. En cas d'erreur, le tour
   * utilisateur optimiste est retiré (rien n'a été persisté) et un message lisible est affiché.
   */
  private sendExec(id: string, content: string, userItem: AtelierThreadItem): void {
    this.execStreaming.set({ status: '', steps: [], text: '' });

    void this.atelier.streamAgent(id, content, {
      onStatus: (state) =>
        this.zone.run(() => {
          this.execStreaming.update((current) => (current ? { ...current, status: state } : current));
        }),
      onAction: (action) =>
        this.zone.run(() => {
          this.execStreaming.update((current) =>
            current ? { ...current, steps: [...current.steps, action] } : current,
          );
        }),
      onAgent: (text) =>
        this.zone.run(() => {
          this.execStreaming.update((current) =>
            current ? { ...current, text: current.text + text } : current,
          );
        }),
      onDone: (done) =>
        this.zone.run(() => {
          this.submitting.set(false);
          this.execStreaming.set(null);
          this.messages.update((current) => [
            ...current,
            {
              id: `local-assistant-${Date.now()}`,
              role: 'ASSISTANT',
              content: done.reply,
              actions: [],
              changedFiles: done.changedFiles ?? [],
            },
          ]);
          // La session a pu exécuter du code et modifier des fichiers : rafraîchir l'arborescence.
          this.refreshTree(id);
        }),
      onError: (code) =>
        this.zone.run(() => {
          this.submitting.set(false);
          this.execStreaming.set(null);
          // Retire le message utilisateur optimiste : rien n'a été persisté côté serveur.
          this.messages.update((current) => current.filter((m) => m.id !== userItem.id));
          this.notifyError(this.mapAgentError(code));
        }),
    });
  }

  /** Traduit un code d'erreur du flux d'exécution en message utilisateur lisible (SF-28-11). */
  private mapAgentError(code: string): string {
    switch (code) {
      case 'forbidden':
        return "L'exécution est réservée à l'offre Gold.";
      case 'agent_disabled':
        return 'Le mode Exécution est momentanément indisponible.';
      case 'workspace_not_found':
        return 'Projet introuvable.';
      case 'session_timeout':
        return 'La session a dépassé le temps imparti. Réessayez sur une tâche plus courte.';
      default:
        return "L'exécution a échoué. Veuillez réessayer.";
    }
  }

  /** Libellé d'une étape d'exécution en cours (`tool` + `detail`, ex. « bash: npm test »). */
  execStepLabel(step: AtelierAgentStreamAction): string {
    return step.detail ? `${step.tool}: ${step.detail}` : step.tool;
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

  /** Icône Material d'une étape de streaming (lecture / écriture / liste / recherche). */
  stepIcon(type: string): string {
    switch (type) {
      case 'write':
        return 'edit';
      case 'list':
        return 'folder_open';
      case 'search':
        return 'search';
      default:
        return 'visibility';
    }
  }

  /** Libellé humain d'une étape de streaming en cours. */
  stepLabel(step: AtelierStreamAction): string {
    switch (step.type) {
      case 'write':
        return `Édition de ${step.path}`;
      case 'list':
        return 'Liste des fichiers';
      case 'search':
        return `Recherche « ${step.path} »`;
      default:
        return `Lecture de ${step.path}`;
    }
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
