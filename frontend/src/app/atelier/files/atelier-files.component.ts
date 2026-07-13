import { NgTemplateOutlet } from '@angular/common';
import { Component, NgZone, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
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
import { AtelierService } from '../../core/services/atelier.service';
import { WORKSPACE_TEXT_ACCEPT, WORKSPACE_TEXT_EXTENSIONS } from '../atelier.component';
import { TreeNode, buildTree } from './file-tree';
import {
  TextPromptDialogComponent,
  TextPromptDialogData,
} from './text-prompt-dialog.component';

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

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.router.navigate(['/atelier']);
      return;
    }
    this.workspaceId.set(id);
    this.loadWorkspace();
  }

  /** Charge le détail du workspace (nom + arborescence). 404 → message + retour Atelier. */
  private loadWorkspace(): void {
    this.loading.set(true);
    this.atelier.getWorkspace(this.workspaceId()).subscribe({
      next: (detail) => {
        this.loading.set(false);
        this.workspaceName.set(detail.name);
        this.paths.set(detail.files);
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
      },
      error: () => this.notifyError("Impossible de rafraîchir l'arborescence du projet."),
    });
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

  /** Enregistre le contenu édité (réutilise `writeFile`). */
  saveFile(): void {
    const path = this.selectedPath();
    if (!path || this.fileSaving()) {
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
