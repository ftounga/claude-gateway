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
      'trials',
    ]);
    service.trials.and.returnValue(of([]));
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

  it("SF-107-04 : nomme l'espace et la durée de chaque code", () => {
    setup([
      { ...issuedCode, space: 'VIGIE', durationHours: 336 },
      { ...liveCode, space: 'FORGE' },
      { ...liveCode, id: 'c3', space: null },
    ]);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Essai Vigie pendant 14 j');
    expect(text).toContain('Forge pendant 24 h');
    expect(text).toContain('Forge et Vigie pendant 24 h');
  });

  it("SF-107-04 : émet un essai Vigie quand le dialogue le demande", () => {
    setup();
    service.issue.and.returnValue(of({ code: 'FORGE-AB2C-3D4E', view: issuedCode }));
    stubDialog({ label: 'essai ACME', space: 'VIGIE' });

    component.create();

    expect(service.issue).toHaveBeenCalledWith('essai ACME', undefined, 'VIGIE');
  });

  it('SF-107-04 : montre le coût réel des essais Vigie, synchro par synchro', () => {
    setup();
    component.trials.set([
      {
        codeId: 't1', label: 'essai ACME', email: 'carol@example.com', startedAt: '2026-09-13T09:00:00Z',
        endsAt: '2026-09-27T09:00:00Z', active: true, syncCount: 2, consumedTokens: 2700000, costUsd: 18.4,
        syncs: [
          { syncId: 's1', hostId: 'h1', startedAt: '2026-09-13T20:00:00Z', status: 'SUCCEEDED', firstSync: true,
            consumedTokens: 2000000, costUsd: 14.2, stoppedOnReserve: false },
          { syncId: 's2', hostId: 'h1', startedAt: '2026-09-14T20:00:00Z', status: 'SUCCEEDED', firstSync: false,
            consumedTokens: 700000, costUsd: 4.2, stoppedOnReserve: false },
        ],
      },
    ]);
    fixture.detectChanges();

    const section = (fixture.nativeElement as HTMLElement)
      .querySelector('section[aria-label="Mesure des essais Vigie"]') as HTMLElement;
    expect(section.textContent).toContain('essai ACME');
    expect(section.textContent).toContain('2 synchros');
    expect(section.textContent).toContain('première synchro (hors réserve)');
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
