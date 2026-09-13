import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { HostSpaces } from '../../core/models/vigie.models';
import { VigieService } from '../../core/services/vigie.service';
import { AddClientDialogComponent } from './add-client-dialog.component';

/** Ajouter un client à la Vigie (F-106 / SF-106-02) : activer sans appairer, ou connecter. */
describe('AddClientDialogComponent', () => {
  let fixture: ComponentFixture<AddClientDialogComponent>;
  let vigie: jasmine.SpyObj<VigieService>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<AddClientDialogComponent>>;

  const spaces: HostSpaces[] = [
    { hostId: 'h1', name: 'EDENRED', missionStatus: 'ACTIVE', spaces: ['FORGE'] },
    { hostId: 'h2', name: 'FREE', missionStatus: 'ACTIVE', spaces: ['FORGE', 'VIGIE'] },
  ];

  function setup(hosts = of(spaces)): HTMLElement {
    vigie = jasmine.createSpyObj<VigieService>('VigieService', ['hostSpaces', 'activate']);
    vigie.hostSpaces.and.returnValue(hosts);
    vigie.activate.and.returnValue(of({ ...spaces[0], spaces: ['FORGE', 'VIGIE'] }));
    dialogRef = jasmine.createSpyObj<MatDialogRef<AddClientDialogComponent>>('MatDialogRef', ['close']);
    TestBed.configureTestingModule({
      imports: [AddClientDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: VigieService, useValue: vigie },
        { provide: MatDialogRef, useValue: dialogRef },
      ],
    });
    fixture = TestBed.createComponent(AddClientDialogComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('ne propose que les clients qui ne sont pas encore dans la Vigie', () => {
    const root = setup();

    const items = Array.from(root.querySelectorAll('.add-client__item')).map((i) => i.textContent);
    expect(items.length).toBe(1);
    expect(items[0]).toContain('EDENRED');
  });

  it('active sans appairer, puis rend le client à ouvrir', () => {
    const root = setup();

    root.querySelector<HTMLButtonElement>('.add-client__activate')?.click();

    expect(vigie.activate).toHaveBeenCalledOnceWith('h1', 'VIGIE');
    expect(dialogRef.close).toHaveBeenCalledWith({ kind: 'activated', hostId: 'h1', hostName: 'EDENRED' });
  });

  it("dit l'échec d'une activation, sans fermer", () => {
    const root = setup();
    vigie.activate.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 404, error: { error: 'runner_host_not_found', message: 'Poste introuvable : h1' },
    })));

    root.querySelector<HTMLButtonElement>('.add-client__activate')?.click();
    fixture.detectChanges();

    expect(dialogRef.close).not.toHaveBeenCalled();
    expect(root.querySelector('.add-client__error')?.textContent).toContain('Poste introuvable');
  });

  it('rend la main pour connecter un nouveau client', () => {
    const root = setup();

    root.querySelector<HTMLButtonElement>('.add-client__connect-button')?.click();

    expect(dialogRef.close).toHaveBeenCalledWith({ kind: 'connect' });
  });

  it('dit quand tous les clients sont déjà dans la Vigie, et quand la liste est illisible', () => {
    let root = setup(of([spaces[1]]));
    expect(root.querySelector('.add-client__empty')?.textContent).toContain('déjà dans la Vigie');

    TestBed.resetTestingModule();
    root = setup(throwError(() => new HttpErrorResponse({ status: 500 })));
    expect(root.querySelector('.add-client__empty')?.textContent).toContain("n'a pas pu être lue");
  });
});
