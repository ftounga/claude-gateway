import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import {
  DepositPreviewData,
  DepositPreviewDialogComponent,
} from './deposit-preview-dialog.component';
import { GovernanceService } from '../../core/services/governance.service';
import {
  GovernanceDepositPlan,
  GovernanceFileComparison,
} from '../../core/models/governance.models';

/**
 * L'annonce faite avant l'écriture (F-51 / SF-51-05, complétée par F-75 / SF-75-03).
 *
 * Ce que ces tests protègent : on **voit ce qu'on accepte**. Le chemin, le sort dossier par
 * dossier, et — en cliquant — le **contenu**. Jusqu'ici on approuvait un dépôt de fichiers à
 * l'aveugle, sur la machine d'un client.
 */
describe('DepositPreviewDialogComponent', () => {
  let fixture: ComponentFixture<DepositPreviewDialogComponent>;
  let component: DepositPreviewDialogComponent;
  let governance: jasmine.SpyObj<GovernanceService>;
  const dialogRef = jasmine.createSpyObj<MatDialogRef<DepositPreviewDialogComponent, boolean>>(
    'MatDialogRef',
    ['close'],
  );

  const plan: GovernanceDepositPlan = {
    packageId: 'p1',
    slug: 'livrables',
    version: 1,
    hostRef: 'h1',
    hostName: 'EDENRED',
    files: [
      { path: 'STATE.md', kind: 'TEMPLATE' },
      { path: '.claude/skills/explique.md', kind: 'SKILL' },
    ],
    projects: [
      {
        workspaceId: 'w1',
        name: 'web',
        path: 'web',
        readable: true,
        entries: [
          { path: 'STATE.md', kind: 'TEMPLATE', action: 'KEEP' },
          { path: '.claude/skills/explique.md', kind: 'SKILL', action: 'CREATE' },
        ],
      },
    ],
    rules: true,
    controls: 2,
  };

  const comparison: GovernanceFileComparison = {
    path: 'STATE.md',
    kind: 'TEMPLATE',
    content: '# Gabarit\n',
    truncated: false,
    projects: [
      {
        workspaceId: 'w1',
        name: 'web',
        readable: true,
        exists: true,
        identical: false,
        content: '# Mon état à moi\n',
        truncated: false,
      },
    ],
    omitted: 0,
  };

  async function build(data: DepositPreviewData): Promise<void> {
    governance = jasmine.createSpyObj<GovernanceService>('GovernanceService', ['readFile']);
    governance.readFile.and.returnValue(of(comparison));
    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [DepositPreviewDialogComponent],
        providers: [
          provideNoopAnimations(),
          { provide: MAT_DIALOG_DATA, useValue: data },
          { provide: MatDialogRef, useValue: dialogRef },
          { provide: GovernanceService, useValue: governance },
        ],
      })
      .compileComponents();
    fixture = TestBed.createComponent(DepositPreviewDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function data(overrides: Partial<DepositPreviewData> = {}): DepositPreviewData {
    return {
      packageId: 'p1',
      packageName: 'Livrables',
      hostRef: 'h1',
      hostName: 'EDENRED',
      plan,
      ...overrides,
    };
  }

  it('dit le chemin exact et le sort de chaque fichier, tous dossiers confondus', async () => {
    await build(data());
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('STATE.md');
    expect(text).toContain('déjà présent');
    expect(text).toContain('.claude/skills/explique.md');
    expect(text).toContain('créé dans 1 dossier(s)');
  });

  it('nomme le POSTE sur lequel on active — la confirmation n’est pas anonyme', async () => {
    await build(data());

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Activer sur EDENRED');
  });

  it('ouvre un fichier au clic, et demande son contenu à ce moment-là seulement', async () => {
    await build(data());

    expect(governance.readFile).not.toHaveBeenCalled();

    component.open('STATE.md');

    expect(governance.readFile).toHaveBeenCalledWith('h1', 'p1', 'STATE.md');
    expect(component.opened()).toBe(comparison);
    expect(component.reading()).toBeFalse();
  });

  it('montre le contenu existant : c’est LUI qui sera gardé', async () => {
    await build(data());
    component.open('STATE.md');
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain("n'écrase jamais");
    expect(text).toContain('Mon état à moi');
    expect(text).toContain('Gabarit');
  });

  it('dit qu’un fichier est illisible sans casser le reste de l’annonce', async () => {
    await build(data());
    governance.readFile.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    component.open('STATE.md');
    fixture.detectChanges();

    expect(component.readFailed()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      "n'a pas pu être lu",
    );
  });

  it("dit qu'un dossier n'a pas pu être lu plutôt que de promettre des créations", async () => {
    await build(
      data({
        plan: {
          ...plan,
          projects: [
            { workspaceId: 'w1', name: 'web', path: null, readable: false, entries: [] },
          ],
        },
      }),
    );
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain("n'ont pas pu être lus");
    expect(component.unreadableProjects()).toBe(1);
  });

  it('dit qu’un poste sans dossier hérite quand même demain', async () => {
    await build(data({ plan: { ...plan, projects: [] } }));

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'aucun dossier',
    );
  });

  it("annonce qu'un paquet sans fichier n'écrit rien", async () => {
    await build(data({ plan: { ...plan, files: [] } }));

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('ne dépose aucun fichier');
  });

  it('ferme en confirmant ou en annulant', async () => {
    await build(data());

    component.confirm();
    expect(dialogRef.close).toHaveBeenCalledWith(true);

    component.cancel();
    expect(dialogRef.close).toHaveBeenCalledWith(false);
  });
});
