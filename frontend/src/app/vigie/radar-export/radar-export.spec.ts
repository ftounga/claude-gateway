import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { ExportService } from '../../core/services/export.service';
import { VigieService } from '../../core/services/vigie.service';
import { RadarExportOfferComponent } from './radar-export-offer.component';
import { RadarExporter, exportFileName, fallbackExportName } from './radar-export';

/** Exporter le Radar d'un client (F-99 / SF-99-07). */
describe('radar-export', () => {
  it('nomme le fichier comme la gateway, ou par un repli sûr', () => {
    expect(exportFileName('attachment; filename="radar-edenred-2026-09-13.md"', 'EDENRED'))
      .toBe('radar-edenred-2026-09-13.md');
    expect(exportFileName(null, 'Crédit Agricole — CAGIP')).toBe('radar-credit-agricole-cagip.md');
    expect(fallbackExportName('   ')).toBe('radar.md');
    expect(fallbackExportName('x'.repeat(200)).length).toBeLessThanOrEqual(80);
  });

  describe('RadarExporter et RadarExportOfferComponent', () => {
    let vigie: jasmine.SpyObj<VigieService>;
    let files: jasmine.SpyObj<ExportService>;

    const response = () => new HttpResponse<Blob>({
      body: new Blob(['# Radar']),
      headers: new HttpHeaders({ 'Content-Disposition': 'attachment; filename="radar-edenred-2026-09-13.md"' }),
    });

    beforeEach(() => {
      vigie = jasmine.createSpyObj<VigieService>('VigieService', ['exportRadar', 'purgeRadar']);
      files = jasmine.createSpyObj<ExportService>('ExportService', ['triggerDownload']);
      TestBed.configureTestingModule({
        imports: [RadarExportOfferComponent],
        providers: [
          provideNoopAnimations(),
          { provide: VigieService, useValue: vigie },
          { provide: ExportService, useValue: files },
        ],
      });
    });

    it('télécharge sous le nom de la gateway, et le rend', () => {
      vigie.exportRadar.and.returnValue(of(response()));
      let name: string | undefined;

      TestBed.inject(RadarExporter).download('h1', 'EDENRED').subscribe((n) => (name = n));

      expect(vigie.exportRadar).toHaveBeenCalledOnceWith('h1');
      expect(files.triggerDownload).toHaveBeenCalledOnceWith(jasmine.any(HttpResponse), 'radar-edenred-2026-09-13.md');
      expect(name).toBe('radar-edenred-2026-09-13.md');
      expect(vigie.purgeRadar).not.toHaveBeenCalled();
    });

    it("le bouton dit ce qui a été exporté ; un échec est dit, avec Réessayer", () => {
      const fixture = TestBed.createComponent(RadarExportOfferComponent);
      fixture.componentRef.setInput('hostId', 'h1');
      fixture.componentRef.setInput('hostName', 'EDENRED');
      fixture.componentRef.setInput('lead', 'Avant d’effacer, gardez-en une copie.');
      fixture.detectChanges();
      const root = fixture.nativeElement as HTMLElement;
      expect(root.textContent).toContain('Avant d’effacer');

      vigie.exportRadar.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
      (root.querySelector('.radar-export__button') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(root.querySelector('.radar-export__error')).not.toBeNull();
      expect(root.querySelector('.radar-export__button')?.textContent).toContain('Réessayer');

      vigie.exportRadar.and.returnValue(of(response()));
      (root.querySelector('.radar-export__button') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(root.querySelector('.radar-export__done')?.textContent).toContain('radar-edenred-2026-09-13.md');
      expect(root.querySelector('.radar-export__error')).toBeNull();
    });
  });
});
