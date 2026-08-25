import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { GitPushDialogComponent, PickedGitPush } from './git-push-dialog.component';

/**
 * Vérifie le dialogue de publication (F-31 / SF-31-04) : la branche de base est refusée ici comme
 * côté serveur — le découvrir après avoir consommé un tour d'exécution serait coûteux et frustrant.
 */
describe('GitPushDialogComponent', () => {
  let fixture: ComponentFixture<GitPushDialogComponent>;
  let component: GitPushDialogComponent;
  let closed: PickedGitPush | undefined | 'not-closed';

  beforeEach(async () => {
    closed = 'not-closed';
    await TestBed.configureTestingModule({
      imports: [GitPushDialogComponent, NoopAnimationsModule],
      providers: [
        {
          provide: MatDialogRef,
          useValue: { close: (value?: PickedGitPush) => (closed = value) },
        },
        {
          provide: MAT_DIALOG_DATA,
          useValue: {
            gitRepo: 'octocat/hello',
            baseBranch: 'main',
            suggestedBranch: 'claude/atelier-20260825-0900',
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(GitPushDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('propose une branche dédiée par défaut', () => {
    expect(component.branch()).toBe('claude/atelier-20260825-0900');
    expect(component.valid()).toBeTrue();
  });

  it('refuse la branche de base du dépôt', () => {
    component.branch.set('main');

    expect(component.valid()).toBeFalse();
    expect(component.branchError()).toContain('main');
  });

  it('refuse un nom de branche invalide', () => {
    component.branch.set('../evil');
    expect(component.valid()).toBeFalse();

    component.branch.set('-force');
    expect(component.valid()).toBeFalse();

    component.branch.set('');
    expect(component.valid()).toBeFalse();
  });

  it('ne ferme pas le dialogue sur une saisie invalide', () => {
    component.branch.set('main');

    component.confirm();

    expect(closed).toBe('not-closed');
  });

  it('renvoie la branche, et omet un message vide', () => {
    component.branch.set(' feat/atelier ');
    component.message.set('   ');

    component.confirm();

    expect(closed).toEqual({ branch: 'feat/atelier', message: undefined });
  });

  it('renvoie le message de commit quand il est fourni', () => {
    component.message.set(' Corrige le bug ');

    component.confirm();

    expect(closed).toEqual({
      branch: 'claude/atelier-20260825-0900',
      message: 'Corrige le bug',
    });
  });
});
