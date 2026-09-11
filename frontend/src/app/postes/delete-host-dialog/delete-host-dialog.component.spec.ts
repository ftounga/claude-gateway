import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import {
  DeleteHostDialogComponent,
  DeleteHostDialogData,
} from './delete-host-dialog.component';

/**
 * Le dialogue de suppression d'un poste (F-69 / SF-69-02) — qui est aussi, et d'abord, un
 * <b>dialogue de refus</b>.
 *
 * <p>Décision du PO : pas de cascade. Tant que des projets vivent sous le poste, il n'y a rien à
 * confirmer — il y a à expliquer, avec un compte et une direction.</p>
 */
describe('DeleteHostDialogComponent', () => {
  let fixture: ComponentFixture<DeleteHostDialogComponent>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<DeleteHostDialogComponent, boolean>>;

  function setup(data: DeleteHostDialogData): void {
    dialogRef = jasmine.createSpyObj<MatDialogRef<DeleteHostDialogComponent, boolean>>(
      'MatDialogRef', ['close']);
    TestBed.configureTestingModule({
      imports: [DeleteHostDialogComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRef },
        provideNoopAnimations(),
      ],
    });
    fixture = TestBed.createComponent(DeleteHostDialogComponent);
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function buttons(): HTMLButtonElement[] {
    return Array.from((fixture.nativeElement as HTMLElement)
      .querySelectorAll('mat-dialog-actions button'));
  }

  // ------------------------------------------------------------------ le refus

  it('refuse, et dit combien de projets restent', () => {
    setup({ hostName: 'Poste CAGIP', remainingProjects: 3 });

    expect(text()).toContain('3 projets');
    expect(text()).toContain('Poste CAGIP');
  });

  it('accorde le compte au singulier', () => {
    setup({ hostName: 'Poste CAGIP', remainingProjects: 1 });

    expect(text()).toContain('1 projet');
    expect(text()).toContain('Il est listé');
  });

  it('dit où les trouver', () => {
    setup({ hostName: 'Poste CAGIP', remainingProjects: 2 });

    expect(text()).toContain('Ils sont listés sur la carte');
    expect(text()).toContain('Forge');
  });

  it('rassure sur la machine, même dans le refus', () => {
    setup({ hostName: 'Poste CAGIP', remainingProjects: 2 });

    expect(text()).toContain("Rien n'est effacé sur votre machine");
  });

  it("n'offre aucun bouton de suppression quand il refuse", () => {
    setup({ hostName: 'Poste CAGIP', remainingProjects: 2 });

    expect(buttons().length).toBe(1);
    expect(buttons()[0].textContent).toContain('Fermer');
  });

  it('ne ferme jamais sur true quand il refuse, même appelé de force', () => {
    setup({ hostName: 'Poste CAGIP', remainingProjects: 2 });

    fixture.componentInstance.confirm();

    expect(dialogRef.close).not.toHaveBeenCalled();
  });

  // ------------------------------------------------------------------ la confirmation

  it('confirme quand le poste est vide, en disant ce qui part et ce qui reste', () => {
    setup({ hostName: 'Poste rendu', remainingProjects: 0 });

    expect(text()).toContain("L'appairage de cette machine");
    expect(text()).toContain('Votre machine et ses fichiers');
    expect(buttons().length).toBe(2);
  });

  it('ferme sur true à la confirmation', () => {
    setup({ hostName: 'Poste rendu', remainingProjects: 0 });

    buttons()[1].click();

    expect(dialogRef.close).toHaveBeenCalledWith(true);
  });

  it('emploie le bouton destructif de la charte', () => {
    setup({ hostName: 'Poste rendu', remainingProjects: 0 });

    // DESIGN_SYSTEM.md §5 : action destructive = mat-flat-button color="warn".
    expect(buttons()[1].classList).toContain('mat-mdc-unelevated-button');
    expect(buttons()[1].classList).toContain('mat-warn');
  });
});
