import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import {
  RemoveClientDialogComponent,
  RemoveClientDialogData,
} from './remove-client-dialog.component';

/** Retirer un client de la Vigie (F-106 / SF-106-02) : rien de supprimé, le Radar seulement si demandé. */
describe('RemoveClientDialogComponent', () => {
  let fixture: ComponentFixture<RemoveClientDialogComponent>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<RemoveClientDialogComponent>>;

  function setup(data: RemoveClientDialogData): HTMLElement {
    dialogRef = jasmine.createSpyObj<MatDialogRef<RemoveClientDialogComponent>>('MatDialogRef', ['close']);
    TestBed.configureTestingModule({
      imports: [RemoveClientDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRef },
      ],
    });
    fixture = TestBed.createComponent(RemoveClientDialogComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it("dit que rien n'est supprimé, et ne purge pas par défaut", () => {
    const root = setup({ hostName: 'EDENRED', inForge: true });

    expect(root.textContent).toContain("Rien n'est supprimé.");
    root.querySelector<HTMLButtonElement>('.remove-client__confirm')?.click();

    expect(dialogRef.close).toHaveBeenCalledWith({ confirmed: true, purgeRadar: false });
  });

  it("n'efface le Radar que si la case est cochée", () => {
    const root = setup({ hostName: 'EDENRED', inForge: true });

    fixture.componentInstance.purgeRadar.set(true);
    fixture.detectChanges();
    expect(root.querySelector('.remove-client__confirm')?.textContent).toContain('effacer le Radar');
    root.querySelector<HTMLButtonElement>('.remove-client__confirm')?.click();

    expect(dialogRef.close).toHaveBeenCalledWith({ confirmed: true, purgeRadar: true });
  });

  it("ne propose pas le retrait d'un client qui n'est que dans la Vigie", () => {
    const root = setup({ hostName: 'CAGIP', inForge: false });

    expect(root.textContent).toContain("n'est que dans la Vigie");
    expect(root.querySelector('.remove-client__confirm')).toBeNull();
    fixture.componentInstance.confirm();
    expect(dialogRef.close).not.toHaveBeenCalled();
  });
});
