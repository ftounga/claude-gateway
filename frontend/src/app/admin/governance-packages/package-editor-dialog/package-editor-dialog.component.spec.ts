import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

import {
  PackageEditorData,
  PackageEditorDialogComponent,
} from './package-editor-dialog.component';
import { GovernancePackageAdmin } from '../../governance-admin.models';

/** Le formulaire de rédaction d'un paquet (F-51 / SF-51-06). */
describe('PackageEditorDialogComponent', () => {
  let fixture: ComponentFixture<PackageEditorDialogComponent>;
  let component: PackageEditorDialogComponent;
  let dialogRef: jasmine.SpyObj<MatDialogRef<PackageEditorDialogComponent, unknown>>;

  const existing: GovernancePackageAdmin = {
    id: 'p1',
    slug: 'livrables',
    name: 'Livrables sans trace',
    summary: 'La règle des livrables.',
    rules: 'Aucune trace de LLM.',
    controls: [{ id: 'commit-propre', kind: 'END_OF_TURN', description: 'Vérifie.', known: true }],
    files: [{ path: 'STATE.md', kind: 'TEMPLATE', content: '# État' }],
    version: 3,
    published: false,
    publishedAt: null,
    updatedAt: new Date().toISOString(),
  };

  async function build(data: PackageEditorData): Promise<void> {
    dialogRef = jasmine.createSpyObj<MatDialogRef<PackageEditorDialogComponent, unknown>>(
      'MatDialogRef',
      ['close'],
    );
    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [PackageEditorDialogComponent],
        providers: [
          provideNoopAnimations(),
          { provide: MAT_DIALOG_DATA, useValue: data },
          { provide: MatDialogRef, useValue: dialogRef },
        ],
      })
      .compileComponents();
    fixture = TestBed.createComponent(PackageEditorDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('pré-remplit le formulaire en modification, et verrouille le slug', async () => {
    await build({ pkg: existing, controls: existing.controls });

    expect(component.editing).toBeTrue();
    expect(component.slug()).toBe('livrables');
    expect(component.controlIds()).toEqual(['commit-propre']);
    const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input');
    expect(input?.disabled).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'remplace intégralement',
    );
  });

  it('part vide en création', async () => {
    await build({ pkg: null, controls: [] });

    expect(component.editing).toBeFalse();
    expect(component.slug()).toBe('');
    expect(component.files().length).toBe(0);
  });

  it('ajoute et retire un fichier sans perdre les autres', async () => {
    await build({ pkg: existing, controls: [] });

    component.addFile();
    component.setFilePath(1, '.claude/skills/explique.md');
    component.setFileKind(1, 'SKILL');
    component.setFileContent(1, '# explique');
    expect(component.files().length).toBe(2);

    component.removeFile(0);
    expect(component.files()).toEqual([
      { path: '.claude/skills/explique.md', kind: 'SKILL', content: '# explique' },
    ]);
  });

  it('rend le contenu saisi, en normalisant les champs vides', async () => {
    await build({ pkg: null, controls: [] });
    component.slug.set('  nouveau  ');
    component.name.set(' Nouveau ');
    component.summary.set('   ');
    component.rules.set('Une règle.');

    component.save();

    expect(dialogRef.close).toHaveBeenCalledWith({
      slug: 'nouveau',
      name: 'Nouveau',
      summary: null,
      rules: 'Une règle.',
      controlIds: [],
      files: [],
    });
  });

  it('ferme sans rien rendre à l’annulation', async () => {
    await build({ pkg: null, controls: [] });

    component.cancel();

    expect(dialogRef.close).toHaveBeenCalledWith();
  });

  it("dit qu'aucun contrôle n'est fourni quand la liste est vide", async () => {
    await build({ pkg: null, controls: [] });

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      "Aucun contrôle n'est fourni",
    );
  });
});
