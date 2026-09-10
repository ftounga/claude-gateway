import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { GovernancePackagesComponent } from './governance-packages.component';
import { GovernanceAdminService } from '../governance-admin.service';
import { GovernancePackageAdmin, GovernancePackageDraft } from '../governance-admin.models';

/**
 * La section d'administration du catalogue (F-51 / SF-51-06).
 *
 * Ce que ces tests protègent : on ne propose jamais un bouton qui échouera (supprimer un paquet
 * publié), et le message d'erreur du backend est affiché **tel quel** — il nomme déjà le champ fautif.
 */
describe('GovernancePackagesComponent', () => {
  let fixture: ComponentFixture<GovernancePackagesComponent>;
  let component: GovernancePackagesComponent;
  let service: jasmine.SpyObj<GovernanceAdminService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const draft: GovernancePackageAdmin = {
    id: 'p1',
    slug: 'livrables',
    name: 'Livrables sans trace',
    summary: null,
    rules: 'Aucune trace de LLM.',
    controls: [],
    files: [{ path: 'STATE.md', kind: 'TEMPLATE', content: '# État' }],
    version: 1,
    published: false,
    publishedAt: null,
    updatedAt: new Date().toISOString(),
  };

  const published: GovernancePackageAdmin = { ...draft, id: 'p2', slug: 'publie', published: true };

  function dialogReturning(result: unknown): void {
    dialog.open.and.returnValue({
      afterClosed: () => of(result),
    } as MatDialogRef<unknown, unknown>);
  }

  beforeEach(async () => {
    service = jasmine.createSpyObj<GovernanceAdminService>('GovernanceAdminService', [
      'list',
      'controls',
      'create',
      'update',
      'publish',
      'unpublish',
      'remove',
    ]);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    service.list.and.returnValue(of([draft, published]));
    service.controls.and.returnValue(of([]));
    service.create.and.returnValue(of(draft));
    service.update.and.returnValue(of(draft));
    service.publish.and.returnValue(of({ ...draft, published: true }));
    service.unpublish.and.returnValue(of(draft));
    service.remove.and.returnValue(of(void 0));

    await TestBed.configureTestingModule({
      imports: [GovernancePackagesComponent],
      providers: [
        provideNoopAnimations(),
        { provide: GovernanceAdminService, useValue: service },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(GovernancePackagesComponent);
    component = fixture.componentInstance;
  });

  it('charge les paquets et les contrôles disponibles', () => {
    fixture.detectChanges();

    expect(component.packages().length).toBe(2);
    expect(service.controls).toHaveBeenCalled();
  });

  it('résume ce que chaque paquet apporte', () => {
    fixture.detectChanges();

    expect(component.brings(draft)).toContain('des règles');
    expect(component.brings(draft)).toContain('1 fichier(s)');
    expect(component.brings({ ...draft, rules: null, files: [] })).toBe('rien');
  });

  it('crée un paquet avec le contenu saisi', () => {
    fixture.detectChanges();
    const submitted: GovernancePackageDraft = {
      slug: 'nouveau',
      name: 'Nouveau',
      summary: null,
      rules: 'Une règle.',
      controlIds: [],
      files: [],
    };
    dialogReturning(submitted);

    component.create();

    expect(service.create).toHaveBeenCalledWith(submitted);
  });

  it("n'envoie rien si le formulaire est annulé", () => {
    fixture.detectChanges();
    dialogReturning(undefined);

    component.create();

    expect(service.create).not.toHaveBeenCalled();
  });

  it('modifie un paquet existant', () => {
    fixture.detectChanges();
    const submitted: GovernancePackageDraft = {
      slug: 'livrables',
      name: 'Renommé',
      summary: null,
      rules: 'Une règle.',
      controlIds: [],
      files: [],
    };
    dialogReturning(submitted);

    component.edit(draft);

    expect(service.update).toHaveBeenCalledWith('p1', submitted);
  });

  it('publie et dépublie', () => {
    fixture.detectChanges();

    component.publish(draft);
    expect(service.publish).toHaveBeenCalledWith('p1');

    component.unpublish(published);
    expect(service.unpublish).toHaveBeenCalledWith('p2');
  });

  it('ne propose la suppression que sur un brouillon', () => {
    fixture.detectChanges();
    const deleteButtons = (fixture.nativeElement as HTMLElement).querySelectorAll(
      'button[aria-label="Supprimer ce brouillon"]',
    );

    // Deux paquets affichés, un seul bouton de suppression : celui du brouillon.
    expect(component.packages().length).toBe(2);
    expect(deleteButtons.length).toBe(1);
  });

  it('ne supprime qu’après confirmation', () => {
    fixture.detectChanges();
    dialogReturning(false);

    component.remove(draft);
    expect(service.remove).not.toHaveBeenCalled();

    dialogReturning(true);
    component.remove(draft);
    expect(service.remove).toHaveBeenCalledWith('p1');
  });

  it('affiche le message du backend tel quel quand il refuse', () => {
    fixture.detectChanges();
    service.publish.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { error: 'governance_conflict', message: 'Un paquet porte déjà cet identifiant.' },
          }),
      ),
    );

    component.publish(draft);

    expect(snackBar.open).toHaveBeenCalledWith(
      'Un paquet porte déjà cet identifiant.',
      'Fermer',
      jasmine.anything(),
    );
  });

  it('propose de réessayer quand le catalogue ne peut pas être lu', () => {
    service.list.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    fixture.detectChanges();

    expect(component.failed()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Réessayer');
  });
});
