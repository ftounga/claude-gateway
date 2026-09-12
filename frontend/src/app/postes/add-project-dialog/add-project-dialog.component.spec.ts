import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { HostFoldersResponse, WorkspaceDetail } from '../../core/models/atelier.models';
import { AtelierService } from '../../core/services/atelier.service';
import { AddProjectDialogComponent } from './add-project-dialog.component';

/**
 * **Ajouter un projet à un poste** (F-72 / SF-72-03) : l'explorateur liste, on **clique**, le
 * projet existe — sans nom à saisir et sans réappairer.
 */
describe('AddProjectDialogComponent (F-72 SF-72-03)', () => {
  let fixture: ComponentFixture<AddProjectDialogComponent>;
  let component: AddProjectDialogComponent;
  let service: jasmine.SpyObj<AtelierService>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<AddProjectDialogComponent, boolean>>;

  const root: HostFoldersResponse = {
    path: '',
    parentPath: null,
    folders: [
      { name: 'EDENRED', path: 'EDENRED', used: false },
      { name: 'CAGIP', path: 'CAGIP', used: true },
    ],
    truncated: false,
  };

  const created = { id: 'w9', name: 'EDENRED' } as WorkspaceDetail;

  function setup(first: HostFoldersResponse = root): void {
    service = jasmine.createSpyObj<AtelierService>('AtelierService',
      ['runnerHostFolders', 'openHostProject']);
    service.runnerHostFolders.and.returnValue(of(first));
    service.openHostProject.and.returnValue(of(created));
    dialogRef = jasmine.createSpyObj<MatDialogRef<AddProjectDialogComponent, boolean>>(
      'MatDialogRef', ['close']);

    TestBed.configureTestingModule({
      imports: [AddProjectDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: AtelierService, useValue: service },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: { hostId: 'h1', hostName: 'EDENRED' } },
      ],
    });

    fixture = TestBed.createComponent(AddProjectDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  afterEach(() => TestBed.resetTestingModule());

  it('liste les dossiers de la racine dès son ouverture', () => {
    setup();

    expect(service.runnerHostFolders).toHaveBeenCalledWith('h1', undefined);
    expect(text()).toContain('EDENRED');
    expect(text()).toContain('CAGIP');
  });

  it("n'offre AUCUN champ de saisie de chemin", () => {
    setup();

    // Un chemin tapé crée un projet vide qui n'échoue qu'au PREMIER USAGE, quand plus personne ne
    // fait le lien avec la faute de frappe.
    expect((fixture.nativeElement as HTMLElement).querySelector('input')).toBeNull();
  });

  it('ouvre un projet sur le dossier cliqué, sans demander de nom', () => {
    setup();

    component.openFolder('EDENRED');

    expect(service.openHostProject).toHaveBeenCalledWith('h1', 'EDENRED');
  });

  it('ouvre la racine comme un choix légitime', () => {
    setup();

    component.openFolder('');

    // Un poste peut n'héberger qu'un projet : la racine est un choix, pas un oubli.
    expect(service.openHostProject).toHaveBeenCalledWith('h1', '');
  });

  it("reste ouvert après un ajout et relit la liste", () => {
    setup();
    service.runnerHostFolders.calls.reset();

    component.openFolder('EDENRED');
    fixture.detectChanges();

    // « Autant de fois qu'on veut » est la promesse de F-48 : refermer obligerait à rouvrir,
    // re-lister, re-descendre.
    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(service.runnerHostFolders).toHaveBeenCalledTimes(1);
    expect(text()).toContain('EDENRED');
  });

  it('descend dans un dossier puis remonte', () => {
    setup();
    service.runnerHostFolders.and.returnValue(of({
      path: 'EDENRED', parentPath: '', folders: [], truncated: false,
    }));

    component.enterFolder({ name: 'EDENRED', path: 'EDENRED', used: false });

    expect(service.runnerHostFolders).toHaveBeenCalledWith('h1', 'EDENRED');
    expect(component.parentPath()).toBe('');

    service.runnerHostFolders.and.returnValue(of(root));
    component.goUp();

    expect(component.browsePath()).toBe('');
  });

  it('marque un dossier déjà ouvert et le rend inerte', () => {
    setup();
    const buttons = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.add-project__pick'),
    ) as HTMLButtonElement[];
    const cagip = buttons.find((b) => (b.textContent ?? '').includes('CAGIP'));

    expect(cagip?.disabled).toBeTrue();
    expect(text()).toContain('déjà ouvert');
  });

  it("dit que le runner n'est pas connecté, et n'offre rien à remplir", () => {
    setup();
    service.runnerHostFolders.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409 })),
    );

    component.retry();
    fixture.detectChanges();

    expect(component.browseError()).toBe('offline');
    expect(text()).toContain("n'est pas connecté");
    expect((fixture.nativeElement as HTMLElement).querySelector('input')).toBeNull();
  });

  it("reprend le refus de doublon de la gateway et relit la liste", () => {
    setup();
    service.openHostProject.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409,
      error: { error: 'host_project_exists', message: 'Ce dossier est déjà ouvert : EDENRED.' },
    })));
    service.runnerHostFolders.calls.reset();

    component.openFolder('EDENRED');
    fixture.detectChanges();

    expect(text()).toContain('Ce dossier est déjà ouvert');
    // L'écran était en retard : on relit plutôt que de le laisser mentir.
    expect(service.runnerHostFolders).toHaveBeenCalledTimes(1);
  });

  it('dit quand la liste est incomplète', () => {
    setup({ ...root, truncated: true });

    expect(text()).toContain('Liste incomplète');
  });

  it("dit qu'il n'y a aucun sous-dossier, sans en faire une erreur", () => {
    setup({ path: '', parentPath: null, folders: [], truncated: false });

    expect(text()).toContain('Aucun sous-dossier ici');
    expect(component.browseError()).toBe('none');
  });

  it('annonce à la fermeture si quelque chose a été ouvert', () => {
    setup();

    component.close();
    expect(dialogRef.close).toHaveBeenCalledWith(false);

    component.openFolder('EDENRED');
    component.close();
    expect(dialogRef.close).toHaveBeenCalledWith(true);
  });
});
