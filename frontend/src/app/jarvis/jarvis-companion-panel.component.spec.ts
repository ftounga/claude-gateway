import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { JarvisCompanionDevice } from '../core/models/jarvis.models';
import { JarvisService } from '../core/services/jarvis.service';
import { JarvisCompanionPanelComponent } from './jarvis-companion-panel.component';

describe('JarvisCompanionPanelComponent (F-232 / SF-232-03)', () => {
  let api: jasmine.SpyObj<JarvisService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  const PIXEL: JarvisCompanionDevice = {
    id: 'd1', name: 'Google Pixel 8', pairedAt: '2026-11-01T10:00:00Z', lastSeenAt: '2026-11-01T11:00:00Z',
  };

  beforeEach(() => {
    api = jasmine.createSpyObj<JarvisService>('JarvisService',
      ['companionDevices', 'companionPairingCode', 'revokeCompanionDevice']);
    api.companionDevices.and.returnValue(of([PIXEL]));
    api.companionPairingCode.and.returnValue(of({ code: 'ABCD2345', expiresAt: '2026-11-01T10:10:00Z' }));
    api.revokeCompanionDevice.and.returnValue(of(undefined));
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    TestBed.configureTestingModule({
      imports: [JarvisCompanionPanelComponent],
      providers: [
        { provide: JarvisService, useValue: api },
        { provide: MatDialog, useValue: dialog },
      ],
    });
  });

  function opened() {
    const fixture = TestBed.createComponent(JarvisCompanionPanelComponent);
    fixture.componentRef.setInput('downloadable', true);
    fixture.detectChanges();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.jcp__toggle')!.click();
    fixture.detectChanges();
    return fixture;
  }

  it('replié au départ ; ouvert, il montre le parcours, le lien et les téléphones appairés', () => {
    const fixture = TestBed.createComponent(JarvisCompanionPanelComponent);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.jcp__body')).toBeNull();

    const open = opened();
    const el = open.nativeElement as HTMLElement;
    expect(el.querySelector('a')?.getAttribute('href')).toBe('/api/runner/download/android');
    expect(el.textContent).toContain('Apprendre « Jarvis »');
    expect(el.querySelectorAll('.jcp__device').length).toBe(1);
    expect(el.textContent).toContain('Google Pixel 8');
  });

  it('« Appairer un téléphone » affiche le code à taper', () => {
    const fixture = opened();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.jcp__pair')!.click();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.jcp__code-value')?.textContent).toBe('ABCD2345');
  });

  it('au-delà de cinq téléphones, un message clair (409)', () => {
    api.companionPairingCode.and.returnValue(throwError(() => new HttpErrorResponse({ status: 409 })));
    const fixture = opened();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.jcp__pair')!.click();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.jcp__error')?.textContent).toContain('Cinq téléphones');
    expect((fixture.nativeElement as HTMLElement).querySelector('.jcp__code-value')).toBeNull();
  });

  it('révoquer passe par une confirmation Material, puis retire le téléphone', () => {
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as ReturnType<MatDialog['open']>);
    const fixture = opened();
    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.jcp__device button');
    buttons[0].click();
    fixture.detectChanges();

    expect(dialog.open).toHaveBeenCalled();
    expect(api.revokeCompanionDevice).toHaveBeenCalledWith('d1');
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.jcp__device').length).toBe(0);
  });

  it('révocation annulée : rien ne change', () => {
    dialog.open.and.returnValue({ afterClosed: () => of(false) } as ReturnType<MatDialog['open']>);
    const fixture = opened();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.jcp__device button')!.click();
    fixture.detectChanges();

    expect(api.revokeCompanionDevice).not.toHaveBeenCalled();
  });
});
