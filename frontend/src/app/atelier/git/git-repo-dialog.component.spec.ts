import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { GitRepoDialogComponent, PickedGitRepository } from './git-repo-dialog.component';

/**
 * Vérifie le dialogue d'ouverture d'un dépôt (F-31 / SF-31-02) : la validation d'URL anticipe celle
 * du backend (coller une URL de page GitHub est l'erreur la plus probable), et les champs laissés
 * vides valent « valeur par défaut » plutôt que chaîne vide.
 */
describe('GitRepoDialogComponent', () => {
  let fixture: ComponentFixture<GitRepoDialogComponent>;
  let component: GitRepoDialogComponent;
  let closed: PickedGitRepository | undefined | 'not-closed';

  beforeEach(async () => {
    closed = 'not-closed';
    await TestBed.configureTestingModule({
      imports: [GitRepoDialogComponent, NoopAnimationsModule],
      providers: [
        {
          provide: MatDialogRef,
          useValue: { close: (value?: PickedGitRepository) => (closed = value) },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(GitRepoDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('accepte une URL de dépôt GitHub', () => {
    component.repoUrl.set('https://github.com/octocat/hello');

    expect(component.urlValid()).toBeTrue();
    expect(component.urlError()).toBeNull();
  });

  it("refuse une URL de page GitHub et l'explique", () => {
    component.repoUrl.set('https://github.com/octocat/hello/tree/main');

    expect(component.urlValid()).toBeFalse();
    expect(component.urlError()).toContain('https://github.com/proprietaire/depot');
  });

  it('ne signale rien tant que rien n\'est saisi', () => {
    expect(component.urlError()).toBeNull();
    expect(component.urlValid()).toBeFalse();
  });

  it('ne ferme pas le dialogue sur une URL invalide', () => {
    component.repoUrl.set('https://gitlab.com/octocat/hello');

    component.confirm();

    expect(closed).toBe('not-closed');
  });

  it('renvoie le dépôt, et omet branche et nom laissés vides', () => {
    component.repoUrl.set('  https://github.com/octocat/hello  ');
    component.branch.set('   ');
    component.name.set('');

    component.confirm();

    expect(closed).toEqual({
      repoUrl: 'https://github.com/octocat/hello',
      branch: undefined,
      name: undefined,
    });
  });

  it('renvoie la branche et le nom quand ils sont fournis', () => {
    component.repoUrl.set('https://github.com/octocat/hello');
    component.branch.set(' feat/atelier ');
    component.name.set(' Mon projet ');

    component.confirm();

    expect(closed).toEqual({
      repoUrl: 'https://github.com/octocat/hello',
      branch: 'feat/atelier',
      name: 'Mon projet',
    });
  });

  it('annuler ferme sans rien renvoyer', () => {
    component.cancel();

    expect(closed).toBeUndefined();
  });
});
