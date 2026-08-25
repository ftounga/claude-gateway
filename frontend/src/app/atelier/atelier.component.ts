import { HttpErrorResponse } from '@angular/common/http';
import { Component, NgZone, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
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
import { AtelierTerminalComponent } from './terminal/atelier-terminal.component';
import {
  blockLabel as blockLabelOf,
  formatElapsed,
  hiddenLineCount,
  visibleOutput,
} from './terminal/terminal-block';
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from '../chat/confirm-dialog/confirm-dialog.component';
import { CopyBlockComponent } from '../chat/copy-block/copy-block.component';
import {
  LibraryPickerDialogComponent,
  PickedLibraryDocument,
} from '../chat/library-picker/library-picker-dialog.component';
import { ApiKeyService } from '../core/services/api-key.service';
import { AtelierService } from '../core/services/atelier.service';
import { ProviderMode } from '../core/models/api-key.models';
import { GitRepoDialogComponent, PickedGitRepository } from './git/git-repo-dialog.component';
import {
  AtelierAction,
  AtelierAgentStreamAction,
  AtelierAgentStreamActionResult,
  AtelierMessage,
  AtelierTerminalBlock,
  AtelierRole,
  AtelierStreamAction,
  WorkspaceDetail,
  WorkspaceSummary,
} from '../core/models/atelier.models';

// Les types et constantes du fil vivent dans `atelier.types` (F-30 SF-30-07) : la vue terminal les
// consomme aussi, et les garder ici créerait une dépendance circulaire. Réexportés pour compatibilité.
import {
  AtelierAgentMode,
  AtelierExecStreamingItem,
  AtelierStreamingItem,
  AtelierThreadItem,
  AtelierTurnCost,
  WORKSPACE_TEXT_ACCEPT,
  WORKSPACE_TEXT_EXTENSIONS,
} from './atelier.types';
export type {
  AtelierAgentMode,
  AtelierThreadItem,
  AtelierStreamingItem,
  AtelierExecStreamingItem,
  AtelierTurnCost,
} from './atelier.types';
export { WORKSPACE_TEXT_EXTENSIONS, WORKSPACE_TEXT_ACCEPT } from './atelier.types';

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
    AtelierTerminalComponent,
  ],
  templateUrl: './atelier.component.html',
  styleUrl: './atelier.component.scss',
})
export class AtelierComponent implements OnInit, OnDestroy {
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
   * Mode de l'agent (SF-28-11). « Assistant » (défaut, Phase 1) : Claude lit/édite les fichiers.
   * « Terminal » (Phase 2) : Claude exécute réellement (bash, tests, build) dans un sandbox hébergé.
   */
  readonly agentMode = signal<AtelierAgentMode>('edit');

  /** Tour assistant « en cours » du mode « Terminal » (état + transcription + texte partiel). */
  readonly execStreaming = signal<AtelierExecStreamingItem | null>(null);

  /** Durée écoulée du run d'exécution en secondes (F-30 SF-30-02) : seul repère de progression. */
  readonly execElapsedSeconds = signal(0);

  /** Chronomètre du run ; arrêté à `onDone`/`onError` pour ne pas laisser tourner un intervalle. */
  private execTimer: ReturnType<typeof setInterval> | null = null;

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

  /**
   * Détail du projet ouvert (F-31 / SF-31-02) : porte la source et, pour un dépôt, `owner/repo` et
   * la branche montée. Nourri par le même appel que l'arborescence — aucun aller-retour de plus.
   */
  readonly activeDetail = signal<WorkspaceDetail | null>(null);

  /** Vrai si le projet ouvert est adossé à un dépôt Git. */
  readonly activeIsGit = computed(() => this.activeDetail()?.source === 'GIT');

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
        this.adoptWorkspace(workspace);
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
   * Ouvre un projet sur un **dépôt GitHub** (F-31 / SF-31-02) : plus d'export/réimport manuel, le
   * dépôt est cloné dans l'espace d'exécution de Claude. Le jeton d'accès n'est pas saisi ici — il
   * est enregistré une fois pour toutes dans les réglages, chiffré côté backend.
   */
  openGitRepoDialog(): void {
    this.dialog
      .open(GitRepoDialogComponent, { width: '520px', autoFocus: false })
      .afterClosed()
      .subscribe((picked: PickedGitRepository | undefined) => {
        if (!picked) {
          return;
        }
        this.creating.set(true);
        this.atelier.createGitWorkspace(picked).subscribe({
          next: (workspace) => {
            this.creating.set(false);
            this.adoptWorkspace(workspace);
            this.snackBar.open('Dépôt ouvert.', 'Fermer', { duration: 3000 });
          },
          error: (err) => {
            this.creating.set(false);
            this.notifyError(this.gitErrorMessage(err));
          },
        });
      });
  }

  /**
   * Message d'erreur d'ouverture de dépôt. Chaque cause appelle une action différente — enregistrer
   * un jeton, corriger l'URL, réessayer plus tard — et les confondre laisserait l'utilisateur sans
   * geste à faire.
   */
  private gitErrorMessage(err: unknown): string {
    const code =
      err instanceof HttpErrorResponse ? (err.error as { error?: string } | null)?.error : undefined;
    switch (code) {
      case 'git_token_missing':
        return 'Aucun jeton GitHub enregistré : ajoutez-en un dans vos réglages.';
      case 'invalid_git_token':
        return 'GitHub a refusé votre jeton. Remplacez-le dans vos réglages.';
      case 'invalid_git_repository':
        return 'Dépôt introuvable, ou hors de portée de votre jeton GitHub.';
      case 'invalid_git_branch':
        return 'Nom de branche invalide.';
      case 'github_unavailable':
        return 'GitHub est momentanément indisponible. Réessayez dans un instant.';
      default:
        return httpErrorMessage(err, "L'ouverture du dépôt a échoué.");
    }
  }

  /** Place un workspace fraîchement créé en tête de liste, l'ouvre, et réinitialise l'écran. */
  private adoptWorkspace(workspace: WorkspaceDetail): void {
    this.workspaces.update((list) => [
      {
        id: workspace.id,
        name: workspace.name,
        createdAt: workspace.createdAt,
        source: workspace.source,
        gitRepo: workspace.gitRepo,
      },
      ...list.filter((w) => w.id !== workspace.id),
    ]);
    this.activeWorkspaceId.set(workspace.id);
    this.tree.set(workspace.files);
    this.activeDetail.set(workspace);
    this.messages.set([]);
    this.resetFilePanel();
    this.alignModeWithSource(workspace);
  }

  /**
   * Un projet Git n'a de sens qu'en mode Terminal (F-31 / SF-31-03) : le mode Assistant travaille sur
   * le stockage objet, vide ici. On aligne le mode sur la source plutôt que de laisser l'utilisateur
   * découvrir le refus au premier message.
   */
  private alignModeWithSource(detail: WorkspaceDetail): void {
    if (detail.source === 'GIT') {
      this.agentMode.set('exec');
    }
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
    this.activeDetail.set(null);
    this.resetFilePanel();
    this.loadHistory(workspace.id);
    this.refreshTree(workspace.id);
  }

  private loadHistory(id: string): void {
    this.atelier.getHistory(id).subscribe({
      next: (history) =>
        // Les tours Terminal restituent leur transcription (F-30 SF-30-09) : sans elle, recharger la
        // page vidait l'écran alors que la sandbox, elle, gardait son état.
        this.messages.set(history.map((m) => toThreadItem(m))),
      error: () => this.notifyError("Impossible de charger l'historique de conversation."),
    });
  }

  private refreshTree(id: string): void {
    this.atelier.getWorkspace(id).subscribe({
      next: (detail) => {
        this.tree.set(detail.files);
        this.activeDetail.set(detail);
        this.alignModeWithSource(detail);
      },
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

    // Mode « Terminal » (Phase 2, SF-28-11) : délègue au flux d'agent (sandbox hébergé). Le mode
    // « Assistant » (défaut, Phase 1) reste strictement inchangé ci-dessous.
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

  /**
   * Réinitialise la sandbox du workspace (F-30 SF-30-06). L'action ne détruit aucun fichier du
   * projet, mais elle jette un environnement qui a pu coûter plusieurs minutes d'installation : la
   * confirmation dit explicitement ce qui est perdu et ce qui est conservé.
   */
  resetSandbox(): void {
    const id = this.activeWorkspaceId();
    if (!id || this.submitting()) {
      return;
    }
    const data: ConfirmDialogData = {
      title: 'Réinitialiser la sandbox',
      message:
        "L'environnement d'exécution (dépendances installées, état des processus) sera perdu. "
        + 'Les fichiers de votre projet sont conservés. Le prochain message repartira d\'un '
        + 'environnement neuf.',
      confirmLabel: 'Réinitialiser',
    };
    this.dialog
      .open(ConfirmDialogComponent, { data, width: '420px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          this.atelier.resetAgentSession(id).subscribe({
            next: () => this.snackBar.open('Sandbox réinitialisée.', 'Fermer', { duration: 4000 }),
            error: (err: unknown) =>
              this.notifyError(
                err instanceof HttpErrorResponse && err.status === 404
                  ? 'Projet introuvable.'
                  : "La sandbox n'a pas pu être réinitialisée. Veuillez réessayer.",
              ),
          });
        }
      });
  }

  /**
   * Bascule le mode de l'agent (« Assistant » ⇄ « Terminal »), sauf pendant un envoi en cours.
   *
   * <p>Sur un projet Git (F-31 / SF-31-03), le mode Assistant est refusé côté backend : il lit le
   * stockage objet, vide sur ce type de projet. On reste donc en Terminal, où le dépôt est
   * réellement cloné — plutôt que d'envoyer l'utilisateur vers une erreur.</p>
   */
  setAgentMode(mode: AtelierAgentMode): void {
    if (this.submitting()) {
      return;
    }
    if (mode === 'edit' && this.activeIsGit()) {
      this.agentMode.set('exec');
      this.notifyError(
        'Ce projet est adossé à un dépôt Git : le mode Terminal est le seul où le dépôt est disponible.',
      );
      return;
    }
    this.agentMode.set(mode);
  }

  /**
   * Envoie le message courant en mode **Terminal** (SF-28-11) : relaie l'état de session, les étapes
   * d'outils (bash, tests…) et le commentaire au fil de l'eau, puis ajoute la réponse finale et la
   * liste des fichiers modifiés, et rafraîchit l'arborescence. Le flux tourne hors zone Angular
   * (fetch) : chaque mise à jour de signal passe par {@link NgZone}. En cas d'erreur, le tour
   * utilisateur optimiste est retiré (rien n'a été persisté) et un message lisible est affiché.
   */
  private sendExec(id: string, content: string, userItem: AtelierThreadItem): void {
    this.execStreaming.set({ status: '', blocks: [], text: '' });
    this.startExecTimer();

    void this.atelier.streamAgent(id, content, {
      onStatus: (state) =>
        this.zone.run(() => {
          this.execStreaming.update((current) => (current ? { ...current, status: state } : current));
        }),
      onAction: (action) =>
        this.zone.run(() => {
          this.execStreaming.update((current) =>
            current ? { ...current, blocks: [...current.blocks, openBlock(action)] } : current,
          );
        }),
      onActionResult: (result) =>
        this.zone.run(() => {
          this.execStreaming.update((current) =>
            current ? { ...current, blocks: attachOutput(current.blocks, result) } : current,
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
          // La transcription est reprise dans le tour final : sans cela, tout ce qui a défilé
          // pendant le run disparaîtrait de l'écran (F-30 SF-30-02).
          const transcript = this.execStreaming()?.blocks ?? [];
          const elapsed = this.execElapsedSeconds();
          this.stopExecTimer();
          this.execStreaming.set(null);
          // Consommation à zéro = relevé manqué côté backend : on n'affiche alors aucun chiffre.
          const tokens = (done.inputTokens ?? 0) + (done.outputTokens ?? 0);
          this.messages.update((current) => [
            ...current,
            {
              id: `local-assistant-${Date.now()}`,
              role: 'ASSISTANT',
              content: done.reply,
              actions: [],
              changedFiles: done.changedFiles ?? [],
              terminal: transcript,
              cost: tokens > 0 ? { elapsedSeconds: elapsed, tokens } : undefined,
            },
          ]);
          // La session a pu exécuter du code et modifier des fichiers : rafraîchir l'arborescence.
          this.refreshTree(id);
        }),
      onError: (code) =>
        this.zone.run(() => {
          this.submitting.set(false);
          this.stopExecTimer();
          this.execStreaming.set(null);
          // Retire le message utilisateur optimiste : rien n'a été persisté côté serveur.
          this.messages.update((current) => current.filter((m) => m.id !== userItem.id));
          this.notifyError(this.mapAgentError(code));
        }),
    });
  }

  /** Traduit un code d'erreur du flux Terminal en message utilisateur lisible (SF-28-11). */
  private mapAgentError(code: string): string {
    switch (code) {
      case 'forbidden':
        return "Le mode Terminal est réservé à l'offre Gold.";
      case 'agent_disabled':
        return 'Le mode Terminal est momentanément indisponible.';
      case 'workspace_not_found':
        return 'Projet introuvable.';
      case 'credit_exhausted':
        // Limite de la plateforme, pas du compte utilisateur : réessayer n'y changerait rien.
        return "Le service d'exécution est indisponible : le crédit du fournisseur est épuisé. "
          + "Contactez l'administrateur.";
      case 'session_timeout':
        return 'La session a dépassé le temps imparti. Réessayez sur une tâche plus courte.';
      default:
        return "L'exécution a échoué. Veuillez réessayer.";
    }
  }

  /** Libellé de l'en-tête d'un bloc terminal : la commande si connue, sinon le nom de l'outil. */
  blockLabel(block: AtelierTerminalBlock): string {
    return blockLabelOf(block);
  }

  /**
   * Sortie effectivement affichée : repliée au-delà de {@link TERMINAL_COLLAPSE_LINES} lignes tant
   * que l'utilisateur ne l'a pas dépliée. La sortie est déjà bornée côté backend (SF-30-01) : ici on
   * ne perd rien, on masque.
   */
  visibleOutput(block: AtelierTerminalBlock): string {
    if (block.expanded) {
      return block.output;
    }
    return visibleOutput(block);
  }

  /** Nombre de lignes masquées par le repli ; `0` si la sortie tient sous le seuil. */
  hiddenLineCount(block: AtelierTerminalBlock): number {
    return hiddenLineCount(block);
  }

  /** Déplie/replie la sortie d'un bloc terminal (tour en cours ou tour déjà dans le fil). */
  toggleBlock(block: AtelierTerminalBlock): void {
    block.expanded = !block.expanded;
    this.execStreaming.update((current) => (current ? { ...current } : current));
    this.messages.update((current) => [...current]);
  }

  /** Durée écoulée du run, format `m:ss`. */
  execElapsedLabel(): string {
    return formatElapsed(this.execElapsedSeconds());
  }

  /** Coût d'un tour terminé : « m:ss · N tokens » (F-30 SF-30-05). */
  costLabel(cost: AtelierTurnCost): string {
    return `${formatElapsed(cost.elapsedSeconds)} · ${cost.tokens.toLocaleString('fr-FR')} tokens`;
  }

  /** Démarre le chronomètre du run (hors zone : il ne pilote qu'un signal). */
  private startExecTimer(): void {
    this.stopExecTimer();
    this.execElapsedSeconds.set(0);
    this.execTimer = setInterval(() => {
      this.zone.run(() => this.execElapsedSeconds.update((value) => value + 1));
    }, 1000);
  }

  /** Arrête le chronomètre ; idempotent (appelé à la fin du run et à la destruction du composant). */
  private stopExecTimer(): void {
    if (this.execTimer !== null) {
      clearInterval(this.execTimer);
      this.execTimer = null;
    }
  }

  /** Quitter l'écran pendant un run ne doit pas laisser le chronomètre tourner. */
  ngOnDestroy(): void {
    this.stopExecTimer();
  }

  /** Ouvre/ferme le panneau « Fichiers ». */
  toggleFilesPanel(): void {
    this.filesPanelOpen.update((open) => !open);
  }

  /** Ouvre la page « Explorateur de fichiers » du workspace actif (SF-28-15). */
  openFileExplorer(): void {
    const id = this.activeWorkspaceId();
    if (!id) {
      return;
    }
    this.router.navigate(['/atelier', id, 'fichiers']);
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


/** Ouvre un bloc terminal pour une commande relayée (F-30 SF-30-02). */
function openBlock(action: AtelierAgentStreamAction): AtelierTerminalBlock {
  return {
    tool: action.tool,
    command: action.detail,
    toolUseId: action.toolUseId ?? null,
    output: '',
    hasOutput: false,
    error: false,
    expanded: false,
  };
}

/**
 * Rattache une sortie à sa commande (F-30 SF-30-02). Priorité au `toolUseId` ; à défaut, la dernière
 * commande encore sans sortie. Si aucune ne convient, un bloc **orphelin** est créé : afficher une
 * sortie sans en-tête vaut mieux que la perdre.
 */
function attachOutput(
  blocks: AtelierTerminalBlock[],
  result: AtelierAgentStreamActionResult,
): AtelierTerminalBlock[] {
  let index = result.toolUseId
    ? blocks.findIndex((block) => block.toolUseId === result.toolUseId)
    : -1;
  if (index < 0) {
    for (let i = blocks.length - 1; i >= 0; i -= 1) {
      if (!blocks[i].hasOutput) {
        index = i;
        break;
      }
    }
  }
  if (index < 0) {
    return [
      ...blocks,
      {
        tool: result.tool,
        toolUseId: result.toolUseId,
        output: result.output,
        hasOutput: true,
        error: result.error,
        expanded: false,
      },
    ];
  }
  const target = blocks[index];
  const merged: AtelierTerminalBlock = {
    ...target,
    toolUseId: target.toolUseId ?? result.toolUseId,
    // Plusieurs sorties pour une même commande : concaténées dans l'ordre d'arrivée.
    output: target.hasOutput && target.output ? `${target.output}\n${result.output}` : result.output,
    hasOutput: true,
    error: target.error || result.error,
  };
  return blocks.map((block, i) => (i === index ? merged : block));
}

/**
 * Convertit un message d'historique en tour du fil (F-30 SF-30-09). Une transcription absente ou
 * illisible produit simplement un tour sans terminal : un défaut d'affichage ne doit jamais empêcher
 * de relire sa conversation.
 */
export function toThreadItem(message: AtelierMessage): AtelierThreadItem {
  const item: AtelierThreadItem = {
    id: message.id,
    role: message.role,
    content: message.content,
    actions: [],
  };
  const stored = message.terminal;
  if (!stored || !Array.isArray(stored.blocks) || stored.blocks.length === 0) {
    return item;
  }
  item.terminal = stored.blocks.map((block): AtelierTerminalBlock => ({
    tool: block.tool,
    command: block.command ?? undefined,
    toolUseId: block.toolUseId ?? null,
    output: block.output ?? '',
    hasOutput: block.hasOutput === true,
    error: block.error === true,
    expanded: false,
  }));
  const tokens = (stored.inputTokens ?? 0) + (stored.outputTokens ?? 0);
  if (tokens > 0) {
    item.cost = { elapsedSeconds: stored.activeSeconds ?? 0, tokens };
  }
  return item;
}
