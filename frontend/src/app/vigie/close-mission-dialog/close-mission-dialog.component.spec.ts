import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { RadarExporter } from '../radar-export/radar-export';
import { CloseMissionDialogComponent } from './close-mission-dialog.component';

/** Clôturer la mission d'un client de la Vigie (F-99 / SF-99-07). */
describe('CloseMissionDialogComponent', () => {
  let fixture: ComponentFixture<CloseMissionDialogComponent>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<CloseMissionDialogComponent>>;

  function setup(): HTMLElement {
    dialogRef = jasmine.createSpyObj<MatDialogRef<CloseMissionDialogComponent>>('MatDialogRef', ['close']);
    TestBed.configureTestingModule({
      imports: [CloseMissionDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: { hostId: 'h1', hostName: 'EDENRED' } },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: RadarExporter, useValue: jasmine.createSpyObj<RadarExporter>('RadarExporter', ['download']) },
      ],
    });
    fixture = TestBed.createComponent(CloseMissionDialogComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it("propose l'export, dit que rien n'est coupé, et n'efface pas par défaut", () => {
    const root = setup();

    expect(root.textContent).toContain("Rien n'est coupé");
    expect(root.querySelector('app-radar-export-offer')).not.toBeNull();
    root.querySelector<HTMLButtonElement>('.close-mission__confirm')?.click();

    expect(dialogRef.close).toHaveBeenCalledWith({ confirmed: true, purgeRadar: false });
  });

  it("n'efface le Radar que si la case est cochée ; annuler ne clôt rien", () => {
    const root = setup();

    fixture.componentInstance.purgeRadar.set(true);
    fixture.detectChanges();
    expect(root.querySelector('.close-mission__confirm')?.textContent).toContain('effacer le Radar');
    root.querySelector<HTMLButtonElement>('.close-mission__confirm')?.click();
    expect(dialogRef.close).toHaveBeenCalledWith({ confirmed: true, purgeRadar: true });

    root.querySelector<HTMLButtonElement>('.close-mission__cancel')?.click();
    expect(dialogRef.close).toHaveBeenCalledWith({ confirmed: false, purgeRadar: false });
  });
});
