import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { RadarDraft } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { RadarDraftDialogComponent } from './radar-draft-dialog.component';

/** Relance ou présentation préparée — le dialogue (F-104 / SF-104-05). */
describe('RadarDraftDialogComponent', () => {
  let fixture: ComponentFixture<RadarDraftDialogComponent>;
  let radar: jasmine.SpyObj<RadarService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const prepared = (extra: Partial<RadarDraft> = {}): RadarDraft => ({
    kind: 'FOLLOW_UP', text: 'Bonjour Julie, as-tu pu avancer ?',
    conversationUrl: 'https://teams.microsoft.com/l/message/recent', preparedAt: '2026-09-13T10:00:00Z', ...extra,
  });

  function build(draft = of(prepared())): HTMLElement {
    radar = jasmine.createSpyObj<RadarService>('RadarService', ['prepareDraft']);
    radar.prepareDraft.and.returnValue(draft);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [RadarDraftDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: RadarService, useValue: radar },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialogRef, useValue: jasmine.createSpyObj('MatDialogRef', ['close']) },
        { provide: MAT_DIALOG_DATA, useValue: { hostId: 'h1', commitmentId: 'c3', kind: 'FOLLOW_UP',
          title: "Julie — Retour de l'éditeur SSO" } },
      ],
    });
    fixture = TestBed.createComponent(RadarDraftDialogComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

  it("prépare à l'ouverture : le brouillon, Ouvrir la conversation en nouvel onglet, rien n'est envoyé", () => {
    const root = build();

    expect(radar.prepareDraft).toHaveBeenCalledOnceWith('h1', 'c3');
    expect(fixture.componentInstance.text()).toBe('Bonjour Julie, as-tu pu avancer ?');
    expect(text(root)).toContain("Rien n'est envoyé : c'est vous qui envoyez.");
    const open = root.querySelector('.radar-draft__open') as HTMLAnchorElement;
    expect(open.getAttribute('href')).toBe('https://teams.microsoft.com/l/message/recent');
    expect(open.getAttribute('target')).toBe('_blank');
    expect(open.getAttribute('rel')).toBe('noopener noreferrer');
  });

  it('copie le brouillon retouché', fakeAsync(() => {
    build();
    const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    fixture.componentInstance.text.set('Bonjour Julie, petite relance sur le retour SSO.');

    fixture.componentInstance.copy();
    flushMicrotasks();

    expect(writeText).toHaveBeenCalledWith('Bonjour Julie, petite relance sur le retour SSO.');
    expect(snackBar.open).toHaveBeenCalledWith('Brouillon copié : collez-le dans la conversation.', 'Fermer', jasmine.any(Object));
  }));

  it("sans conversation d'origine : pas de lien, l'absence est dite ; Préparer à nouveau rappelle", () => {
    const root = build(of(prepared({ conversationUrl: null })));
    expect(root.querySelector('.radar-draft__open')).toBeNull();
    expect(text(root.querySelector('.radar-draft__no-link'))).toContain('Aucune conversation Teams');

    fixture.componentInstance.prepare();
    expect(radar.prepareDraft).toHaveBeenCalledTimes(2);
  });

  it('402 dit le quota', () => {
    const root = build(throwError(() => new HttpErrorResponse({ status: 402 })));
    fixture.detectChanges();
    expect(text(root.querySelector('.radar-draft__error'))).toContain('quota');
  });
});
