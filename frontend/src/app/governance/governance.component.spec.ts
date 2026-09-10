import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { GovernanceComponent } from './governance.component';
import { GovernanceService } from '../core/services/governance.service';
import { AtelierService } from '../core/services/atelier.service';
import {
  GovernanceDepositPlan,
  GovernancePackage,
  GovernanceProject,
  GovernanceSelection,
} from '../core/models/governance.models';
import { WorkspaceSummary } from '../core/models/atelier.models';
import { DepositPreviewData } from './deposit-preview-dialog/deposit-preview-dialog.component';

/**
 * L'écran de gouvernance (F-51 / SF-51-05).
 *
 * Ce que ces tests protègent avant tout : **aucune activation n'est envoyée tant que l'annonce n'a
 * pas été confirmée**. C'est l'exigence centrale de la feature — un paquet écrit sur la machine de
 * l'utilisateur.
 */
describe('GovernanceComponent', () => {
  let fixture: ComponentFixture<GovernanceComponent>;
  let component: GovernanceComponent;
  let governance: jasmine.SpyObj<GovernanceService>;
  let atelier: jasmine.SpyObj<AtelierService>;
  let dialog: jasmine.SpyObj<MatDialog>;

  const pkg: GovernancePackage = {
    id: 'p1',
    slug: 'livrables',
    name: 'Livrables sans trace',
    summary: 'La règle des livrables.',
    version: 2,
    rules: 'Aucun livrable ne doit suggérer un LLM.',
    controls: [],
    files: [
      { path: 'STATE.md', kind: 'TEMPLATE' },
      { path: '.claude/skills/explique.md', kind: 'SKILL' },
    ],
  };

  const selection: GovernanceSelection[] = [
    { pkg, defaultApplied: false, activeProjects: 0 },
  ];

  const workspaces: WorkspaceSummary[] = [
    {
      id: 'w1',
      name: 'web',
      createdAt: new Date().toISOString(),
      source: 'ARCHIVE',
      gitRepo: null,
    } as WorkspaceSummary,
  ];

  const emptyProject: GovernanceProject = { workspaceId: 'w1', active: [], available: [pkg] };

  const plan: GovernanceDepositPlan = {
    packageId: 'p1',
    slug: 'livrables',
    version: 2,
    readable: true,
    entries: [
      { path: 'STATE.md', kind: 'TEMPLATE', action: 'KEEP' },
      { path: '.claude/skills/explique.md', kind: 'SKILL', action: 'CREATE' },
    ],
    rules: true,
    controls: 0,
  };

  function dialogClosing(result: boolean): void {
    dialog.open.and.returnValue({
      afterClosed: () => of(result),
    } as MatDialogRef<unknown, boolean>);
  }

  beforeEach(async () => {
    governance = jasmine.createSpyObj<GovernanceService>('GovernanceService', [
      'getCatalog',
      'getSelection',
      'getProject',
      'select',
      'deselect',
      'preview',
      'activate',
      'apply',
      'deactivate',
    ]);
    atelier = jasmine.createSpyObj<AtelierService>('AtelierService', ['listWorkspaces']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    governance.getCatalog.and.returnValue(of([pkg]));
    governance.getSelection.and.returnValue(of(selection));
    governance.getProject.and.returnValue(of(emptyProject));
    governance.preview.and.returnValue(of(plan));
    governance.activate.and.returnValue(of(emptyProject));
    governance.select.and.returnValue(of(selection));
    governance.deselect.and.returnValue(of(void 0));
    governance.deactivate.and.returnValue(of(void 0));
    governance.apply.and.returnValue(of(plan));
    atelier.listWorkspaces.and.returnValue(of(workspaces));

    await TestBed.configureTestingModule({
      imports: [GovernanceComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: GovernanceService, useValue: governance },
        { provide: AtelierService, useValue: atelier },
        { provide: MatDialog, useValue: dialog },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(GovernanceComponent);
    component = fixture.componentInstance;
  });

  it('charge le catalogue, la sélection et les projets, et choisit le premier projet', () => {
    fixture.detectChanges();

    expect(component.catalog().length).toBe(1);
    expect(component.selection().length).toBe(1);
    expect(component.selectedWorkspaceId()).toBe('w1');
    expect(governance.getProject).toHaveBeenCalledWith('w1');
  });

  it('affiche les chemins que le paquet déposerait', () => {
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('STATE.md');
    expect(text).toContain('.claude/skills/explique.md');
  });

  it("n'active RIEN tant que l'annonce n'est pas confirmée", () => {
    fixture.detectChanges();
    dialogClosing(false);

    component.activate(pkg);

    expect(governance.preview).toHaveBeenCalledWith('w1', 'p1');
    expect(dialog.open).toHaveBeenCalled();
    expect(governance.activate).not.toHaveBeenCalled();
  });

  it("active une fois l'annonce confirmée, et lui passe le plan rendu par l'aperçu", () => {
    fixture.detectChanges();
    dialogClosing(true);

    component.activate(pkg);

    const data = dialog.open.calls.mostRecent().args[1]?.data as DepositPreviewData;
    expect(data.plan).toBe(plan);
    expect(data.projectName).toBe('web');
    expect(governance.activate).toHaveBeenCalledWith('w1', 'p1');
  });

  it("n'active pas en aveugle quand l'aperçu échoue", () => {
    fixture.detectChanges();
    governance.preview.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })),
    );

    component.activate(pkg);

    expect(dialog.open).not.toHaveBeenCalled();
    expect(governance.activate).not.toHaveBeenCalled();
  });

  it("laisse activer quand le projet n'a pas pu être lu — les règles s'appliquent sans disque", () => {
    fixture.detectChanges();
    governance.preview.and.returnValue(of({ ...plan, readable: false }));
    dialogClosing(true);

    component.activate(pkg);

    const data = dialog.open.calls.mostRecent().args[1]?.data as DepositPreviewData;
    expect(data.plan.readable).toBeFalse();
    expect(governance.activate).toHaveBeenCalled();
  });

  it('retient un paquet, et met la sélection à jour', () => {
    fixture.detectChanges();

    component.retain(pkg);

    expect(governance.select).toHaveBeenCalledWith('p1', false);
    expect(component.isRetained(pkg)).toBeTrue();
  });

  it('envoie le drapeau « appliqué par défaut »', () => {
    fixture.detectChanges();

    component.toggleDefault(pkg, true);

    expect(governance.select).toHaveBeenCalledWith('p1', true);
  });

  it('ne désactive qu’après confirmation', () => {
    fixture.detectChanges();
    dialogClosing(false);

    component.deactivate({
      pkg,
      appliedVersion: 2,
      outdated: false,
      status: 'APPLIED',
      appliedAt: null,
    });

    expect(governance.deactivate).not.toHaveBeenCalled();

    dialogClosing(true);
    component.deactivate({
      pkg,
      appliedVersion: 2,
      outdated: false,
      status: 'APPLIED',
      appliedAt: null,
    });

    expect(governance.deactivate).toHaveBeenCalledWith('w1', 'p1');
  });

  it('un 403 affiche le bandeau Forge et arrête là', () => {
    governance.getCatalog.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 403 })),
    );

    fixture.detectChanges();

    expect(component.error()).toBe('forbidden');
    expect(component.loading()).toBeFalse();
    expect(governance.getProject).not.toHaveBeenCalled();
  });

  it('une panne réseau propose de réessayer', () => {
    governance.getCatalog.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 0 })),
    );

    fixture.detectChanges();

    expect(component.error()).toBe('network');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Réessayer');
  });

  it("aucune URL appelée ne porte d'identifiant d'utilisateur : l'isolation vient du JWT", () => {
    fixture.detectChanges();
    dialogClosing(true);
    component.activate(pkg);

    expect(governance.preview).toHaveBeenCalledWith('w1', 'p1');
    expect(governance.activate).toHaveBeenCalledWith('w1', 'p1');
    expect(governance.getSelection).toHaveBeenCalledWith();
  });
});
