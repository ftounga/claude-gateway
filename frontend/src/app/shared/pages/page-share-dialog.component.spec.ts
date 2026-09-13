import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Clipboard } from '@angular/cdk/clipboard';
import { HttpErrorResponse } from '@angular/common/http';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { PageJournalEntry, PageShareSummary } from '../../core/models/pages.models';
import { PagesService } from '../../core/services/pages.service';
import { PageShareDialogComponent, SHARE_WARNING, journalLabel } from './page-share-dialog.component';

/** Partager une page (F-109 / SF-109-05). */
describe('PageShareDialogComponent', () => {
  let fixture: ComponentFixture<PageShareDialogComponent>;
  let pages: jasmine.SpyObj<PagesService>;
  let clipboard: jasmine.SpyObj<Clipboard>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const active: PageShareSummary = {
    id: 's1', createdAt: '2026-09-13T10:00:00Z', expiresAt: '2026-09-20T10:00:00Z', revokedAt: null, openCount: 3,
    lastOpenedAt: '2026-09-14T08:00:00Z', state: 'ACTIVE',
  };
  const revoked: PageShareSummary = { ...active, id: 's0', state: 'REVOKED', revokedAt: '2026-09-12T10:00:00Z', openCount: 1 };
  const journal: PageJournalEntry[] = [
    { kind: 'OPENED', occurredAt: '2026-09-14T08:00:00Z', shareId: 's1', version: null },
    { kind: 'CREATED', occurredAt: '2026-09-13T09:00:00Z', shareId: null, version: 1 },
  ];

  function build(): HTMLElement {
    pages = jasmine.createSpyObj<PagesService>('PagesService', ['shares', 'journal', 'createShare', 'revokeShare']);
    pages.shares.and.returnValue(of([active, revoked]));
    pages.journal.and.returnValue(of(journal));
    clipboard = jasmine.createSpyObj<Clipboard>('Clipboard', ['copy']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [PageShareDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: { pageId: 'p1', title: 'Radar MFA' } },
        { provide: PagesService, useValue: pages },
        { provide: Clipboard, useValue: clipboard },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(PageShareDialogComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it("dit l'avertissement du cadrage, et ne crée rien tant que la case n'est pas cochée", () => {
    const root = build();

    expect(root.textContent).toContain(SHARE_WARNING);
    expect(SHARE_WARNING).toContain("vérifiez qu'il autorise leur diffusion");
    const submit = root.querySelector('.page-share__submit') as HTMLButtonElement;
    expect(submit.disabled).toBeTrue();
  });

  it('coché : crée un lien de 7 jours, montre l\'URL complète, la copie', async () => {
    const root = build();
    pages.createShare.and.returnValue(of({ id: 's2', url: '/p/' + 'a'.repeat(43), createdAt: '', expiresAt: '' }));

    fixture.componentInstance.checked = true;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    (root.querySelector('.page-share__submit') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(pages.createShare).toHaveBeenCalledOnceWith('p1', 7);
    const url = `${window.location.origin}/p/${'a'.repeat(43)}`;
    expect(root.querySelector('.page-share__url')?.textContent).toBe(url);
    (root.querySelector('.page-share__copy') as HTMLButtonElement).click();
    expect(clipboard.copy).toHaveBeenCalledOnceWith(url);
  });

  it('une durée hors de 1 à 90 bloque la création', () => {
    build();
    const component = fixture.componentInstance;
    component.checked = true;

    component.days = 0;
    expect(component.daysValid()).toBeFalse();
    component.days = 91;
    expect(component.daysValid()).toBeFalse();
    component.create();
    expect(pages.createShare).not.toHaveBeenCalled();
    component.days = 90;
    expect(component.daysValid()).toBeTrue();
  });

  it('liste les liens avec leur état et leurs ouvertures ; seul un lien actif se révoque', () => {
    const root = build();
    pages.revokeShare.and.returnValue(of(undefined));

    const items = root.querySelectorAll('.page-share__item');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('actif');
    expect(items[0].textContent).toContain('3 ouvertures');
    expect(items[1].textContent).toContain('révoqué');
    expect(items[1].querySelector('.page-share__revoke')).toBeNull();

    (items[0].querySelector('.page-share__revoke') as HTMLButtonElement).click();
    expect(pages.revokeShare).toHaveBeenCalledOnceWith('p1', 's1');
    expect(pages.shares).toHaveBeenCalledTimes(2);
  });

  it('le journal, lisible ; un échec de création est dit', () => {
    const root = build();
    expect(root.textContent).toContain('Lien de partage ouvert');
    expect(root.textContent).toContain('Page créée');
    expect(journalLabel({ kind: 'VERSION', occurredAt: '', shareId: null, version: 3 })).toBe('Version 3 publiée');

    pages.createShare.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    fixture.componentInstance.checked = true;
    fixture.componentInstance.create();
    expect(snackBar.open.calls.mostRecent().args[0]).toContain("n'a pas pu être créé");
  });
});
