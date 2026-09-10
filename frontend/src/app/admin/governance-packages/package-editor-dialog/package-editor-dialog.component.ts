import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';

import { GovernanceControl, GovernanceFileKind } from '../../../core/models/governance.models';
import {
  GovernanceFileDetail,
  GovernancePackageAdmin,
  GovernancePackageDraft,
} from '../../governance-admin.models';

/** Ce que le formulaire reçoit : le paquet à modifier (ou rien), et les contrôles disponibles. */
export interface PackageEditorData {
  pkg: GovernancePackageAdmin | null;
  controls: GovernanceControl[];
}

/**
 * Rédiger un paquet de gouvernance (F-51 / SF-51-06).
 *
 * <p>Deux choses que ce formulaire dit explicitement, parce qu'elles surprendraient sinon :</p>
 * <ul>
 *   <li>le <b>slug est immuable</b> — il est ce qui identifie un paquet d'une version à l'autre, y
 *       compris à l'œil nu dans un journal ; il est donc verrouillé en modification ;</li>
 *   <li>enregistrer <b>remplace intégralement</b> le contenu et incrémente la version. Laisser croire
 *       à une édition partielle mentirait sur ce qui part.</li>
 * </ul>
 *
 * <p>Les <b>contrôles se choisissent</b>, ils ne se tapent pas : le backend refuse un identifiant
 * qu'il ne fournit pas, autant ne jamais donner l'occasion d'en inventer un (arbitrage F2).</p>
 *
 * <p>Les bornes (longueurs, nombre de fichiers) sont rappelées en indication mais <b>tranchées par le
 * backend</b> : une seule définition de la règle, et c'est celle qui s'applique.</p>
 */
@Component({
  selector: 'app-package-editor-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
  ],
  templateUrl: './package-editor-dialog.component.html',
  styleUrl: './package-editor-dialog.component.scss',
})
export class PackageEditorDialogComponent {
  readonly data = inject<PackageEditorData>(MAT_DIALOG_DATA);
  private readonly dialogRef =
    inject<MatDialogRef<PackageEditorDialogComponent, GovernancePackageDraft>>(MatDialogRef);

  /** Vrai en modification : le slug est alors verrouillé. */
  readonly editing = !!this.data.pkg;

  readonly slug = signal(this.data.pkg?.slug ?? '');
  readonly name = signal(this.data.pkg?.name ?? '');
  readonly summary = signal(this.data.pkg?.summary ?? '');
  readonly rules = signal(this.data.pkg?.rules ?? '');
  readonly controlIds = signal<string[]>(
    (this.data.pkg?.controls ?? []).map((control) => control.id),
  );
  readonly files = signal<GovernanceFileDetail[]>(
    (this.data.pkg?.files ?? []).map((file) => ({ ...file })),
  );

  addFile(): void {
    this.files.update((files) => [...files, { path: '', kind: 'TEMPLATE', content: '' }]);
  }

  removeFile(index: number): void {
    this.files.update((files) => files.filter((_, position) => position !== index));
  }

  setFilePath(index: number, path: string): void {
    this.patchFile(index, { path });
  }

  setFileKind(index: number, kind: GovernanceFileKind): void {
    this.patchFile(index, { kind });
  }

  setFileContent(index: number, content: string): void {
    this.patchFile(index, { content });
  }

  cancel(): void {
    this.dialogRef.close();
  }

  /** Rend le contenu saisi. La validation, elle, appartient au backend — et à lui seul. */
  save(): void {
    this.dialogRef.close({
      slug: this.slug().trim(),
      name: this.name().trim(),
      summary: this.summary().trim() || null,
      rules: this.rules().trim() || null,
      controlIds: this.controlIds(),
      files: this.files().map((file) => ({
        path: file.path.trim(),
        kind: file.kind,
        content: file.content,
      })),
    });
  }

  private patchFile(index: number, patch: Partial<GovernanceFileDetail>): void {
    this.files.update((files) =>
      files.map((file, position) => (position === index ? { ...file, ...patch } : file)),
    );
  }
}
