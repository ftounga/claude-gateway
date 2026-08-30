import { NgTemplateOutlet } from '@angular/common';
import { Component, HostListener, NgZone, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { httpErrorMessage } from '../../shared/http-error.util';
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from '../../chat/confirm-dialog/confirm-dialog.component';
import {
  LibraryPickerDialogComponent,
  PickedLibraryDocument,
} from '../../chat/library-picker/library-picker-dialog.component';
import { WorkspaceDetail } from '../../core/models/atelier.models';
import { AtelierService } from '../../core/services/atelier.service';
import { WORKSPACE_TEXT_ACCEPT, WORKSPACE_TEXT_EXTENSIONS } from '../atelier.component';
import { TreeNode, buildTree } from './file-tree';
import {
  GitBranchDialogComponent,
  GitBranchDialogData,
} from '../git/git-branch-dialog.component';
import {
  GitPushDialogComponent,
  GitPushDialogData,
  PickedGitPush,
} from '../git/git-push-dialog.component';
import {
  TextPromptDialogComponent,
  TextPromptDialogData,
} from './text-prompt-dialog.component';

/** Message de commit par défaut quand l'utilisateur n'en saisit pas. */
const DEFAULT_COMMIT_MESSAGE = 'Modifications depuis l\'Atelier';

/**
 * Page « Explorateur de fichiers » de l'Atelier (F-28 / SF-28-15). Remplace le tiroir « Fichiers »
 * par un vrai gestionnaire : arborescence de dossiers repliable dérivée des chemins plats, ajout
 * (PC/bibliothèque, réutilise SF-28-13), renommer / télécharger / supprimer (SF-28-14), nouveau
 * dossier (`.gitkeep`), export `.zip`, aperçu/édition et recherche filtrante.
 *
 * <p>Frontend pur : ne parle qu'à la Gateway via {@link AtelierService} (aucun accès direct à un
 * fournisseur). L'isolation `user_id` et le gating Gold sont garantis côté backend ; un 403/404 est
 * traduit en message + retour à l'Atelier.</p>
 */
@Component({
  selector: 'app-atelier-files',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    RouterLink,
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    MatMenuModule,
    MatFormFieldModule,
    MatSelectModule,
    MatInputModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './atelier-files.component.html',
  styleUrl: './atelier-files.component.scss',
})
export class AtelierFilesComponent implements OnInit {
  private readonly atelier = inject(AtelierService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly zone = inject(NgZone);

  /** Attribut `accept` du sélecteur PC (texte/code uniquement, réutilisé de SF-28-13). */
  readonly workspaceTextAccept = WORKSPACE_TEXT_ACCEPT;

  readonly workspaceId = signal<string>('');
  readonly workspaceName = signal<string>('');

  /** Liste plate des chemins renvoyée par le backend (source de l'arbre). */
  readonly paths = signal<string[]>([]);

  /** Filtre de recherche (sous-chaîne, insensible à la casse) appliqué aux chemins. */
  readonly search = signal<string>('');

  /** Dossiers repliés (par chemin). Les dossiers sont dépliés par défaut. */
  readonly collapsed = signal<Set<string>>(new Set());

  readonly loading = signal(false);
  readonly notFound = signal(false);

  readonly selectedPath = signal<string | null>(null);
  readonly fileContent = signal('');
  readonly fileLoading = signal(false);
  readonly fileSaving = signal(false);

  /** Chemins filtrés par la recherche (tous si la recherche est vide). */
  private readonly filteredPaths = computed(() => {
    const q = this.search().trim().toLowerCase();
    if (q.length === 0) {
      return this.paths();
    }
    return this.paths().filter((p) => p.toLowerCase().includes(q));
  });

  /** Arbre imbriqué dérivé des chemins filtrés (dossiers avant fichiers, tri alpha). */
  readonly nodes = computed<TreeNode[]>(() => buildTree(this.filteredPaths()));

  /** Nombre de fichiers réels (hors `.gitkeep`) affichés. */
  readonly fileCount = computed(
    () => this.filteredPaths().filter((p) => !p.endsWith('/.gitkeep') && p !== '.gitkeep').length,
  );

  /**
   * Projet adossé à un dépôt Git (F-31 / SF-31-03, amendé par SF-31-09).
   *
   * <p>L'explorateur n'écrit toujours pas dans le stockage — ce serait la deuxième vérité que
   * SF-31-03 a refusée. Mais l'édition n'est plus interdite pour autant : les modifications sont
   * <b>retenues</b> ici, puis publiées en un commit sur une branche dédiée (SF-31-08). Le nom reste
   * `readOnly` pour ce qu'il gouverne encore : l'ajout, la suppression, le renommage et l'export,
   * qui n'ont pas de sens sur un dépôt.</p>
   */
  readonly readOnly = signal(false);

  /** Branches du dépôt (F-31 / SF-31-10), chargées à l'ouverture d'un projet Git. */
  readonly branches = signal<string[]>([]);

  /** Branche par défaut du dépôt : la seule sur laquelle publier reste interdit. */
  readonly defaultBranch = signal<string | null>(null);

  /** Changement de branche en cours : évite deux bascules concurrentes. */
  readonly switchingBranch = signal(false);


  /**
   * Modifications retenues, non encore publiées (F-31 / SF-31-09) : chemin → contenu.
   *
   * <p>Elles vivent le temps de l'écran. Les persister donnerait l'illusion d'un brouillon
   * sauvegardé, alors qu'aucun serveur ne les connaît tant que la publication n'a pas eu lieu.</p>
   */
  readonly pendingEdits = signal<ReadonlyMap<string, string>>(new Map());

  /** Nombre de fichiers touchés — pas d'enregistrements : deux sauvegardes du même fichier font un. */
  readonly pendingCount = computed(() => this.pendingEdits().size);

  /** Publication en cours : évite un double envoi et grise le bouton. */
  readonly publishing = signal(false);

  /** `owner/repo` et branche montés, affichés en tête de l'explorateur d'un projet Git. */
  readonly gitRepo = signal<string | null>(null);
  readonly gitBranch = signal<string | null>(null);

  /**
   * Arborescence **partielle** (dépôt très volumineux) : le dire évite de faire conclure qu'un
   * fichier absent de la liste n'existe pas.
   */
  readonly truncated = signal(false);

  /**
   * Fichier à ouvrir dès le chargement, désigné par `?path=` (F-34 / SF-34-02 : la pastille
   * « Instructions » amène directement sur le fichier). Consommé une fois : une navigation interne
   * ultérieure ne doit pas ramener l'utilisateur sur ce fichier.
   */
  private pendingPath: string | null = null;

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.router.navigate(['/atelier']);
      return;
    }
    this.workspaceId.set(id);
    this.pendingPath = this.route.snapshot.queryParamMap.get('path');
    this.loadWorkspace();
  }

  /**
   * Ouvre le fichier demandé par `?path=`, s'il existe dans l'arborescence chargée. Un chemin inconnu
   * est ignoré en silence : l'explorateur s'affiche normalement plutôt que de crier sur un lien
   * devenu obsolète (fichier renommé ou supprimé depuis).
   */
  private openPendingPath(): void {
    const path = this.pendingPath;
    this.pendingPath = null;
    if (path && this.paths().includes(path)) {
      this.openFile(path);
    }
  }

  /** Charge le détail du workspace (nom + arborescence). 404 → message + retour Atelier. */
  private loadWorkspace(): void {
    this.loading.set(true);
    this.atelier.getWorkspace(this.workspaceId()).subscribe({
      next: (detail) => {
        this.loading.set(false);
        this.workspaceName.set(detail.name);
        this.paths.set(detail.files);
        this.applySource(detail);
        this.openPendingPath();
      },
      error: (err) => {
        this.loading.set(false);
        this.notFound.set(true);
        this.notifyError(httpErrorMessage(err, 'Projet introuvable.'));
      },
    });
  }

  private refreshTree(): void {
    this.atelier.getWorkspace(this.workspaceId()).subscribe({
      next: (detail) => {
        this.workspaceName.set(detail.name);
        this.paths.set(detail.files);
        this.applySource(detail);
      },
      error: () => this.notifyError("Impossible de rafraîchir l'arborescence du projet."),
    });
  }

  /** Retient ce que la source du projet change à l'écran : lecture seule, dépôt, branche, troncage. */
  private applySource(detail: WorkspaceDetail): void {
    this.readOnly.set(detail.source === 'GIT');
    if (detail.source === 'GIT') {
      this.loadBranches();
    }
    this.gitRepo.set(detail.gitRepo);
    this.gitBranch.set(detail.gitBranch);
    this.truncated.set(detail.truncated);
  }

  // ---- Arbre : repli / sélection ----

  /** Un dossier est déplié sauf s'il est explicitement replié ; en recherche, tout est déplié. */
  isExpanded(path: string): boolean {
    if (this.search().trim().length > 0) {
      return true;
    }
    return !this.collapsed().has(path);
  }

  /** Replie / déplie un dossier. */
  toggleFolder(path: string): void {
    this.collapsed.update((current) => {
      const next = new Set(current);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
      }
      return next;
    });
  }

  /** Badge d'extension d'un fichier (segment après le dernier point, minuscule). */
  fileExtension(name: string): string {
    const dot = name.lastIndexOf('.');
    return dot > 0 ? name.slice(dot + 1).toLowerCase() : '';
  }

  // ---- Aperçu / édition ----

  /** Charge le contenu d'un fichier dans l'aperçu éditable (réutilise `getFile`). */
  openFile(path: string): void {
    this.selectedPath.set(path);
    const pending = this.pendingEdits().get(path);
    if (pending !== undefined) {
      // Une modification retenue prime sur la version de la branche : sinon l'utilisateur verrait
      // son propre travail disparaître en rouvrant le fichier.
      this.fileContent.set(pending);
      this.fileLoading.set(false);
      return;
    }
    this.fileLoading.set(true);
    this.atelier.getFile(this.workspaceId(), path).subscribe({
      next: (file) => {
        this.fileContent.set(file.content);
        this.fileLoading.set(false);
      },
      error: (err) => {
        this.fileLoading.set(false);
        this.notifyError(httpErrorMessage(err, 'Impossible de charger le fichier.'));
      },
    });
  }

  /**
   * Enregistre le contenu édité.
   *
   * <p>Sur un projet Git, « enregistrer » ne part pas sur le réseau : la modification est
   * <b>retenue</b> jusqu'à la publication (SF-31-09). Le backend refuserait de toute façon une
   * écriture dans le stockage d'un projet Git (`git_workspace_read_only`).</p>
   */
  saveFile(): void {
    const path = this.selectedPath();
    if (!path || this.fileSaving()) {
      return;
    }
    if (this.readOnly()) {
      const next = new Map(this.pendingEdits());
      next.set(path, this.fileContent());
      this.pendingEdits.set(next);
      this.snackBar.open(
        `Modification retenue — ${next.size} fichier(s) à publier.`,
        'Fermer',
        { duration: 2500 },
      );
      return;
    }
    this.fileSaving.set(true);
    this.atelier.writeFile(this.workspaceId(), path, this.fileContent()).subscribe({
      next: () => {
        this.fileSaving.set(false);
        this.snackBar.open('Fichier enregistré.', 'Fermer', { duration: 2000 });
      },
      error: (err) => {
        this.fileSaving.set(false);
        this.notifyError(httpErrorMessage(err, "L'enregistrement du fichier a échoué."));
      },
    });
  }

  /**
   * Publie toutes les modifications retenues en <b>un commit</b> sur une branche dédiée
   * (F-31 / SF-31-09, endpoint de SF-31-08).
   *
   * <p>Réutilise le dialogue de SF-31-04 : même formulaire, même refus de la branche de base à la
   * saisie. Un échec <b>conserve</b> la file — on ne fait pas disparaître le travail de
   * l'utilisateur parce que GitHub a répondu non.</p>
   */
  publishEdits(): void {
    if (this.pendingCount() === 0 || this.publishing()) {
      return;
    }
    const dialogRef = this.dialog.open(GitPushDialogComponent, {
      width: '520px',
      data: {
        gitRepo: this.gitRepo(),
        baseBranch: this.gitBranch(),
        suggestedBranch: this.suggestedBranch(),
      } satisfies GitPushDialogData,
    });
    dialogRef.afterClosed().subscribe((picked?: PickedGitPush) => {
      if (!picked) {
        return;
      }
      this.sendCommit(picked.branch ?? this.suggestedBranch(), picked.message ?? DEFAULT_COMMIT_MESSAGE);
    });
  }

  private sendCommit(branch: string, message: string): void {
    const files = [...this.pendingEdits()].map(([path, content]) => ({ path, content }));
    this.publishing.set(true);
    this.atelier.commitGitFiles(this.workspaceId(), branch, message, files).subscribe({
      next: (result) => {
        this.publishing.set(false);
        this.pendingEdits.set(new Map());
        const link = result.pullRequestUrl ?? result.compareUrl;
        this.snackBar
          .open(
            `${files.length} fichier(s) publié(s) sur ${result.branch}.`,
            result.pullRequestUrl ? 'Voir la pull request' : 'Voir les modifications',
            { duration: 12000 },
          )
          .onAction()
          .subscribe(() => window.open(link, '_blank', 'noopener'));
        this.warnOtherBranch(result.branch);
      },
      error: (err) => {
        this.publishing.set(false);
        // La file reste intacte : l'utilisateur peut corriger la branche et republier.
        this.notifyError(httpErrorMessage(err, 'La publication a échoué.'));
      },
    });
  }

  /**
   * Après une publication faite depuis l'écran, dire <b>exactement</b> où vit le travail.
   *
   * <p>La première version de ce message conseillait de réinitialiser la session « pour repartir du
   * commit publié ». C'était faux : la session monte le dépôt sur la branche du projet
   * ({@code workspace.gitBranch}), et une réinitialisation y reviendrait — sans le commit, qui vit
   * sur la branche de publication. Elle parlait aussi d'une « version précédente du dépôt », alors
   * que la branche du projet n'a pas bougé.</p>
   *
   * <p>Ce qui est vrai, et suffit : le travail est sur une branche que la session ne voit pas.</p>
   */
  private warnOtherBranch(published: string): void {
    const base = this.gitBranch() ?? 'la branche du projet';
    this.snackBar.open(
      `Vos modifications sont sur ${published}. La session Claude travaille sur ${base} et ne les voit pas.`,
      'Compris',
      { duration: 14000 },
    );
  }

  /**
   * Garde de sortie (F-31 / SF-31-09) : les modifications retenues vivent le temps de l'écran.
   * Partir sans le dire les perdrait en silence — c'est le seul endroit où l'utilisateur peut
   * encore décider.
   *
   * <p>Branchée sur `beforeunload` (fermeture d'onglet, rechargement) et sur les deux sorties
   * internes de l'écran, qui passent par {@link #confirmLeave}.</p>
   */
  @HostListener('window:beforeunload', ['$event'])
  onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.pendingCount() > 0) {
      event.preventDefault();
      // Les navigateurs imposent leur propre libellé ; seul le fait de bloquer nous appartient.
      event.returnValue = true;
    }
  }

  /**
   * Demande confirmation avant de quitter l'écran avec des modifications non publiées.
   *
   * @param destination route de destination
   */
  confirmLeave(destination: unknown[], queryParams?: Record<string, string>): void {
    const go = () => void this.router.navigate(destination, queryParams ? { queryParams } : {});
    if (this.pendingCount() === 0) {
      go();
      return;
    }
    const ref = this.dialog.open(ConfirmDialogComponent, {
      width: '460px',
      data: {
        title: 'Modifications non publiées',
        message:
          `${this.pendingCount()} fichier(s) modifié(s) n'ont pas été publiés. ` +
          'En quittant cet écran, ces modifications seront perdues.',
        confirmLabel: 'Quitter sans publier',
      } satisfies ConfirmDialogData,
    });
    ref.afterClosed().subscribe((confirmed) => {
      if (confirmed) {
        go();
      }
    });
  }

  /** Retour au terminal du projet, sous la même garde que le retour au projet. */
  confirmLeaveToTerminal(): void {
    this.confirmLeave(['/atelier', this.workspaceId()], { mode: 'terminal' });
  }

  private loadBranches(): void {
    this.atelier.gitBranches(this.workspaceId()).subscribe({
      next: (result) => {
        this.branches.set(result.branches);
        this.defaultBranch.set(result.defaultBranch);
        this.gitBranch.set(result.current);
      },
      // Silencieux : ne pas pouvoir lister les branches ne doit pas empêcher de lire les fichiers.
      error: () => this.branches.set([]),
    });
  }

  /**
   * Place le projet sur une autre branche (F-31 / SF-31-10) : l'arborescence et les fichiers en
   * viennent aussitôt, et la prochaine session Claude la montera.
   */
  switchBranch(branch: string): void {
    if (branch === this.gitBranch() || this.switchingBranch()) {
      return;
    }
    if (this.pendingCount() > 0) {
      // Changer de branche abandonnerait des modifications qui n'existent qu'ici.
      const ref = this.dialog.open(ConfirmDialogComponent, {
        width: '460px',
        data: {
          title: 'Modifications non publiées',
          message:
            `${this.pendingCount()} fichier(s) modifié(s) seront perdus en changeant de branche.`,
          confirmLabel: 'Changer sans publier',
        } satisfies ConfirmDialogData,
      });
      ref.afterClosed().subscribe((confirmed) => {
        if (confirmed) {
          this.pendingEdits.set(new Map());
          this.applyBranch(branch);
        }
      });
      return;
    }
    this.applyBranch(branch);
  }

  private applyBranch(branch: string): void {
    const previous = this.gitBranch();
    this.switchingBranch.set(true);
    this.atelier.switchGitBranch(this.workspaceId(), branch).subscribe({
      next: () => {
        this.switchingBranch.set(false);
        this.selectedPath.set(null);
        this.fileContent.set('');
        this.loadWorkspace();
        this.noteSessionBranch(previous);
        this.snackBar.open(`Projet placé sur ${branch}.`, 'Fermer', { duration: 3000 });
      },
      error: (err) => {
        this.switchingBranch.set(false);
        this.notifyError(httpErrorMessage(err, 'Le changement de branche a échoué.'));
      },
    });
  }

  /** Crée une branche depuis la courante, puis s'y place. */
  createBranch(): void {
    const ref = this.dialog.open(GitBranchDialogComponent, {
      width: '480px',
      data: { fromBranch: this.gitBranch() ?? '', existing: this.branches() } satisfies GitBranchDialogData,
    });
    ref.afterClosed().subscribe((name?: string) => {
      if (!name) {
        return;
      }
      this.switchingBranch.set(true);
      const previous = this.gitBranch();
      this.atelier.createGitBranch(this.workspaceId(), name).subscribe({
        next: () => {
          this.switchingBranch.set(false);
          this.loadWorkspace();
          this.noteSessionBranch(previous);
          this.snackBar.open(`Branche ${name} créée, projet placé dessus.`, 'Fermer', { duration: 4000 });
        },
        error: (err) => {
          this.switchingBranch.set(false);
          this.notifyError(httpErrorMessage(err, 'La création de la branche a échoué.'));
        },
      });
    });
  }

  /**
   * Après un changement de branche, dire ce qu'il advient de la session Claude.
   *
   * <p>Formulé au conditionnel parce que l'écran ne sait pas si une session est ouverte — le
   * prétendre serait une supposition de plus. Ici, contrairement au message corrigé en #209, le
   * conseil de réinitialisation est <b>exact</b> : la session monte `workspace.gitBranch` à son
   * ouverture, donc la rouvrir la placera sur la nouvelle branche.</p>
   */
  private noteSessionBranch(previous: string | null): void {
    if (!previous) {
      return;
    }
    this.snackBar.open(
      `Si une session Claude est ouverte, elle travaille encore sur ${previous} — réinitialisez-la pour qu'elle suive la nouvelle branche.`,
      'Compris',
      { duration: 12000 },
    );
  }

  /** Nom de branche proposé : reconnaissable, horodaté, jamais la branche de base. */
  private suggestedBranch(): string {
    const stamp = new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '');
    return `claude/edition-${stamp}`;
  }

  /** Fil d'Ariane du fichier ouvert (segments de dossier, sans le nom du fichier). */
  breadcrumbDirs(path: string | null): string[] {
    if (!path) {
      return [];
    }
    const parts = path.split('/');
    parts.pop();
    return parts;
  }

  /** Nom court (dernier segment) d'un chemin. */
  baseName(path: string): string {
    const parts = path.split('/');
    return parts[parts.length - 1];
  }

  // ---- Barre d'outils : ajout PC / bibliothèque ----

  /**
   * Ajoute un fichier **texte/code** du PC (réutilise la logique SF-28-13) : refuse les binaires,
   * lit en texte via {@link FileReader} puis `writeFile`, et rafraîchit l'arbre.
   */
  async onFilePicked(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
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
    this.atelier.writeFile(this.workspaceId(), file.name, content).subscribe({
      next: () => {
        this.refreshTree();
        this.snackBar.open('Fichier ajouté.', 'Fermer', { duration: 3000 });
      },
      error: (err) => this.notifyError(httpErrorMessage(err, "L'ajout du fichier a échoué.")),
    });
  }

  /** Ouvre le sélecteur de bibliothèque (réutilise SF-28-13) et importe le texte des documents choisis. */
  openLibraryPicker(): void {
    this.dialog
      .open(LibraryPickerDialogComponent, { width: '560px', autoFocus: false })
      .afterClosed()
      .subscribe((picked: PickedLibraryDocument[] | undefined) => {
        if (!picked || picked.length === 0) {
          return;
        }
        this.atelier.importLibrary(this.workspaceId(), picked.map((d) => d.id)).subscribe({
          next: (detail) => {
            this.paths.set(detail.files);
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

  // ---- Barre d'outils : nouveau dossier / export ----

  /**
   * Crée un dossier en écrivant un marqueur `<dossier>/.gitkeep` (le workspace n'a pas de notion de
   * dossier vide côté stockage). Saisie du nom via `MatDialog` (jamais `window.prompt`).
   */
  newFolder(): void {
    const data: TextPromptDialogData = {
      title: 'Nouveau dossier',
      label: 'Nom du dossier',
      confirmLabel: 'Créer',
      hint: 'Ex : src/utils',
    };
    this.dialog
      .open(TextPromptDialogComponent, { width: '420px', data })
      .afterClosed()
      .subscribe((name: string | undefined) => {
        if (!name) {
          return;
        }
        const folder = name.replace(/^\/+|\/+$/g, '');
        if (folder.length === 0) {
          return;
        }
        this.atelier.writeFile(this.workspaceId(), `${folder}/.gitkeep`, '').subscribe({
          next: () => {
            this.refreshTree();
            this.snackBar.open('Dossier créé.', 'Fermer', { duration: 3000 });
          },
          error: (err) => this.notifyError(httpErrorMessage(err, 'La création du dossier a échoué.')),
        });
      });
  }

  /** Exporte tout le workspace en `.zip` et déclenche le téléchargement (réutilise `exportZip`). */
  exportZip(): void {
    this.atelier.exportZip(this.workspaceId()).subscribe({
      next: (blob) => {
        const name = this.workspaceName().trim() || 'workspace';
        this.triggerDownload(blob, `${name}.zip`);
      },
      error: (err) => this.notifyError(httpErrorMessage(err, "L'export du projet a échoué.")),
    });
  }

  // ---- Actions par fichier : renommer / télécharger / supprimer ----

  /** Renomme (ou déplace) un fichier : saisie du nouveau chemin via `MatDialog` puis `renameFile`. */
  renameFile(path: string, event?: Event): void {
    event?.stopPropagation();
    const data: TextPromptDialogData = {
      title: 'Renommer le fichier',
      label: 'Nouveau chemin',
      confirmLabel: 'Renommer',
      initialValue: path,
    };
    this.dialog
      .open(TextPromptDialogComponent, { width: '480px', data })
      .afterClosed()
      .subscribe((to: string | undefined) => {
        if (!to || to === path) {
          return;
        }
        this.atelier.renameFile(this.workspaceId(), path, to).subscribe({
          next: (detail) => {
            this.paths.set(detail.files);
            if (this.selectedPath() === path) {
              this.selectedPath.set(to);
            }
            this.snackBar.open('Fichier renommé.', 'Fermer', { duration: 3000 });
          },
          error: (err) => this.notifyError(httpErrorMessage(err, 'Le renommage a échoué.')),
        });
      });
  }

  /** Télécharge un fichier : lit son contenu (`getFile`) puis déclenche le téléchargement texte. */
  downloadFile(path: string, event?: Event): void {
    event?.stopPropagation();
    this.atelier.getFile(this.workspaceId(), path).subscribe({
      next: (file) => {
        const blob = new Blob([file.content], { type: 'text/plain;charset=utf-8' });
        this.triggerDownload(blob, this.baseName(path));
      },
      error: (err) => this.notifyError(httpErrorMessage(err, 'Le téléchargement a échoué.')),
    });
  }

  /** Supprime un fichier après **confirmation** `MatDialog` puis `deleteFile` + refresh. */
  deleteFile(path: string, event?: Event): void {
    event?.stopPropagation();
    const data: ConfirmDialogData = {
      title: 'Supprimer le fichier',
      message: `Supprimer « ${path} » ? Cette action est définitive.`,
      confirmLabel: 'Supprimer',
    };
    this.dialog
      .open(ConfirmDialogComponent, { width: '440px', data })
      .afterClosed()
      .subscribe((confirmed: boolean | undefined) => {
        if (!confirmed) {
          return;
        }
        this.atelier.deleteFile(this.workspaceId(), path).subscribe({
          next: () => {
            if (this.selectedPath() === path) {
              this.selectedPath.set(null);
              this.fileContent.set('');
            }
            this.refreshTree();
            this.snackBar.open('Fichier supprimé.', 'Fermer', { duration: 3000 });
          },
          error: (err) => this.notifyError(httpErrorMessage(err, 'La suppression a échoué.')),
        });
      });
  }

  // ---- Helpers ----

  /**
   * Détecte un fichier binaire à refuser à l'ajout PC (repris de SF-28-13) : MIME image/audio/vidéo/
   * PDF/archive/binaire, ou extension hors liste texte/code quand le MIME n'est pas `text/*`.
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

  /** Déclenche le téléchargement d'un blob sous un nom donné (lien objet temporaire). */
  private triggerDownload(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    link.click();
    URL.revokeObjectURL(url);
  }

  private notifyError(message: string): void {
    this.zone.run(() =>
      this.snackBar.open(message, 'Fermer', { duration: 4000, panelClass: 'snack-error' }),
    );
  }
}
