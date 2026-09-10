import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { AccessCodesComponent } from './access-codes.component';
import { AccessCodeAdminService } from '../access-code-admin.service';
import { AccessCodeAdminView, IssuedAccessCode } from '../access-code-admin.models';

/**
 * Tests de la section « Codes d'accès » de l'administration (F-62 / SF-62-03).
 *
 * <p>Le test qui compte le plus est celui du code en clair : il doit apparaître **une fois** après
 * une création, et **jamais** dans la liste. C'est la seule propriété de cet écran qu'une régression
 * rendrait dangereuse.</p>
 */
describe('AccessCodesComponent', () => {
  let fixture: ComponentFixture<AccessCodesComponent>;
  let component: AccessCodesComponent;
  let service: jasmine.SpyObj<AccessCodeAdminService>;

  const issuedCode: AccessCodeAdminView = {
    id: 'c1',
    label: 'démo prospect',
    assignedEmail: null,
    grantedPlanCode: 'GOLD',
    durationHours: 24,
    validUntil: '2026-10-10T09:00:00Z',
    state: 'ISSUED',
    redeemedByEmail: null,
    redeemedAt: null,
    grantedUntil: null,
    previousPlanCode: null,
    createdAt: '2026-09-10T09:00:00Z',
  };
  const liveCode: AccessCodeAdminView = {
    ...issuedCode,
    id: 'c2',
    label: 'dépannage client',
    assignedEmail: 'client@example.com',
    state: 'ACTIVE',
    redeemedByEmail: 'client@example.com',
    redeemedAt: '2026-09-10T10:00:00Z',
    grantedUntil: '2026-09-11T10:00:00Z',
    previousPlanCode: 'SOLO',
  };

  function setup(codes: AccessCodeAdminView[] | 'fails' = [issuedCode, liveCode]): void {
    service = jasmine.createSpyObj<AccessCodeAdminService>('AccessCodeAdminService', [
      'list',
      'issue',
    ]);
    service.list.and.returnValue(
      codes === 'fails'
        ? throwError(() => new HttpErrorResponse({ status: 500 }))
        : of(codes),
    );

    TestBed.configureTestingModule({
      imports: [AccessCodesComponent],
      providers: [
        provideNoopAnimations(),
        { provide: AccessCodeAdminService, useValue: service },
      ],
    });
    fixture = TestBed.createComponent(AccessCodesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  /** Simule l'ouverture du dialogue de création, qui se referme sur le brouillon donné. */
  function stubDialog(result: unknown): void {
    spyOn(TestBed.inject(MatDialog), 'open').and.returnValue({
      afterClosed: () => of(result),
    } as unknown as MatDialogRef<unknown>);
  }

  it('rend les codes renvoyés par le service', () => {
    setup();

    expect(component.codes().length).toBe(2);
    expect(component.loading()).toBeFalse();
    expect(component.failed()).toBeFalse();
  });

  it("nomme l'état et le destinataire en français", () => {
    setup();

    expect(component.stateLabel('ISSUED')).toBe('à remettre');
    expect(component.stateLabel('ACTIVE')).toBe('en cours');
    expect(component.stateLabel('ENDED')).toBe('terminé');
    expect(component.stateLabel('EXPIRED')).toBe('périmé');
    expect(component.recipient(issuedCode)).toBe('non nominatif');
    expect(component.recipient(liveCode)).toBe('client@example.com');
  });

  it('affiche le code en clair après création, et recharge la liste', () => {
    setup();
    const issued: IssuedAccessCode = { code: 'FORGE-AB2C-3D4E', view: issuedCode };
    service.issue.and.returnValue(of(issued));
    stubDialog({ label: 'démo prospect', assignedEmail: 'prospect@example.com' });

    component.create();

    expect(service.issue).toHaveBeenCalledWith('démo prospect', 'prospect@example.com');
    expect(component.freshCode()).toBe('FORGE-AB2C-3D4E');
    // Deux appels : le chargement initial, puis le rechargement après création.
    expect(service.list).toHaveBeenCalledTimes(2);
    expect(component.busy()).toBeFalse();
  });

  it("le code en clair ne vient JAMAIS de la liste — il n'y figure pas", () => {
    setup();

    // Rien dans les vues de liste ne porte de code : la seule source est la réponse de création.
    const serialized = JSON.stringify(component.codes());
    expect(serialized).not.toContain('FORGE-');
    expect(component.freshCode()).toBeNull();
  });

  it("l'admin peut effacer le code affiché", () => {
    setup();
    service.issue.and.returnValue(of({ code: 'FORGE-AB2C-3D4E', view: issuedCode }));
    stubDialog({ label: 'démo' });

    component.create();
    component.dismissFreshCode();

    expect(component.freshCode()).toBeNull();
  });

  it("un dialogue annulé n'émet rien", () => {
    setup();
    stubDialog(undefined);

    component.create();

    expect(service.issue).not.toHaveBeenCalled();
  });

  it('affiche le message du backend quand la création échoue', () => {
    setup();
    const open = spyOn(TestBed.inject(MatSnackBar), 'open');
    service.issue.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 400,
            error: { error: 'validation_error', message: 'Le libellé est obligatoire.' },
          }),
      ),
    );
    stubDialog({ label: 'x' });

    component.create();

    expect(open).toHaveBeenCalledWith('Le libellé est obligatoire.', 'Fermer', jasmine.any(Object));
    expect(component.freshCode()).toBeNull();
    expect(component.busy()).toBeFalse();
  });

  it("signale l'échec de lecture et propose de réessayer", () => {
    setup('fails');

    expect(component.failed()).toBeTrue();
    expect(component.loading()).toBeFalse();
    expect(component.codes()).toEqual([]);
  });
});
