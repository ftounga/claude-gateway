import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { ConfirmDialogComponent } from '../../chat/confirm-dialog/confirm-dialog.component';
import { TextPromptDialogComponent } from '../../atelier/files/text-prompt-dialog.component';
import { PageSummary } from '../../core/models/pages.models';
import { ExportService } from '../../core/services/export.service';
import { PagesService } from '../../core/services/pages.service';
import { HOST_PAGES_PAGE_SIZE, HostPagesComponent } from './host-pages.component';
import { PageVersionsDialogComponent, sizeLabel } from './page-versions-dialog.component';

/** L'onglet Pages d'un poste ou d'un client (F-109 / SF-109-04). */
describe('HostPagesComponent', () => {
  let fixture: ComponentFixture<HostPagesComponent>;
  let pages: jasmine.SpyObj<PagesService>;
  let files: jasmine.SpyObj<ExportService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let dialogAnswer: unknown;

  const page = (id: string, title: string, workspaceId: string | null = null): PageSummary => ({
    id, title, description: null, space: 'FORGE', hostId: 'h1', workspaceId, currentVersion: 3,
    createdAt: '2026-09-13T10:00:00Z', updatedAt: '2026-09-13T12:30:00Z', viewUrl: `/api/p/t1.${id}.x/`,
  });

  function build(list: PageSummary[] | 'error' = [page('p1', 'Maquette de la Forge', 'w1')]): HTMLElement {
    pages = jasmine.createSpyObj<PagesService>('PagesService', ['list', 'rename', 'remove', 'download']);
    pages.list.and.returnValue(list === 'error' ? throwError(() => new HttpErrorResponse({ status: 500 })) : of(list));
    files = jasmine.createSpyObj<ExportService>('ExportService', ['triggerDownload']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.callFake((() => ({ afterClosed: () => of(dialogAnswer) })) as never);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [HostPagesComponent],
      providers: [
        provideNoopAnimations(),
        { provide: PagesService, useValue: pages },
        { provide: ExportService, useValue: files },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(HostPagesComponent);
    fixture.componentRef.setInput('hostId', 'h1');
    fixture.componentRef.setInput('space', 'FORGE');
    fixture.componentRef.setInput('projectNames', { w1: 'portail-client' });
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('une carte par page : vignette sandboxée, titre, projet, version', () => {
    const root = build();

    expect(pages.list).toHaveBeenCalledOnceWith('h1', 'FORGE');
    const card = root.querySelector('.host-pages__card')!;
    expect(card.textContent).toContain('Maquette de la Forge');
    expect(card.textContent).toContain('portail-client');
    expect(card.textContent).toContain('v3');
    const frame = card.querySelector('iframe')!;
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts allow-popups');
    expect(frame.getAttribute('src')).toBe('/api/p/t1.p1.x/');
  });

  it("vide : dit comment en obtenir une ; illisible : le dit", () => {
    expect(build([]).textContent).toContain('Aucune page ici');
    TestBed.resetTestingModule();
    expect(build('error').textContent).toContain("n'ont pas pu être lues");
  });

  it('pagine au-delà de douze pages', () => {
    const many = Array.from({ length: HOST_PAGES_PAGE_SIZE + 2 }, (_, i) => page(`p${i}`, `Page ${i}`));
    const root = build(many);

    expect(root.querySelectorAll('.host-pages__card').length).toBe(HOST_PAGES_PAGE_SIZE);
    expect(root.querySelector('mat-paginator')).not.toBeNull();
    fixture.componentInstance.onPage({ pageIndex: 1, pageSize: HOST_PAGES_PAGE_SIZE, length: many.length });
    fixture.detectChanges();
    expect(root.querySelectorAll('.host-pages__card').length).toBe(2);
  });

  it('Ouvrir : plein écran dans un nouvel onglet', () => {
    build();
    const open = spyOn(window, 'open');

    fixture.componentInstance.open(page('p1', 'Maquette'));

    expect(open).toHaveBeenCalledWith('/pages/p1', '_blank', 'noopener');
  });

  it('Renommer : dialogue de saisie, puis le titre change dans la carte', () => {
    const root = build();
    dialogAnswer = 'Forge refondue';
    pages.rename.and.returnValue(of({ ...page('p1', 'Forge refondue', 'w1') }));

    fixture.componentInstance.rename(page('p1', 'Maquette de la Forge', 'w1'));
    fixture.detectChanges();

    expect(dialog.open.calls.mostRecent().args[0]).toBe(TextPromptDialogComponent);
    expect(pages.rename).toHaveBeenCalledOnceWith('p1', 'Forge refondue');
    expect(root.textContent).toContain('Forge refondue');
  });

  it('Supprimer : confirmation d\'abord ; refusée, rien ; confirmée, la carte part ; un échec est dit', () => {
    const root = build();
    pages.remove.and.returnValue(of(undefined));

    dialogAnswer = false;
    fixture.componentInstance.remove(page('p1', 'Maquette de la Forge'));
    expect(dialog.open.calls.mostRecent().args[0]).toBe(ConfirmDialogComponent);
    expect(pages.remove).not.toHaveBeenCalled();

    pages.remove.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    dialogAnswer = true;
    fixture.componentInstance.remove(page('p1', 'Maquette de la Forge'));
    fixture.detectChanges();
    expect(root.querySelectorAll('.host-pages__card').length).toBe(1);
    expect(snackBar.open.calls.mostRecent().args[0]).toContain("n'a pas pu être supprimée");

    pages.remove.and.returnValue(of(undefined));
    fixture.componentInstance.remove(page('p1', 'Maquette de la Forge'));
    fixture.detectChanges();
    expect(root.querySelectorAll('.host-pages__card').length).toBe(0);
  });

  it('Télécharger le fichier HTML ; Versions précédentes ouvre leur dialogue', () => {
    build();
    const response = new HttpResponse<Blob>({ body: new Blob(['<h1>x</h1>']) });
    pages.download.and.returnValue(of(response));

    fixture.componentInstance.download(page('p1', 'Maquette'));
    expect(pages.download).toHaveBeenCalledOnceWith('p1');
    expect(files.triggerDownload).toHaveBeenCalledOnceWith(response, 'page.html');

    fixture.componentInstance.versions(page('p1', 'Maquette'));
    expect(dialog.open.calls.mostRecent().args[0]).toBe(PageVersionsDialogComponent);
  });

  it('la taille lisible des versions', () => {
    expect(sizeLabel(500)).toBe('1 Ko');
    expect(sizeLabel(12 * 1024)).toBe('12 Ko');
    expect(sizeLabel(1.5 * 1024 * 1024)).toBe('1,5 Mo');
  });
});
