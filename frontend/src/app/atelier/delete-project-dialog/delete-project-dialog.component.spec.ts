import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import {
  DeleteProjectDialogComponent,
  DeleteProjectDialogData,
} from './delete-project-dialog.component';

/**
 * Le dialogue de suppression d'un projet (F-69 / SF-69-02).
 *
 * <p>Ce qui est testé ici n'est pas de la mécanique, c'est du <b>texte</b> — et c'est délibéré. La
 * décision du PO est que personne ne doive hésiter en cliquant : l'écran doit <b>écrire</b> que le
 * dossier sur la machine n'est pas touché. Un test qui vérifierait seulement que le dialogue
 * s'ouvre laisserait cette phrase disparaître au premier remaniement.</p>
 */
describe('DeleteProjectDialogComponent', () => {
  let fixture: ComponentFixture<DeleteProjectDialogComponent>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<DeleteProjectDialogComponent, boolean>>;

  function setup(data: DeleteProjectDialogData): void {
    dialogRef = jasmine.createSpyObj<MatDialogRef<DeleteProjectDialogComponent, boolean>>(
      'MatDialogRef', ['close']);
    TestBed.configureTestingModule({
      imports: [DeleteProjectDialogComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRef },
        provideNoopAnimations(),
      ],
    });
    fixture = TestBed.createComponent(DeleteProjectDialogComponent);
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('nomme le projet visé', () => {
    setup({ projectName: 'client-edenred' });

    expect(text()).toContain('client-edenred');
  });

  it('écrit noir sur blanc que le dossier de la machine n\'est pas touché', () => {
    setup({ projectName: 'essai' });

    expect(text()).toContain('Le dossier sur votre machine');
    expect(text()).toContain("n'est supprimé, déplacé ni modifié");
  });

  it('nomme le dossier quand on connaît son chemin sous la racine du poste', () => {
    setup({ projectName: 'web', hostName: 'Poste CAGIP', projectPath: 'apps/web' });

    expect(text()).toContain('apps/web');
  });

  it('dit que le poste reste appairé quand le projet est rattaché', () => {
    setup({ projectName: 'web', hostName: 'Poste CAGIP' });

    expect(text()).toContain('Poste CAGIP');
    expect(text()).toContain('reste appairé');
  });

  it('liste ce qui part : conversation, réglages, journal', () => {
    setup({ projectName: 'essai' });

    expect(text()).toContain('conversation');
    expect(text()).toContain('journal');
  });

  it('ferme sur false quand on annule, sur true quand on confirme', () => {
    setup({ projectName: 'essai' });
    const buttons = (fixture.nativeElement as HTMLElement)
      .querySelectorAll('mat-dialog-actions button');

    (buttons[0] as HTMLButtonElement).click();
    expect(dialogRef.close).toHaveBeenCalledWith(false);

    (buttons[1] as HTMLButtonElement).click();
    expect(dialogRef.close).toHaveBeenCalledWith(true);
  });

  it('emploie le bouton destructif de la charte, jamais une couleur maison', () => {
    setup({ projectName: 'essai' });
    const confirm = (fixture.nativeElement as HTMLElement)
      .querySelectorAll('mat-dialog-actions button')[1] as HTMLElement;

    // DESIGN_SYSTEM.md §5 : action destructive = mat-flat-button color="warn".
    expect(confirm.classList).toContain('mat-mdc-unelevated-button');
    expect(confirm.classList).toContain('mat-warn');
  });
});
