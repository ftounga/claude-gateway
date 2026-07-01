import { DatePipe } from '@angular/common';
import { AfterViewInit, Component, OnInit, ViewChild, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import {
  FormBuilder,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatPaginator, MatPaginatorModule } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTableDataSource, MatTableModule } from '@angular/material/table';

import { TemplatesService } from '../core/services/templates.service';
import {
  ConfirmDialogComponent,
  ConfirmDialogData,
} from '../chat/confirm-dialog/confirm-dialog.component';
import { TemplateCategory, TemplateResponse } from '../core/models/template.models';

/** Libellé FR + classe de badge (design system) d'une catégorie. */
interface CategoryDisplay {
  label: string;
  badgeClass: string;
}

const CATEGORY_DISPLAY: Record<TemplateCategory, CategoryDisplay> = {
  AUDIT: { label: 'Audit', badgeClass: 'badge--info' },
  REPORT: { label: 'Rapport', badgeClass: 'badge--success' },
  OTHER: { label: 'Autre', badgeClass: 'badge--neutral' },
};

/** Options du sélecteur de catégorie. */
const CATEGORY_OPTIONS: { value: TemplateCategory; label: string }[] = [
  { value: 'AUDIT', label: 'Audit' },
  { value: 'REPORT', label: 'Rapport' },
  { value: 'OTHER', label: 'Autre' },
];

/**
 * Écran des modèles de prompts réutilisables (F-13) : liste, création/édition (formulaire outline),
 * copie du contenu dans le presse-papier (réutilisation dans le chat) et suppression confirmée.
 * Ne parle qu'à Claude Gateway (`/api/templates`) ; l'isolation est garantie côté backend via le JWT.
 */
@Component({
  selector: 'app-templates',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatTableModule,
    MatPaginatorModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatIconModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './templates.component.html',
  styleUrl: './templates.component.scss',
})
export class TemplatesComponent implements OnInit, AfterViewInit {
  private readonly templatesService = inject(TemplatesService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly fb = inject(FormBuilder);

  readonly displayedColumns = ['name', 'category', 'updatedAt', 'actions'];
  readonly dataSource = new MatTableDataSource<TemplateResponse>([]);
  readonly categoryOptions = CATEGORY_OPTIONS;

  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly showForm = signal(false);
  /** Id du modèle en cours d'édition ; `null` en mode création. */
  readonly editingId = signal<string | null>(null);

  readonly form: FormGroup = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(120)]],
    category: ['OTHER' as TemplateCategory, Validators.required],
    content: ['', [Validators.required, Validators.maxLength(10000)]],
  });

  @ViewChild(MatPaginator) paginator?: MatPaginator;

  ngOnInit(): void {
    this.refresh();
  }

  ngAfterViewInit(): void {
    if (this.paginator) {
      this.dataSource.paginator = this.paginator;
    }
  }

  refresh(): void {
    this.loading.set(true);
    this.templatesService.list().subscribe({
      next: (templates) => {
        this.dataSource.data = templates;
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.notify('Impossible de charger vos modèles.', 'snack-error');
      },
    });
  }

  startCreate(): void {
    this.editingId.set(null);
    this.form.reset({ name: '', category: 'OTHER', content: '' });
    this.showForm.set(true);
  }

  startEdit(template: TemplateResponse): void {
    this.editingId.set(template.id);
    this.form.reset({
      name: template.name,
      category: template.category,
      content: template.content,
    });
    this.showForm.set(true);
  }

  cancelForm(): void {
    this.showForm.set(false);
    this.editingId.set(null);
  }

  save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    const body = this.form.getRawValue();
    const id = this.editingId();
    const request$ = id
      ? this.templatesService.update(id, body)
      : this.templatesService.create(body);

    request$.subscribe({
      next: () => {
        this.saving.set(false);
        this.showForm.set(false);
        this.editingId.set(null);
        this.notify(id ? 'Modèle mis à jour.' : 'Modèle créé.', 'snack-success');
        this.refresh();
      },
      error: (error: HttpErrorResponse) => {
        this.saving.set(false);
        const message =
          error.status === 400
            ? 'Vérifiez les champs du modèle.'
            : 'Impossible d’enregistrer le modèle.';
        this.notify(message, 'snack-error');
      },
    });
  }

  /** Copie le contenu du modèle dans le presse-papier pour le réutiliser dans le chat. */
  copy(template: TemplateResponse): void {
    const clipboard = navigator.clipboard;
    if (!clipboard || typeof clipboard.writeText !== 'function') {
      this.notify('Copie impossible sur ce navigateur.', 'snack-error');
      return;
    }
    clipboard.writeText(template.content).then(
      () => this.notify('Modèle copié dans le presse-papier.', 'snack-success'),
      () => this.notify('Copie impossible.', 'snack-error'),
    );
  }

  remove(template: TemplateResponse): void {
    const data: ConfirmDialogData = {
      title: 'Supprimer le modèle',
      message: `Supprimer définitivement « ${template.name} » ? Cette action est irréversible.`,
      confirmLabel: 'Supprimer',
    };
    this.dialog
      .open(ConfirmDialogComponent, { data, width: '420px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed) {
          this.performDelete(template);
        }
      });
  }

  private performDelete(template: TemplateResponse): void {
    this.templatesService.delete(template.id).subscribe({
      next: () => {
        if (this.editingId() === template.id) {
          this.cancelForm();
        }
        this.notify('Modèle supprimé.', 'snack-success');
        this.refresh();
      },
      error: () => this.notify('Impossible de supprimer le modèle.', 'snack-error'),
    });
  }

  categoryDisplay(category: TemplateCategory): CategoryDisplay {
    return CATEGORY_DISPLAY[category];
  }

  private notify(message: string, panelClass: string): void {
    this.snackBar.open(message, 'Fermer', { duration: 5000, panelClass: [panelClass] });
  }
}
