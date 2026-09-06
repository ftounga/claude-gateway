import { HttpErrorResponse } from '@angular/common/http';
import { Component, NgZone, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';

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
  AtelierEngine,
  AtelierEngineStatus,
  AtelierRunnerRecommendation,
  AtelierMessage,
  AtelierTerminalBlock,
  AtelierRole,
  AtelierStreamAction,
  GitPullRequestResult,
  GitPushResult,
  RunnerStatus,
  WorkspaceDetail,
  WorkspaceExecutionTarget,
  WorkspaceSummary,
} from '../core/models/atelier.models';
import {
  RunnerPairingDialogComponent,
  RunnerPairingDialogData,
} from './runner/runner-pairing-dialog.component';
import {
  RunnerAuditDialogComponent,
  RunnerAuditDialogData,
} from './runner/runner-audit-dialog.component';
import { chatStepsToBlocks } from './terminal/chat-steps';

// Les types et constantes du fil vivent dans `atelier.types` (F-30 SF-30-07) : la vue terminal les
// consomme aussi, et les garder ici créerait une dépendance circulaire. Réexportés pour compatibilité.
import {
  AtelierExecStreamingItem,
  AtelierPendingConfirmation,
  AtelierStreamingItem,
  AtelierThreadItem,
  AtelierTurnCost,
  WORKSPACE_TEXT_ACCEPT,
  WORKSPACE_TEXT_EXTENSIONS,
} from './atelier.types';
export type {
  AtelierThreadItem,
  AtelierStreamingItem,
  AtelierExecStreamingItem,
  AtelierTurnCost,
} from './atelier.types';
export { WORKSPACE_TEXT_EXTENSIONS, WORKSPACE_TEXT_ACCEPT } from './atelier.types';

/**
 * Période de relevé du statut runner (F-38 / SF-38-06), en millisecondes.
 *
 * <p>15 s : le backend tolère déjà 90 s de silence avant de déclarer un runner absent
 * (`app.runner.heartbeat.stale-after`). Sonder plus vite n'apporterait rien de plus juste, et
 * ouvrir un canal poussé pour cette seule information coûterait plus qu'il ne rapporte.</p>
 */
export const RUNNER_STATUS_POLL_MS = 15_000;

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
    MatListModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatProgressSpinnerModule,
    RouterLink,
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

  /**
   * Reprise du fil (F-39 / SF-39-04, décision D5). Le fil reprend **sans rien demander** : ce signal
   * ne passe à `true` que lorsque la reprise ne va pas de soi — projet inactif depuis plus de deux
   * semaines — et l'écran propose alors un choix explicite plutôt que de décider à la place de
   * l'utilisateur.
   */
  readonly resumeChoice = signal(false);
  /** Date du dernier message du fil, affichée dans la proposition de reprise. */
  readonly resumeLastMessageAt = signal<string | null>(null);
  /** Nombre de tours encore rejoués : à zéro, « repartir à neuf » n'aurait rien à faire. */
  readonly resumeTurns = signal(0);

  /** Tour assistant « en cours » (étapes + texte partiel) affiché pendant le streaming (SF-28-05). */
  readonly streaming = signal<AtelierStreamingItem | null>(null);

  /**
   * Moteur qui anime le terminal du projet ouvert (F-39 / SF-39-08, décision D1). **Lu** auprès de
   * la gateway (`GET /engine`), jamais choisi ni déduit ici : la règle vivait à quatre endroits de
   * ce composant et avait déjà été inversée une fois entre F-31 et F-38.
   */
  readonly engine = signal<AtelierEngine>('HOSTED_SANDBOX');

  /** Vrai si le tour part sur la boucle maison (outils relayés vers la machine de l'utilisateur). */
  readonly localEngine = computed(() => this.engine() === 'LOCAL_MACHINE');

  /**
   * Limite du bac à sable qui justifie de proposer le runner **ici et maintenant** (F-39 / SF-39-09,
   * décision D6), ou `null` s'il n'y a rien à proposer. Calculée par la gateway (SF-39-07) : l'écran
   * ne devine jamais qu'un projet est « trop gros ».
   */
  readonly runnerHint = signal<AtelierRunnerRecommendation | null>(null);

  /**
   * Projets pour lesquels la proposition a été classée sans suite pendant cette session (D-L4-7).
   * Volontairement **non persisté** : la bande n'apparaît que sur une limite réellement rencontrée,
   * et celui qui la referme aujourd'hui aura peut-être changé d'avis demain — parce que le bac à
   * sable l'aura gêné entre-temps.
   */
  private readonly dismissedRunnerHints = new Set<string>();

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
   * Cible d'exécution du projet ouvert (F-38 / SF-38-05). Champ additif : absent d'un backend
   * antérieur ⇒ `SANDBOX`, exactement le comportement historique.
   */
  readonly executionTarget = computed<WorkspaceExecutionTarget>(
    () => this.activeDetail()?.executionTarget ?? 'SANDBOX');

  /** Vrai si les outils du projet s'exécutent sur la machine de l'utilisateur. */
  readonly runnerTarget = computed(() => this.executionTarget() === 'RUNNER');

  /** Vrai si le projet actif vit sur la machine de l'utilisateur (F-38 / SF-38-16). */
  readonly localProject = computed(
    () => this.workspaces().find((w) => w.id === this.activeWorkspaceId())?.source === 'LOCAL',
  );

  /**
   * Dossier du projet local, tel que le runner l'a déclaré à l'appairage (F-38 / SF-38-16). Rend
   * `null` tant qu'aucune machine ne s'est appairée : mieux vaut ne rien dire que d'annoncer un
   * dossier qu'on ne connaît pas encore.
   */
  readonly localFolder = computed(() => {
    if (!this.localProject()) {
      return null;
    }
    return this.workspaces().find((w) => w.id === this.activeWorkspaceId())?.runnerRootName ?? null;
  });

  /**
   * Dernier état runner relevé (F-38 / SF-38-02), ou `null` tant qu'aucun relevé n'a abouti —
   * « état inconnu » se dit, il ne se devine pas.
   */
  readonly runnerStatus = signal<RunnerStatus | null>(null);

  /** Bascule de cible d'exécution en vol : le sélecteur reste inerte le temps de l'aller-retour. */
  readonly switchingTarget = signal(false);

  /** Coupe-circuit en vol : le bouton reste inerte le temps de l'aller-retour. */
  readonly killingRunner = signal(false);

  /** Sondage du statut runner ; `null` hors cible `RUNNER`. */
  private runnerStatusTimer: ReturnType<typeof setInterval> | null = null;

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
    // Projet demandé par l'URL (F-30 SF-30-10) : `/atelier/{id}`. Le paramètre `?mode=terminal` des
    // liens antérieurs est **accepté et ignoré** — il n'y a plus qu'un terminal (F-39 / SF-39-08),
    // mais un lien déjà partagé doit continuer d'ouvrir le bon projet.
    this.requestedWorkspaceId = this.route.snapshot.paramMap.get('id');
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
   * Ouvre un projet qui vit **déjà sur la machine** de l'utilisateur (F-38 / SF-38-16).
   *
   * <p>Le parcours tient d'un seul tenant, parce qu'il est interrompu par une action hors du
   * navigateur — lancer une commande. On demande donc un <b>nom</b>, rien d'autre, puis on
   * enchaîne immédiatement sur l'écran d'appairage, où vivent le code, le binaire et la commande à
   * coller. Aucun chemin n'est demandé ici : c'est le runner qui déclarera sa racine.</p>
   */
  openLocalProjectDialog(): void {
    this.promptForName('Projet sur ma machine', '', (name) => {
      this.creating.set(true);
      this.atelier.createLocalWorkspace(name).subscribe({
        next: (workspace) => {
          this.creating.set(false);
          this.adoptWorkspace(workspace);
          // Enchaînement immédiat : un projet local sans machine connectée n'a nulle part où
          // travailler, et le dire après coup ferait perdre le fil au moment précis où il faut
          // sortir du navigateur.
          this.openRunnerPairing();
        },
        error: (err: unknown) => {
          this.creating.set(false);
          this.notifyError(
            httpErrorMessage(err, "Le projet n'a pas pu être créé. Veuillez réessayer."),
          );
        },
      });
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
   * Quitte le terminal (F-39 / SF-39-08). Il n'y a plus d'autre mode vers lequel basculer : quitter,
   * c'est refermer le projet et revenir à la liste.
   */
  leaveTerminal(): void {
    this.closeWorkspace();
  }

  /** Referme le projet ouvert et revient à la liste. */
  private closeWorkspace(): void {
    this.activeWorkspaceId.set(null);
    this.activeDetail.set(null);
    this.tree.set([]);
    this.messages.set([]);
    this.pushResult.set(null);
    this.pullRequest.set(null);
    this.stopRunnerPolling();
    this.runnerStatus.set(null);
    this.resetFilePanel();
    this.engine.set('HOSTED_SANDBOX');
    this.runnerHint.set(null);
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
    this.loadEngine(workspace);
    this.syncRunnerPolling();
  }

  /**
   * Relève le moteur du projet auprès de la gateway (F-39 / SF-39-07). C'est **elle** qui tranche :
   * l'écran ne déduit plus rien de la source ni de la cible.
   *
   * <p>Un relevé manqué ne doit jamais fermer l'Atelier : on retombe alors sur la cible déjà connue
   * du détail du projet, qui donne exactement la même réponse dans tous les cas nominaux — c'est un
   * repli, pas une seconde règle.</p>
   */
  private loadEngine(detail: WorkspaceDetail): void {
    this.engine.set(engineFromTarget(detail));
    this.atelier.getEngine(detail.id).subscribe({
      next: (status) => {
        this.engine.set(status.engine);
        this.runnerHint.set(this.hintFor(detail.id, status));
      },
      error: () => {
        this.engine.set(engineFromTarget(detail));
        // Ne rien proposer vaut mieux que proposer au hasard : sans relevé, aucune bande.
        this.runnerHint.set(null);
      },
    });
  }

  /**
   * Motif à afficher, ou `null`. Un `recommendRunner` sans motif ne dit rien — le motif **est** le
   * message — et un projet déjà classé sans suite pendant cette session n'est plus sollicité (D-L4-7).
   */
  private hintFor(workspaceId: string, status: AtelierEngineStatus): AtelierRunnerRecommendation | null {
    if (!status.recommendRunner || this.dismissedRunnerHints.has(workspaceId)) {
      return null;
    }
    return status.recommendReason;
  }

  /**
   * Classe la proposition sans suite pour ce projet (F-39 / SF-39-09). Le geste est un « plus tard »,
   * pas un « jamais » : rien n'est enregistré au-delà de la session.
   */
  dismissRunnerHint(): void {
    const id = this.activeWorkspaceId();
    if (id) {
      this.dismissedRunnerHints.add(id);
    }
    this.runnerHint.set(null);
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
    // L'état runner appartient au projet quitté : le garder afficherait le statut d'une autre machine.
    this.stopRunnerPolling();
    this.runnerStatus.set(null);
    this.resetFilePanel();
    this.resumeChoice.set(false);
    this.resumeLastMessageAt.set(null);
    this.resumeTurns.set(0);
    this.loadHistory(workspace.id);
    this.loadResumeState(workspace.id);
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

  /**
   * État de reprise du fil (F-39 / SF-39-04). Un échec est **silencieux** : ne pas savoir s'il faut
   * proposer un choix ne doit pas empêcher de travailler — le fil reprend, comme avant.
   */
  private loadResumeState(id: string): void {
    this.atelier.getResume(id).subscribe({
      next: (resume) => {
        this.resumeTurns.set(resume.turns);
        this.resumeLastMessageAt.set(resume.lastMessageAt);
        this.resumeChoice.set(resume.prompt === 'IDLE');
      },
      error: () => this.resumeChoice.set(false),
    });
  }

  /** « Reprendre le fil » : le comportement par défaut, la bannière disparaît. */
  keepThread(): void {
    this.resumeChoice.set(false);
  }

  /**
   * « Repartir à neuf » (F-39 / SF-39-04, décision D1) : les tours passés cessent d'être rejoués.
   * **Rien n'est supprimé** — la conversation reste affichée ; c'est ce qui rend le geste
   * réversible, et pourquoi il ne demande pas de confirmation destructive.
   */
  restartThread(): void {
    const id = this.activeWorkspaceId();
    if (!id) {
      return;
    }
    this.atelier.restartThread(id).subscribe({
      next: (resume) => {
        this.resumeTurns.set(resume.turns);
        this.resumeLastMessageAt.set(null);
        this.resumeChoice.set(false);
        this.snackBar.open(
          'Nouveau départ : Claude repart sans le contexte des tours précédents. La conversation reste affichée.',
          'Fermer',
          { duration: 5000 },
        );
      },
      error: () => this.notifyError('Impossible de repartir à neuf.'),
    });
  }

  private refreshTree(id: string): void {
    this.atelier.getWorkspace(id).subscribe({
      next: (detail) => {
        this.tree.set(detail.files);
        this.activeDetail.set(detail);
        this.loadEngine(detail);
        // Le statut runner n'a de sens qu'en cible RUNNER : le sondage suit la cible du projet.
        this.syncRunnerPolling();
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

    // Le moteur décide du chemin d'envoi (F-39 / SF-39-08) — jamais l'utilisateur, qui a saisi la
    // même demande dans le même terminal quel que soit l'endroit où elle s'exécutera.
    if (!this.localEngine()) {
      this.sendExec(id, content, userItem);
      return;
    }

    this.streaming.set({ steps: [], text: '' });
    // La boucle maison s'affiche dans la même vue terminal que le flux d'agent : les étapes du tour
    // sont converties en blocs (commande puis sortie) au fil de l'eau (D-L4-5).
    this.execStreaming.set({ status: '', blocks: [], text: '', tokens: null, plan: [] });
    this.startExecTimer();

    void this.atelier.streamChat(id, content, {
      onAction: (action) =>
        this.zone.run(() => {
          this.streaming.update((current) =>
            current ? { ...current, steps: [...current.steps, action] } : current,
          );
          this.mirrorLocalSteps();
        }),
      onText: (text) =>
        this.zone.run(() => {
          this.streaming.update((current) =>
            current ? { ...current, text: current.text + text } : current,
          );
          this.execStreaming.update((current) =>
            current ? { ...current, text: current.text + text } : current,
          );
        }),
      // Sortie de commande (F-38 / SF-38-07) : elle appartient à l'étape en cours — celle qui vient
      // d'être relayée. L'accumuler ailleurs la détacherait de la commande qui la produit.
      onOutput: (chunk) =>
        this.zone.run(() => {
          this.streaming.update((current) => {
            if (!current || current.steps.length === 0) {
              return current;
            }
            const steps = [...current.steps];
            const last = steps[steps.length - 1];
            steps[steps.length - 1] = { ...last, output: (last.output ?? '') + chunk };
            return { ...current, steps };
          });
          this.mirrorLocalSteps();
        }),
      // Autorisation demandée avant d'exécuter sur la machine connectée (F-38 / SF-38-08) : le
      // tour est en pause tant que rien n'est décidé, et le silence vaut refus.
      onConfirmRequest: (request) =>
        this.zone.run(() => this.showConfirmation(request, 'LOCAL_MACHINE')),
      onConfirmResolved: (resolved) => this.zone.run(() => this.clearConfirmation(resolved)),
      // Consommation relevée après chaque itération (F-39 / SF-39-15) : la ligne vivante affichait
      // les étapes et la durée, jamais les tokens, sur le moteur qui exécute réellement.
      onProgress: (tokens) =>
        this.zone.run(() =>
          this.execStreaming.update((current) => (current ? { ...current, tokens } : current)),
        ),
      // Plan de travail (F-39 / SF-39-13) : la liste complète REMPLACE la précédente. La ligne
      // vivante dit ce qui se passe à l'instant ; le plan dit ce qui reste.
      onPlan: (steps) =>
        this.zone.run(() =>
          this.execStreaming.update((current) => (current ? { ...current, plan: steps } : current)),
        ),
      onDone: (done) =>
        this.zone.run(() => {
          this.submitting.set(false);
          this.interrupting.set(false);
          // La transcription est reprise dans le tour final : sans cela, tout ce qui a défilé
          // pendant le tour disparaîtrait de l'écran (acquis F-30 SF-30-02).
          const transcript = this.execStreaming()?.blocks ?? [];
          // La durée est relevée par le backend (F-39 / SF-39-15) ; le chronomètre d'écran sert de
          // repli quand le champ manque — un backend antérieur ne l'émet pas.
          const elapsed = done.activeSeconds ?? this.execElapsedSeconds();
          this.stopExecTimer();
          this.streaming.set(null);
          this.execStreaming.set(null);
          // Plus rien n'attend de décision : une invite restée à l'écran serait un piège.
          this.pendingConfirmation.set(null);
          // Consommation à zéro = relevé manqué : on n'affiche alors aucun chiffre, plutôt qu'un
          // « 0 token » qui passerait pour une mesure (même règle qu'un relevé manqué côté agent,
          // F-30 SF-30-05).
          const tokens = (done.inputTokens ?? 0) + (done.outputTokens ?? 0);
          this.messages.update((current) => [
            ...current,
            {
              id: done.messageId,
              role: 'ASSISTANT',
              content: done.reply,
              actions: done.actions ?? [],
              terminal: transcript.length > 0 ? transcript : undefined,
              // Ce qu'a coûté le tour (acquis §4 n°6, SF-30-05) : la boucle maison ne le relevait
              // pas, si bien que l'acquis ne valait pas sur le moteur qui exécute réellement.
              cost: tokens > 0 ? { elapsedSeconds: elapsed, tokens } : undefined,
              // Plafond de consommation de CE message atteint (F-39 / SF-39-15) : le travail est
              // conservé, et l'écran le dit — un arrêt au milieu sans explication serait le pire
              // des deux mondes.
              budgetReached: done.budgetReached === true,
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
          this.interrupting.set(false);
          this.stopExecTimer();
          this.streaming.set(null);
          this.execStreaming.set(null);
          this.pendingConfirmation.set(null);
          // Retire le message utilisateur optimiste : rien n'a été persisté côté serveur.
          this.messages.update((current) => current.filter((m) => m.id !== userItem.id));
          this.notifyError(this.streamErrorMessage(code));
        }),
    });
  }

  /**
   * Recopie les étapes de la boucle maison dans la transcription terminal (F-39 / SF-39-08).
   *
   * <p>Les deux flux de l'Atelier n'ont pas la même forme ; c'est ici qu'ils se rejoignent, pour que
   * les acquis §4 de F-30 — commande puis sortie, repli des sorties longues, ligne vivante,
   * transcription conservée — valent des deux côtés.</p>
   */
  private mirrorLocalSteps(): void {
    const steps = this.streaming()?.steps ?? [];
    this.execStreaming.update((current) =>
      current ? { ...current, blocks: chatStepsToBlocks(steps) } : current,
    );
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
    // Un seul bouton « Interrompre » à l'écran (F-39 / SF-39-08) : c'est le moteur qui décide à qui
    // la demande s'adresse. Les deux chemins existent déjà et n'ont pas la même destination.
    if (this.localEngine()) {
      this.interruptLocalRun();
      return;
    }
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

  /**
   * Interrompt le tour de la **boucle maison** en cours (F-38 / SF-38-07). Utile surtout en cible
   * `RUNNER`, où une commande peut tourner plusieurs minutes sur la machine de l'utilisateur : sans
   * ce bouton, la seule sortie serait de fermer l'onglet en laissant la commande derrière soi.
   */
  interruptLocalRun(): void {
    const id = this.activeWorkspaceId();
    if (!id || !this.submitting() || this.interrupting()) {
      return;
    }
    this.interrupting.set(true);
    this.atelier.interruptChat(id).subscribe({
      next: () => {
        this.interrupting.set(false);
        this.snackBar.open('Interruption demandée : arrêt en cours…', 'Fermer', { duration: 4000 });
      },
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
  private showConfirmation(request: AtelierConfirmRequest, source: AtelierEngine): void {
    this.pendingConfirmation.set({
      toolUseId: request.toolUseId,
      tool: request.tool,
      detail: request.detail,
      source,
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
    const decision = {
      toolUseId: pending.toolUseId,
      decision: (allow ? 'allow' : 'deny') as 'allow' | 'deny',
      reason: !allow && reason.length > 0 ? reason : undefined,
    };
    // La question est la même des deux côtés, la destination non : boucle maison sur machine
    // connectée (F-38 / SF-38-08) ou session de bac à sable (F-33).
    const answer = pending.source === 'LOCAL_MACHINE'
      ? this.atelier.confirmChatToolUse(id, decision)
      : this.atelier.confirmToolUse(id, decision);
    answer
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

  /**
   * Bascule la **cible d'exécution** du projet (F-38 / SF-38-06) : sandbox hébergé ⇄ ma machine.
   *
   * <p>Aucune valeur optimiste : l'écran n'affiche la nouvelle cible qu'après confirmation du
   * backend. Cette cible décide d'où l'agent lit et où il écrit — annoncer « ma machine » alors que
   * le serveur a refusé enverrait des écritures là où l'utilisateur ne les attend pas.</p>
   */
  setExecutionTarget(target: WorkspaceExecutionTarget): void {
    const id = this.activeWorkspaceId();
    if (!id || this.switchingTarget() || this.submitting() || target === this.executionTarget()) {
      return;
    }
    this.switchingTarget.set(true);
    this.atelier.setExecutionTarget(id, target).subscribe({
      next: (detail) => {
        this.switchingTarget.set(false);
        this.activeDetail.set(detail);
        this.tree.set(detail.files);
        // La cible vient de changer : le moteur en découle, on le redemande à la gateway.
        this.loadEngine(detail);
        this.syncRunnerPolling();
      },
      error: (err: unknown) => {
        this.switchingTarget.set(false);
        this.notifyError(
          err instanceof HttpErrorResponse && err.status === 404
            ? 'Projet introuvable.'
            : "La cible d'exécution n'a pas pu être changée. Veuillez réessayer.");
      },
    });
  }

  /**
   * Relève l'état runner du projet ouvert. **Silencieux en cas d'échec** : ce relevé se répète toutes
   * les 15 secondes, une snackbar par tentative ratée noierait l'écran. L'état retombe simplement à
   * « inconnu » et le relevé suivant corrige.
   */
  refreshRunnerStatus(): void {
    const id = this.activeWorkspaceId();
    if (!id || !this.runnerTarget()) {
      return;
    }
    this.atelier.getRunnerStatus(id).subscribe({
      next: (status) => this.runnerStatus.set(status),
      error: () => this.runnerStatus.set(null),
    });
  }

  /**
   * Ouvre l'écran d'appairage d'une machine. Au retour, on relève le statut : l'utilisateur vient
   * peut-être de lancer son runner.
   */
  openRunnerPairing(): void {
    const id = this.activeWorkspaceId();
    if (!id) {
      return;
    }
    const data: RunnerPairingDialogData = { workspaceId: id, workspaceName: this.activeName() };
    this.dialog
      .open(RunnerPairingDialogComponent, { data, width: '560px', maxWidth: '95vw' })
      .afterClosed()
      .subscribe(() => this.refreshRunnerStatus());
  }

  /** Ouvre le journal d'activité de la machine (F-38 / SF-38-08, décision D11). */
  openRunnerAudit(): void {
    const id = this.activeWorkspaceId();
    if (!id) {
      return;
    }
    const data: RunnerAuditDialogData = { workspaceId: id, workspaceName: this.activeName() };
    this.dialog.open(RunnerAuditDialogComponent, { data, width: '640px', maxWidth: '95vw' });
  }

  /**
   * **Coupe-circuit** (F-38 / SF-38-08) : coupe la liaison avec la machine, révoque ses jetons et
   * ramène le projet sur le sandbox hébergé.
   *
   * <p>Confirmation préalable — le geste oblige à réappairer la machine ensuite — mais aucune
   * étape de plus : c'est le bouton qu'on cherche quand quelque chose se passe mal.</p>
   */
  killRunner(): void {
    const id = this.activeWorkspaceId();
    if (!id || this.killingRunner()) {
      return;
    }
    const data: ConfirmDialogData = {
      title: 'Couper la liaison avec la machine',
      message:
        'La connexion en cours est fermée, les jetons de ce projet sont révoqués et le projet '
        + "repasse sur le sandbox hébergé. Il faudra réappairer la machine pour l'utiliser à "
        + 'nouveau.',
      confirmLabel: 'Couper maintenant',
    };
    this.dialog
      .open(ConfirmDialogComponent, { data, width: '460px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          this.performKillRunner(id);
        }
      });
  }

  private performKillRunner(id: string): void {
    this.killingRunner.set(true);
    this.atelier.killRunner(id).subscribe({
      next: (result) => {
        this.killingRunner.set(false);
        // La cible qui fait foi est celle renvoyée par le backend, jamais celle qu'on espérait.
        const detail = this.activeDetail();
        if (detail) {
          const updated = { ...detail, executionTarget: result.executionTarget };
          this.activeDetail.set(updated);
          this.loadEngine(updated);
        }
        this.pendingConfirmation.set(null);
        this.runnerStatus.set(null);
        this.syncRunnerPolling();
        this.snackBar.open(
          result.revokedTokens > 0
            ? `Liaison coupée : ${result.revokedTokens} jeton(s) révoqué(s). Projet repassé sur le `
              + 'sandbox hébergé.'
            : 'Aucune liaison active. Projet repassé sur le sandbox hébergé.',
          'Fermer', { duration: 6000 });
      },
      error: (err: unknown) => {
        this.killingRunner.set(false);
        this.notifyError(
          err instanceof HttpErrorResponse && err.status === 404
            ? 'Projet introuvable.'
            : "La liaison n'a pas pu être coupée. Veuillez réessayer.");
      },
    });
  }

  /** Libellé de la pastille d'état runner. « Inconnu » tant qu'aucun relevé n'a abouti. */
  runnerStatusLabel(): string {
    const status = this.runnerStatus();
    if (!status) {
      return 'État du runner inconnu';
    }
    return status.connected ? 'Runner connecté' : 'Aucun runner connecté';
  }

  /**
   * Détail de la pastille : la dernière activité observée, en relatif. Le statut n'est **pas** du
   * temps réel — c'est cette date qui permet de juger sa fraîcheur.
   */
  runnerLastSeenLabel(): string | null {
    const lastSeen = this.runnerStatus()?.lastSeenAt;
    if (!lastSeen) {
      return null;
    }
    const elapsed = Math.floor((Date.now() - new Date(lastSeen).getTime()) / 1000);
    if (!Number.isFinite(elapsed) || elapsed < 0) {
      return null;
    }
    if (elapsed < 60) {
      return `dernier signe de vie il y a ${elapsed} s`;
    }
    if (elapsed < 3600) {
      return `dernier signe de vie il y a ${Math.floor(elapsed / 60)} min`;
    }
    return `dernier signe de vie il y a ${Math.floor(elapsed / 3600)} h`;
  }

  /**
   * Aligne le sondage du statut runner sur la cible courante : il ne tourne qu'en cible `RUNNER`,
   * avec un projet ouvert. Ailleurs, il est arrêté et l'état effacé.
   */
  private syncRunnerPolling(): void {
    if (!this.runnerTarget() || !this.activeWorkspaceId()) {
      this.stopRunnerPolling();
      this.runnerStatus.set(null);
      return;
    }
    this.refreshRunnerStatus();
    if (this.runnerStatusTimer === null) {
      this.runnerStatusTimer = setInterval(
        () => this.zone.run(() => this.refreshRunnerStatus()), RUNNER_STATUS_POLL_MS);
    }
  }

  /** Arrête le sondage ; idempotent (fermeture de projet, bascule de cible, destruction). */
  private stopRunnerPolling(): void {
    if (this.runnerStatusTimer !== null) {
      clearInterval(this.runnerStatusTimer);
      this.runnerStatusTimer = null;
    }
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
   * Envoie le message courant en mode **Terminal** (SF-28-11) : relaie l'état de session, les étapes
   * d'outils (bash, tests…) et le commentaire au fil de l'eau, puis ajoute la réponse finale et la
   * liste des fichiers modifiés, et rafraîchit l'arborescence. Le flux tourne hors zone Angular
   * (fetch) : chaque mise à jour de signal passe par {@link NgZone}. En cas d'erreur, le tour
   * utilisateur optimiste est retiré (rien n'a été persisté) et un message lisible est affiché.
   */
  private sendExec(id: string, content: string, userItem: AtelierThreadItem): void {
    this.execStreaming.set({ status: '', blocks: [], text: '', tokens: null, plan: [] });
    this.startExecTimer();

    void this.atelier.streamAgent(id, content, {
      onStatus: (state) =>
        this.zone.run(() => {
          this.execStreaming.update((current) => (current ? { ...current, status: state } : current));
        }),
      // Consommation relevée pendant le run (F-30 / SF-30-13) : alimente la ligne vivante.
      onProgress: (tokens) =>
        this.zone.run(() => {
          this.execStreaming.update((current) => (current ? { ...current, tokens } : current));
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
      onConfirmRequest: (request) => this.zone.run(() => this.showConfirmation(request, 'HOSTED_SANDBOX')),
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
        return "L'exécution est réservée à l'offre Gold.";
      case 'agent_disabled':
        return "L'exécution est momentanément indisponible.";
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

  /** Quitter l'écran ne doit laisser tourner ni le chronomètre, ni le sondage du statut runner. */
  ngOnDestroy(): void {
    this.stopExecTimer();
    this.stopRunnerPolling();
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

  /**
   * Icône Material d'une étape de streaming. Le type est une **chaîne libre** : le backend en ajoute
   * (`bash` en SF-38-07) et un type inconnu doit rester présentable, jamais déguisé en lecture.
   */
  stepIcon(type: string): string {
    switch (type) {
      case 'read':
        return 'visibility';
      case 'write':
        return 'edit';
      case 'list':
        return 'folder_open';
      case 'search':
        return 'search';
      case 'bash':
        return 'terminal';
      default:
        return 'bolt';
    }
  }

  /**
   * Libellé humain d'une étape de streaming en cours. Un type inconnu affiche son argument brut
   * plutôt qu'une étiquette fausse : mieux vaut « npm test » que « Lecture de npm test ».
   */
  stepLabel(step: AtelierStreamAction): string {
    switch (step.type) {
      case 'read':
        return `Lecture de ${step.path}`;
      case 'write':
        return `Édition de ${step.path}`;
      case 'list':
        return 'Liste des fichiers';
      case 'search':
        return `Recherche « ${step.path} »`;
      default:
        return step.path ?? step.type;
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


/**
 * Moteur déduit de la cible d'exécution (F-39 / SF-39-08) — **repli seulement**, quand le relevé
 * `GET /engine` n'a pas abouti. La règle de référence vit côté gateway (SF-39-07) ; celle-ci existe
 * pour qu'un aller-retour manqué n'empêche pas d'ouvrir un projet, jamais pour la doubler.
 */
function engineFromTarget(detail: WorkspaceDetail): AtelierEngine {
  return detail.executionTarget === 'RUNNER' ? 'LOCAL_MACHINE' : 'HOSTED_SANDBOX';
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
  // Le coût vit lui aussi hors des blocs (F-39 / SF-39-15) : la boucle maison relève sa
  // consommation sans persister de transcription, et un tour mesuré perdait sa mesure ici.
  const tokens = (stored.inputTokens ?? 0) + (stored.outputTokens ?? 0);
  if (tokens > 0) {
    item.cost = { elapsedSeconds: stored.activeSeconds ?? 0, tokens };
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
  return item;
}
