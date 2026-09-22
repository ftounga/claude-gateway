import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { Presentation } from '../../core/models/presentation.models';
import { ExportService } from '../../core/services/export.service';
import { PresentationService } from '../../core/services/presentation.service';
import { PresentationsPanelComponent } from './presentations-panel.component';

/** L'onglet Présentations d'un poste ou d'un client (F-129 / SF-129-02). */
describe('PresentationsPanelComponent', () => {
  let fixture: ComponentFixture<PresentationsPanelComponent>;
  let service: jasmine.SpyObj<PresentationService>;
  let files: jasmine.SpyObj<ExportService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let dialogAnswer: unknown;

  const deck = (id: string, title: string): Presentation => ({
    id, title, description: 'Neuf slides', space: 'VIGIE', hostId: 'h1', workspaceId: null,
    pptxBytes: 1_600_000, slideCount: null, createdAt: '2026-09-20T10:00:00Z', updatedAt: '2026-09-20T12:30:00Z',
  });

  function build(list: Presentation[] | 'error' = [deck('d1', 'Onboarding CI/CD')]): HTMLElement {
    service = jasmine.createSpyObj<PresentationService>('PresentationService',
      ['list', 'download', 'delete', 'get', 'slide']);
    service.list.and.returnValue(list === 'error'
      ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(list));
    service.download.and.returnValue(of(new HttpResponse({ body: new Blob(['PK']) })));
    service.delete.and.returnValue(of(void 0));
    files = jasmine.createSpyObj<ExportService>('ExportService', ['triggerDownload']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.callFake((() => ({ afterClosed: () => of(dialogAnswer) })) as never);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [PresentationsPanelComponent],
      providers: [
        provideNoopAnimations(),
        { provide: PresentationService, useValue: service },
        { provide: ExportService, useValue: files },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(PresentationsPanelComponent);
    fixture.componentRef.setInput('hostId', 'h1');
    fixture.componentRef.setInput('space', 'VIGIE');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('une carte par présentation : titre, description, taille', () => {
    const root = build();
    expect(service.list).toHaveBeenCalledOnceWith('h1', 'VIGIE');
    const card = root.querySelector('.decks__card')!;
    expect(card.textContent).toContain('Onboarding CI/CD');
    expect(card.textContent).toContain('Neuf slides');
    expect(card.textContent).toContain('1,5 Mo');
  });

  it('vide : dit comment en obtenir une ; illisible : le dit', () => {
    expect(build([]).textContent).toContain('Aucune présentation ici');
    TestBed.resetTestingModule();
    expect(build('error').textContent).toContain("n'ont pas pu être lues");
  });

  it('Télécharger : passe la réponse à ExportService.triggerDownload', () => {
    build();
    fixture.componentInstance.download(deck('d1', 'Onboarding CI/CD'));
    expect(service.download).toHaveBeenCalledWith('d1');
    expect(files.triggerDownload).toHaveBeenCalled();
  });

  it('Supprimer : confirmé, retire la carte et notifie', () => {
    const root = build();
    dialogAnswer = true;
    fixture.componentInstance.remove(deck('d1', 'Onboarding CI/CD'));
    fixture.detectChanges();
    expect(service.delete).toHaveBeenCalledWith('d1');
    expect(root.querySelector('.decks__card')).toBeNull();
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('Supprimer : annulé, ne supprime rien', () => {
    build();
    dialogAnswer = false;
    fixture.componentInstance.remove(deck('d1', 'Onboarding CI/CD'));
    expect(service.delete).not.toHaveBeenCalled();
  });
});
