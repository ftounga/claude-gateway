import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { TerminalAccesViewComponent, isoDay } from './terminal-acces-view.component';
import { AccesObtenu, expiryLabel } from '../../core/models/terminal-actions.models';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';
import { mockTerminalActionsService } from '../../core/services/terminal-actions.service.testing';

function entry(id: string, system: string, over: Partial<AccesObtenu> = {}): AccesObtenu {
  return { id, system, account: null, obtainedAt: '2026-10-02', grantedBy: null, interlocuteurId: null,
    expiresAt: null, expired: false, subjectId: null, actionId: null, note: null, workspaceId: 'w-1', ...over };
}

/** Le registre des accès à l'écran (F-180 / SF-180-04). */
describe('La vue Accès (F-180 / SF-180-04)', () => {
  let fixture: ComponentFixture<TerminalAccesViewComponent>;
  let component: TerminalAccesViewComponent;
  let service: jasmine.SpyObj<TerminalActionsService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let dialog: jasmine.SpyObj<MatDialog>;

  function build(setup: (s: jasmine.SpyObj<TerminalActionsService>) => void, confirm = true): void {
    service = mockTerminalActionsService();
    setup(service);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.returnValue({ afterClosed: () => of(confirm) } as never);
    TestBed.configureTestingModule({
      imports: [TerminalAccesViewComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(),
        { provide: TerminalActionsService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialog, useValue: dialog }],
    });
    fixture = TestBed.createComponent(TerminalAccesViewComponent);
    fixture.componentRef.setInput('workspaceId', 'w-1');
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => TestBed.resetTestingModule());

  it('liste le registre : système, compte, date, par qui, expiration', () => {
    build(s => s.acces.and.returnValue(of([
      entry('a-1', 'VPN Fortinet', { account: 'k.dupont', grantedBy: 'Rémi', expiresAt: '2000-01-01', expired: true }),
    ])));
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('VPN Fortinet');
    expect(text).toContain('k.dupont');
    expect(text).toContain('obtenu le 02/10/2026 par Rémi');
    expect(fixture.nativeElement.querySelector('.acces__expiry--late').textContent).toContain('expiré');
  });

  it('ajouter : envoie système et compte, l’entrée apparaît', () => {
    build(s => s.addAcces.and.returnValue(of(entry('a-2', 'Forge'))));
    component.startAdd();
    component.form.system = ' Forge ';
    component.form.account = 'svc';
    component.save();
    fixture.detectChanges();
    expect(service.addAcces).toHaveBeenCalledWith('w-1', jasmine.objectContaining({ system: 'Forge', account: 'svc' }));
    expect(fixture.nativeElement.textContent).toContain('Forge');
  });

  it('un secret refusé : le message du serveur est dit tel quel', () => {
    build(s => s.addAcces.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { error: 'terminal_action_invalid', message: 'Le registre ne garde aucun secret' },
    }))));
    component.startAdd();
    component.form.system = 'VPN';
    component.save();
    expect(snackBar.open).toHaveBeenCalledWith('Le registre ne garde aucun secret', 'Fermer', jasmine.any(Object));
  });

  it('retirer : confirmé par un dialogue ; annulé, rien ne part', () => {
    build(s => {
      s.acces.and.returnValue(of([entry('a-1', 'VPN')]));
      s.deleteAcces.and.returnValue(of(undefined));
    }, false);
    component.remove(component.entries()[0]);
    expect(dialog.open).toHaveBeenCalled();
    expect(service.deleteAcces).not.toHaveBeenCalled();
  });

  it('dates : jour lisible, échéance', () => {
    expect(isoDay('2026-10-02')).toBe('02/10/2026');
    expect(expiryLabel('2026-10-20', new Date('2026-10-16T10:00:00'))).toBe('expire dans 4 j');
    expect(expiryLabel('2026-10-10', new Date('2026-10-16T10:00:00'))).toBe('expiré');
    expect(expiryLabel(null)).toBe('');
  });
});
