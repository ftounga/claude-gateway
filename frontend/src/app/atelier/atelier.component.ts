import { HttpErrorResponse } from '@angular/common/http';
import { Component, NgZone, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
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
import { toDiffViews } from './terminal/terminal-diff';
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from '../chat/confirm-dialog/confirm-dialog.component';
import { CopyBlockComponent } from '../chat/copy-block/copy-block.component';
import {
  TextPromptDialogComponent,
  TextPromptDialogData,
} from './files/text-prompt-dialog.component';
import {
  LibraryPickerDialogComponent,
  PickedLibraryDocument,
} from '../chat/library-picker/library-picker-dialog.component';
import { ApiKeyService } from '../core/services/api-key.service';
import { AtelierService } from '../core/services/atelier.service';
import { ProviderMode } from '../core/models/api-key.models';
import { GitPushDialogComponent, PickedGitPush } from './git/git-push-dialog.component';
import { GitRepoDialogComponent, PickedGitRepository } from './git/git-repo-dialog.component';
import {
  AtelierAction,
  AtelierAgentStreamAction,
  AtelierAgentStreamActionResult,
  AtelierConfirmRequest,
  AtelierConfirmResolved,
  AtelierMessage,
  AtelierTerminalBlock,
  AtelierRole,
  AtelierStreamAction,
  GitPullRequestResult,
  GitPushResult,
  WorkspaceDetail,
  WorkspaceSummary,
} from '../core/models/atelier.models';

// Les types et constantes du fil vivent dans `atelier.types` (F-30 SF-30-07) : la vue terminal les
// consomme aussi, et les garder ici créerait une dépendance circulaire. Réexportés pour compatibilité.
import {
  AtelierAgentMode,
  AtelierExecStreamingItem,
  AtelierPendingConfirmation,
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
  private readonly route = inject(ActivatedRoute);
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

  /**
   * Chemin du fichier d'instructions du projet (F-34 / SF-34-02), ou `null` s'il n'en porte pas.
   * Le chemin vient du backend : l'écran ne devine jamais quel fichier fait foi.
   */
  readonly instructionsPath = computed(() => this.activeDetail()?.instructionsPath ?? null);

  /** Publication sur branche en cours (F-31 / SF-31-04) : le bouton reste inerte pendant le tour. */
  readonly publishing = signal(false);

  /**
   * Demande d'interruption en vol (F-32 / SF-32-02) : le bouton reste inerte le temps que la demande
   * parte. L'arrêt lui-même vient plus tard, à une frontière sûre côté fournisseur.
   */
  readonly interrupting = signal(false);

  /**
   * Demande d'autorisation en attente (F-33 / SF-33-03), ou `null`. Une seule à la fois : l'API peut
   * en poser plusieurs, mais les arbitrer simultanément à l'écran serait confus — la suivante prend
   * la place une fois la précédente tranchée.
   */
  readonly pendingConfirmation = signal<AtelierPendingConfirmation | null>(null);

  /** Bascule de l'option « demander avant d'exécuter » en vol : le bouton reste inerte. */
  readonly togglingConfirmation = signal(false);

  /** Vrai si le projet ouvert demande l'autorisation avant chaque commande (F-33 / SF-33-01). */
  readonly askBeforeBash = computed(() => this.activeDetail()?.askBeforeBash === true);

  /**
   * Dernière publication du projet ouvert. Conservée à l'écran : le lien d'ouverture de pull request
   * est l'aboutissement du parcours, il ne doit pas défiler hors de vue avec le reste du terminal.
   */
  readonly pushResult = signal<GitPushResult | null>(null);

  /**
   * Pull request ouverte pour la dernière publication (F-31 / SF-31-05), ou `null` tant qu'aucune
   * n'a été demandée. Épinglée au même endroit que la publication : c'est l'aboutissement du
   * parcours, et l'URL est la seule chose que l'utilisateur ait à emporter.
   */
  readonly pullRequest = signal<GitPullRequestResult | null>(null);

  /** Ouverture de pull request en vol : le bouton reste inerte tant que le tour n'est pas fini. */
  readonly openingPullRequest = signal(false);

  ngOnInit(): void {
    // Projet et mode demandés par l'URL (F-30 SF-30-10) : `/atelier/{id}?mode=terminal`. Les deux
    // sont optionnels — `/atelier` seul garde exactement son comportement d'origine.
    this.requestedWorkspaceId = this.route.snapshot.paramMap.get('id');
    if (this.route.snapshot.queryParamMap.get('mode') === 'terminal') {
      this.agentMode.set('exec');
    }
    this.loadWorkspaces();
  }

  /** Projet réclamé par l'URL, honoré une fois la liste chargée (ou ignoré s'il n'existe pas). */
  private requestedWorkspaceId: string | null = null;

  /**
   * Applique le projet demandé par l'URL, si l'utilisateur le possède. Un identifiant inconnu ou
   * appartenant à quelqu'un d'autre n'apparaît pas dans la liste : on retombe alors sur l'écran
   * habituel avec un message, plutôt que sur un écran vide.
   */
  private applyRequestedWorkspace(list: WorkspaceSummary[]): void {
    const id = this.requestedWorkspaceId;
    this.requestedWorkspaceId = null;
    if (!id) {
      return;
    }
    const found = list.find((w) => w.id === id);
    if (!found) {
      this.agentMode.set('edit');
      this.notifyError('Projet introuvable.');
      return;
    }
    this.selectWorkspace(found);
  }

  private loadWorkspaces(): void {
    this.atelier.listWorkspaces().subscribe({
      next: (list) => {
        this.workspaces.set(list);
        this.applyRequestedWorkspace(list);
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
    // Nom proposé à la création (F-28 SF-28-16) : pré-rempli avec le nom de l'archive, modifiable.
    // Annuler la saisie annule l'import — l'utilisateur a choisi un fichier, pas encore un projet.
    const suggested = file.name.replace(/\.zip$/i, '');
    this.promptForName('Nommer le projet', suggested, (name) => this.uploadZip(file, name));
  }

  /** Demande un nom à l'utilisateur, pré-rempli, puis exécute l'action. Annuler ne fait rien. */
  private promptForName(title: string, initialValue: string, then: (name: string) => void): void {
    const data: TextPromptDialogData = {
      title,
      label: 'Nom du projet',
      confirmLabel: 'Valider',
      initialValue,
      hint: 'Une étiquette : deux projets peuvent porter le même nom.',
    };
    this.dialog
      .open(TextPromptDialogComponent, { data, width: '420px' })
      .afterClosed()
      .subscribe((name) => {
        if (name && name.trim().length > 0) {
          then(name.trim());
        }
      });
  }

  /** Renomme le projet actif (F-28 SF-28-16) : étiquette seule, rien d'autre ne bouge. */
  renameActiveWorkspace(): void {
    const id = this.activeWorkspaceId();
    if (!id) {
      return;
    }
    this.promptForName('Renommer le projet', this.activeName(), (name) => {
      this.atelier.renameWorkspace(id, name).subscribe({
        next: (detail) => {
          this.workspaces.update((list) =>
            list.map((w) => (w.id === id ? { ...w, name: detail.name } : w)),
          );
          this.snackBar.open('Projet renommé.', 'Fermer', { duration: 3000 });
        },
        error: (err: unknown) =>
          this.notifyError(
            err instanceof HttpErrorResponse && err.status === 404
              ? 'Projet introuvable.'
              : "Le projet n'a pas pu être renommé. Veuillez réessayer.",
          ),
      });
    });
  }

  private uploadZip(file: File, name: string): void {
    this.creating.set(true);
    this.atelier.createWorkspace(file, name).subscribe({
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

  /**
   * Publie le travail de la session sur une branche dédiée (F-31 / SF-31-04), puis affiche le lien
   * d'ouverture de pull request.
   *
   * Le résultat n'est **pas** déduit de ce que Claude répond : le backend constate l'existence de la
   * branche auprès de GitHub. Un échec revient donc en `200` avec sa cause, et c'est cette cause que
   * l'écran montre.
   */
  openPushDialog(): void {
    const id = this.activeWorkspaceId();
    const detail = this.activeDetail();
    if (!id || !detail || detail.source !== 'GIT' || this.publishing()) {
      return;
    }
    this.dialog
      .open(GitPushDialogComponent, {
        width: '520px',
        autoFocus: false,
        data: {
          gitRepo: detail.gitRepo,
          baseBranch: detail.gitBranch,
          suggestedBranch: this.suggestedBranch(),
        },
      })
      .afterClosed()
      .subscribe((picked: PickedGitPush | undefined) => {
        if (!picked) {
          return;
        }
        this.publishing.set(true);
        // Une nouvelle publication rend caduque la pull request de la précédente : la laisser à
        // l'écran ferait pointer un lien vers un travail qui n'est plus celui qu'on vient de pousser.
        this.pullRequest.set(null);
        this.atelier.pushBranch(id, picked).subscribe({
          next: (result) => {
            this.publishing.set(false);
            this.pushResult.set(result);
            this.snackBar.open(
              result.pushed ? `Branche ${result.branch} publiée.` : "Rien n'a été publié.",
              'Fermer',
              { duration: 4000 },
            );
            this.refreshTree(id);
          },
          error: (err) => {
            this.publishing.set(false);
            this.notifyError(this.pushErrorMessage(err));
          },
        });
      });
  }

  /**
   * Ouvre la pull request de la branche qui vient d'être publiée (F-31 / SF-31-05).
   *
   * Rien n'est deviné côté client : la branche envoyée est celle que le backend a **constatée** au
   * push. Et le résultat n'est pas déduit de ce que Claude répond — le backend constate l'existence
   * de la pull request auprès de GitHub. Un échec revient donc en `200` avec sa cause.
   */
  openPullRequest(): void {
    const id = this.activeWorkspaceId();
    const push = this.pushResult();
    if (!id || !push || !push.pushed || this.openingPullRequest()) {
      return;
    }
    this.openingPullRequest.set(true);
    this.atelier.createPullRequest(id, { branch: push.branch }).subscribe({
      next: (result) => {
        this.openingPullRequest.set(false);
        this.pullRequest.set(result);
        this.snackBar.open(
          result.created
            ? `Pull request #${result.number} ouverte.`
            : "Aucune pull request n'a été ouverte.",
          'Fermer',
          { duration: 4000 },
        );
      },
      error: (err) => {
        this.openingPullRequest.set(false);
        this.notifyError(this.pushErrorMessage(err));
      },
    });
  }

  /**
   * Message d'erreur de publication. Comme à l'ouverture d'un dépôt, chaque cause appelle un geste
   * différent : relancer une commande, changer de branche, réenregistrer un jeton, réessayer.
   *
   * Partagé avec l'ouverture de pull request (SF-31-05) : les causes et les gestes correctifs sont
   * exactement les mêmes — dupliquer la table ferait diverger les deux messages sans raison.
   */
  private pushErrorMessage(err: unknown): string {
    const code =
      err instanceof HttpErrorResponse ? (err.error as { error?: string } | null)?.error : undefined;
    switch (code) {
      case 'no_active_session':
        return "Aucun travail en cours : demandez d'abord une modification à Claude.";
      case 'invalid_git_branch':
        return 'Nom de branche invalide, ou branche par défaut du dépôt.';
      case 'git_workspace_required':
        return "Ce projet n'est pas adossé à un dépôt Git.";
      case 'git_token_missing':
        return 'Aucun jeton GitHub enregistré : ajoutez-en un dans vos réglages.';
      case 'invalid_git_token':
        return 'GitHub a refusé votre jeton. Remplacez-le dans vos réglages.';
      case 'github_unavailable':
        return 'GitHub est momentanément indisponible : le résultat de la publication est inconnu.';
      default:
        return httpErrorMessage(err, 'La publication a échoué.');
    }
  }

  /** Branche proposée par défaut : préfixe reconnaissable + horodatage, comme côté backend. */
  private suggestedBranch(): string {
    const now = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    return (
      `claude/atelier-${now.getUTCFullYear()}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}` +
      `-${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}`
    );
  }

  /**
   * Quitte la vue terminal. Sur un projet Git, le mode Assistant n'existe pas (il lirait un stockage
   * vide) : quitter, c'est donc refermer le projet plutôt que basculer vers un mode indisponible.
   */
  leaveTerminal(): void {
    if (this.activeIsGit()) {
      this.closeWorkspace();
      return;
    }
    this.setAgentMode('edit');
  }

  /** Referme le projet ouvert et revient à la liste. */
  private closeWorkspace(): void {
    this.activeWorkspaceId.set(null);
    this.activeDetail.set(null);
    this.tree.set([]);
    this.messages.set([]);
    this.pushResult.set(null);
    this.pullRequest.set(null);
    this.resetFilePanel();
    this.agentMode.set('edit');
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
    this.pushResult.set(null);
    this.pullRequest.set(null);
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
    this.pushResult.set(null);
    this.pullRequest.set(null);
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
   * Demande l'interruption du run en cours (F-32 / SF-32-02). Sans confirmation : l'action est
   * réversible (on renvoie un message) et l'urgence est le motif même du bouton.
   *
   * <p>L'arrêt est <b>asynchrone</b> — la session s'arrête à une frontière sûre côté fournisseur —
   * donc rien n'est retiré de l'écran ici : c'est le `done` du flux en cours qui clôt le tour, marqué
   * comme interrompu.</p>
   */
  interruptRun(): void {
    const id = this.activeWorkspaceId();
    if (!id || !this.submitting() || this.interrupting()) {
      return;
    }
    this.interrupting.set(true);
    this.atelier.interruptAgentSession(id).subscribe({
      next: () =>
        this.snackBar.open('Interruption demandée : arrêt en cours…', 'Fermer', { duration: 4000 }),
      error: (err: unknown) => {
        this.interrupting.set(false);
        this.notifyError(this.interruptErrorMessage(err));
      },
    });
  }

  /** Traduit l'échec d'une demande d'interruption en message lisible (F-32 / SF-32-02). */
  private interruptErrorMessage(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 409) {
        return "Aucune exécution en cours à interrompre.";
      }
      if (err.status === 404) {
        return 'Projet introuvable.';
      }
    }
    return "L'interruption n'a pas pu être transmise. Veuillez réessayer.";
  }

  /**
   * Affiche la demande d'autorisation posée par l'agent (F-33 / SF-33-03). L'invite vit **dans le
   * flux**, là où l'utilisateur regarde déjà défiler la sortie : une modale masquerait précisément
   * les commandes précédentes, qui sont ce qui permet de juger.
   */
  private showConfirmation(request: AtelierConfirmRequest): void {
    this.pendingConfirmation.set({
      toolUseId: request.toolUseId,
      tool: request.tool,
      detail: request.detail,
      answering: false,
      denying: false,
      reason: '',
    });
  }

  /**
   * Retire l'invite quand la demande a été tranchée — ici, dans un autre onglet, ou par expiration
   * du délai. Le refus automatique est **dit** : sans cela, l'utilisateur croirait sa décision encore
   * attendue alors que la commande a déjà été refusée.
   */
  private clearConfirmation(resolved: AtelierConfirmResolved): void {
    const pending = this.pendingConfirmation();
    if (pending && pending.toolUseId !== resolved.toolUseId) {
      return;
    }
    this.pendingConfirmation.set(null);
    if (resolved.decision === 'timeout') {
      this.snackBar.open(
        'Commande refusée : aucune réponse dans le délai imparti.', 'Fermer', { duration: 6000 });
    }
  }

  /** Ouvre le champ de motif : refuser tient en un clic, motiver est un second geste, facultatif. */
  startDenying(): void {
    this.pendingConfirmation.update((current) =>
      current ? { ...current, denying: true } : current);
  }

  /** Saisie du motif de refus (le composant terminal reste une vue de présentation). */
  setConfirmationReason(reason: string): void {
    this.pendingConfirmation.update((current) =>
      current ? { ...current, reason } : current);
  }

  /**
   * Répond à la demande en attente (F-33 / SF-33-03) : autorise, ou refuse avec le motif saisi — que
   * l'agent recevra, pour qu'il propose autre chose plutôt que de rester bloqué.
   *
   * <p>L'invite n'est retirée qu'à la **résolution** relayée par le flux : c'est elle qui prouve que
   * la décision est bien arrivée jusqu'à la session.</p>
   */
  answerConfirmation(allow: boolean): void {
    const id = this.activeWorkspaceId();
    const pending = this.pendingConfirmation();
    if (!id || !pending || pending.answering) {
      return;
    }
    this.pendingConfirmation.set({ ...pending, answering: true });
    const reason = pending.reason.trim();
    this.atelier
      .confirmToolUse(id, {
        toolUseId: pending.toolUseId,
        decision: allow ? 'allow' : 'deny',
        reason: !allow && reason.length > 0 ? reason : undefined,
      })
      .subscribe({
        error: (err: unknown) => {
          // La demande n'est plus à trancher (déjà expirée, session close) : retirer l'invite plutôt
          // que de laisser l'utilisateur cliquer dans le vide.
          this.pendingConfirmation.set(null);
          this.notifyError(this.confirmErrorMessage(err));
        },
      });
  }

  /** Traduit l'échec d'une réponse d'autorisation en message lisible (F-33 / SF-33-03). */
  private confirmErrorMessage(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 409) {
        return "L'exécution n'attend plus de réponse.";
      }
      if (err.status === 404) {
        return 'Projet introuvable.';
      }
      if (err.status === 502) {
        return "Votre réponse n'a pas pu être transmise : la demande n'était plus à trancher.";
      }
    }
    return "Votre réponse n'a pas pu être transmise. Veuillez réessayer.";
  }

  /**
   * Bascule l'option « demander avant d'exécuter » du projet (F-33 / SF-33-01).
   *
   * <p>La politique d'outils est fixée à l'ouverture de la sandbox : quand une session tourne déjà,
   * le backend le dit (`appliesToCurrentSession: false`) et on le répète à l'utilisateur — annoncer
   * une protection qui n'est pas en vigueur serait pire que ne rien annoncer.</p>
   */
  toggleAskBeforeBash(): void {
    const id = this.activeWorkspaceId();
    if (!id || this.togglingConfirmation()) {
      return;
    }
    const enabled = !this.askBeforeBash();
    this.togglingConfirmation.set(true);
    this.atelier.setAskBeforeBash(id, enabled).subscribe({
      next: (state) => {
        this.togglingConfirmation.set(false);
        this.activeDetail.update((detail) =>
          detail ? { ...detail, askBeforeBash: state.enabled } : detail);
        this.snackBar.open(this.confirmationToggleMessage(state.enabled,
          state.appliesToCurrentSession), 'Fermer', { duration: 6000 });
      },
      error: (err: unknown) => {
        this.togglingConfirmation.set(false);
        this.notifyError(
          err instanceof HttpErrorResponse && err.status === 404
            ? 'Projet introuvable.'
            : "Le réglage n'a pas pu être enregistré. Veuillez réessayer.");
      },
    });
  }

  /** Message de bascule : dit franchement quand le réglage ne vaut que pour la prochaine sandbox. */
  private confirmationToggleMessage(enabled: boolean, appliesNow: boolean): string {
    const state = enabled
      ? 'Validation activée : Claude demandera avant chaque commande.'
      : 'Validation désactivée : Claude exécutera sans demander.';
    return appliesNow
      ? state
      : `${state} Prend effet à la prochaine sandbox — réinitialisez pour l'appliquer maintenant.`;
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
      onConfirmRequest: (request) => this.zone.run(() => this.showConfirmation(request)),
      onConfirmResolved: (resolved) => this.zone.run(() => this.clearConfirmation(resolved)),
      onDone: (done) =>
        this.zone.run(() => {
          this.submitting.set(false);
          this.interrupting.set(false);
          // Plus rien n'attend de décision : une invite restée à l'écran serait un piège.
          this.pendingConfirmation.set(null);
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
              // Le tour interrompu reste affiché : il a eu lieu et il est facturé (F-32).
              interrupted: done.interrupted === true,
              // Plafond de dépense de ce run atteint (F-36 SF-36-04) : le tour est conservé, et
              // l'écran le dit — un arrêt au milieu sans explication serait le pire des deux mondes.
              budgetReached: done.budgetReached === true,
              // Ce qui a changé dans les fichiers (F-37 SF-37-02) : replié, sous le commentaire.
              diffs: toDiffViews(done.diffs),
            },
          ]);
          // La session a pu exécuter du code et modifier des fichiers : rafraîchir l'arborescence.
          this.refreshTree(id);
        }),
      onError: (code) =>
        this.zone.run(() => {
          this.submitting.set(false);
          this.interrupting.set(false);
          this.pendingConfirmation.set(null);
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

  /**
   * Ouvre l'explorateur de fichiers sur le fichier d'instructions du projet (F-34 / SF-34-02).
   *
   * <p>Réutilise l'explorateur plutôt qu'un éditeur dédié : c'est là que le fichier s'édite déjà, et
   * un projet Git y est en lecture seule sans traitement particulier.</p>
   */
  openInstructions(): void {
    const id = this.activeWorkspaceId();
    const path = this.instructionsPath();
    if (!id || !path) {
      return;
    }
    this.router.navigate(['/atelier', id, 'fichiers'], { queryParams: { path } });
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
    threadId: action.threadId ?? null,
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
        threadId: result.threadId ?? null,
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
    // Mieux vaut un fil tardif qu'aucun : une commande sans fil adopte celui de sa sortie.
    threadId: target.threadId ?? result.threadId ?? null,
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
  if (!stored) {
    return item;
  }
  // Le drapeau d'interruption vit hors des blocs : un tour interrompu avant d'avoir lancé la moindre
  // commande n'a pas de transcription, et doit pourtant rester marqué au rechargement (F-32).
  item.interrupted = stored.interrupted === true;
  // Même raison pour le plafond de dépense (F-36 SF-36-04) : sans cela, la mention disparaîtrait au
  // rechargement et l'utilisateur relirait un tour d'apparence normale, arrêté sans motif.
  item.budgetReached = stored.budgetReached === true;
  // Les modifications vivent hors des blocs (F-37 SF-37-02) : un tour peut n'avoir lancé aucune
  // commande visible et avoir pourtant réécrit des fichiers.
  const diffs = toDiffViews(stored.diffs);
  if (diffs.length > 0) {
    item.diffs = diffs;
  }
  if (!Array.isArray(stored.blocks) || stored.blocks.length === 0) {
    return item;
  }
  item.terminal = stored.blocks.map((block): AtelierTerminalBlock => ({
    tool: block.tool,
    command: block.command ?? undefined,
    toolUseId: block.toolUseId ?? null,
    threadId: block.threadId ?? null,
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
