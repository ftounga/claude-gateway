import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

import {
  DepositPreviewData,
  DepositPreviewDialogComponent,
} from './deposit-preview-dialog.component';
import { GovernanceDepositPlan } from '../../core/models/governance.models';

/** L'annonce faite avant l'écriture (F-51 / SF-51-05) : ce qu'elle dit, et ce qu'elle ne cache pas. */
describe('DepositPreviewDialogComponent', () => {
  let fixture: ComponentFixture<DepositPreviewDialogComponent>;
  let component: DepositPreviewDialogComponent;
  const dialogRef = jasmine.createSpyObj<MatDialogRef<DepositPreviewDialogComponent, boolean>>(
    'MatDialogRef',
    ['close'],
  );

  const plan: GovernanceDepositPlan = {
    packageId: 'p1',
    slug: 'livrables',
    version: 1,
    readable: true,
    entries: [
      { path: 'STATE.md', kind: 'TEMPLATE', action: 'KEEP' },
      { path: '.claude/skills/explique.md', kind: 'SKILL', action: 'CREATE' },
    ],
    rules: true,
    controls: 2,
  };

  async function build(data: DepositPreviewData): Promise<void> {
    await TestBed.resetTestingModule()
      .configureTestingModule({
        imports: [DepositPreviewDialogComponent],
        providers: [
          provideNoopAnimations(),
          { provide: MAT_DIALOG_DATA, useValue: data },
          { provide: MatDialogRef, useValue: dialogRef },
        ],
      })
      .compileComponents();
    fixture = TestBed.createComponent(DepositPreviewDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('dit le chemin exact et le sort de chaque fichier', async () => {
    await build({ packageName: 'Livrables', projectName: 'web', plan });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('STATE.md');
    expect(text).toContain('déjà présent');
    expect(text).toContain('.claude/skills/explique.md');
    expect(text).toContain('sera créé');
    expect(component.createdCount).toBe(1);
    expect(component.keptCount).toBe(1);
  });

  it("dit que le projet n'a pas pu être lu plutôt que de promettre des créations", async () => {
    await build({
      packageName: 'Livrables',
      projectName: 'web',
      plan: {
        ...plan,
        readable: false,
        entries: [{ path: 'STATE.md', kind: 'TEMPLATE', action: 'UNKNOWN' }],
      },
    });
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain("n'a pas pu être lu");
    expect(text).toContain('indéterminé');
  });

  it("annonce qu'un paquet sans fichier n'écrit rien", async () => {
    await build({
      packageName: 'Règles seules',
      projectName: 'web',
      plan: { ...plan, entries: [] },
    });

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('ne dépose aucun fichier');
  });

  it('ferme en confirmant ou en annulant', async () => {
    await build({ packageName: 'Livrables', projectName: 'web', plan });

    component.confirm();
    expect(dialogRef.close).toHaveBeenCalledWith(true);

    component.cancel();
    expect(dialogRef.close).toHaveBeenCalledWith(false);
  });
});
