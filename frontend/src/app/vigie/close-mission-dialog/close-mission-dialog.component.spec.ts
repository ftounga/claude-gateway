import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { RadarExporter } from '../radar-export/radar-export';
import { HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { ExportService } from '../../core/services/export.service';
import { PagesService } from '../../core/services/pages.service';
import { CloseMissionDialogComponent } from './close-mission-dialog.component';

/** Clôturer la mission d'un client de la Vigie (F-99 / SF-99-07). */
describe('CloseMissionDialogComponent', () => {
  let fixture: ComponentFixture<CloseMissionDialogComponent>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<CloseMissionDialogComponent>>;
  let pages: jasmine.SpyObj<PagesService>;
  let files: jasmine.SpyObj<ExportService>;

  function setup(): HTMLElement {
    dialogRef = jasmine.createSpyObj<MatDialogRef<CloseMissionDialogComponent>>('MatDialogRef', ['close']);
    pages = jasmine.createSpyObj<PagesService>('PagesService', ['exportPlace']);
    files = jasmine.createSpyObj<ExportService>('ExportService', ['triggerDownload']);
    TestBed.configureTestingModule({
      imports: [CloseMissionDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: { hostId: 'h1', hostName: 'EDENRED' } },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: RadarExporter, useValue: jasmine.createSpyObj<RadarExporter>('RadarExporter', ['download']) },
        { provide: PagesService, useValue: pages },
        { provide: ExportService, useValue: files },
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

    expect(dialogRef.close).toHaveBeenCalledWith({ confirmed: true, purgeRadar: false, purgePages: false });
  });

  it("n'efface le Radar que si la case est cochée ; annuler ne clôt rien", () => {
    const root = setup();

    fixture.componentInstance.purgeRadar.set(true);
    fixture.detectChanges();
    expect(root.querySelector('.close-mission__confirm')?.textContent).toContain('effacer le Radar');
    root.querySelector<HTMLButtonElement>('.close-mission__confirm')?.click();
    expect(dialogRef.close).toHaveBeenCalledWith({ confirmed: true, purgeRadar: true, purgePages: false });

    root.querySelector<HTMLButtonElement>('.close-mission__cancel')?.click();
    expect(dialogRef.close).toHaveBeenCalledWith({ confirmed: false, purgeRadar: false, purgePages: false });
  });

  // ---- F-109 / SF-109-04 : ses pages ----

  it("propose le téléchargement de ses pages, n'efface ses pages que si la case est cochée", () => {
    const root = setup();
    expect(root.textContent).toContain('Ses pages peuvent être gardées');
    const box = root.querySelector('.close-mission__purge-pages input[type="checkbox"]') as HTMLInputElement;
    expect(box.checked).toBeFalse();

    fixture.componentInstance.purgePages.set(true);
    fixture.detectChanges();
    expect(root.querySelector('.close-mission__confirm')?.textContent).toContain('effacer les pages');
    root.querySelector<HTMLButtonElement>('.close-mission__confirm')?.click();
    expect(dialogRef.close).toHaveBeenCalledWith({ confirmed: true, purgeRadar: false, purgePages: true });
  });

  it("télécharger ses pages : l'archive du client ; un échec le dit", () => {
    const root = setup();
    const response = new HttpResponse<Blob>({ body: new Blob(['zip']) });
    pages.exportPlace.and.returnValue(of(response));

    root.querySelector<HTMLButtonElement>('.close-mission__pages-export')?.click();
    fixture.detectChanges();
    expect(pages.exportPlace).toHaveBeenCalledOnceWith('h1', 'VIGIE');
    expect(files.triggerDownload).toHaveBeenCalledOnceWith(response, 'pages.zip');
    expect(root.textContent).toContain('Pages téléchargées');

    pages.exportPlace.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    root.querySelector<HTMLButtonElement>('.close-mission__pages-export')?.click();
    fixture.detectChanges();
    expect(root.querySelector('.close-mission__pages-error')).not.toBeNull();
  });
});
