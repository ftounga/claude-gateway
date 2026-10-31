import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { DepositPreviewDialogComponent } from './deposit-preview-dialog.component';
import { cellText, csvDelimiter, parseCsv } from './deposit-preview';

/** F-228 / SF-228-03 — les tableurs, en lecture seule. */
describe('F-228 / SF-228-03 — tableurs', () => {
  it('CSV : séparateur deviné, guillemets, guillemets doublés, retours à la ligne entre guillemets', () => {
    expect(csvDelimiter('a;b;c\n1;2;3')).toBe(';');
    expect(csvDelimiter('a\tb\n1\t2')).toBe('\t');
    expect(csvDelimiter('"x;y",b,c\n')).toBe(',');
    const sheet = parseCsv('﻿nom;montant\n"Dupont; Jean";"12,5"\n"dit ""oui""";"ligne\nsuite"\r\n');
    expect(sheet.rows).toEqual([['nom', 'montant'], ['Dupont; Jean', '12,5'], ['dit "oui"', 'ligne\nsuite']]);
    expect(sheet.truncated).toBeFalse();
  });

  it('CSV : 500 lignes au plus, la coupe est dite', () => {
    const many = Array.from({ length: 800 }, (_, i) => `l${i},${i}`).join('\n');
    const sheet = parseCsv(many);
    expect(sheet.rows.length).toBe(500);
    expect(sheet.truncated).toBeTrue();
    expect(parseCsv('a,b\n1,2\n', 2).truncated).toBeFalse();
  });

  it('une cellule : date lisible, vide pour null', () => {
    expect(cellText(null)).toBe('');
    expect(cellText(12.5)).toBe('12.5');
    expect(cellText(new Date(2026, 9, 31))).toBe('31/10/2026');
  });

  describe('dans la boîte d’aperçu', () => {
    let path = 'a/export.csv';

    beforeEach(() => {
      TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
          { provide: MAT_DIALOG_DATA, useFactory: () => ({ workspaceId: 'w1', depositId: 'd1', path, size: 100 }) }],
      });
    });

    afterEach(() => TestBed.inject(HttpTestingController).match(() => true));

    async function settle(fixture: { detectChanges: () => void; whenStable: () => Promise<unknown> }): Promise<void> {
      for (let i = 0; i < 5; i++) {
        fixture.detectChanges();
        await fixture.whenStable();
        await new Promise((resolve) => setTimeout(resolve, 20));
      }
      fixture.detectChanges();
    }

    it('un CSV s’affiche en tableau, en-tête compris, sans HTML interprété', async () => {
      path = 'a/export.csv';
      const fixture = TestBed.createComponent(DepositPreviewDialogComponent);
      fixture.detectChanges();
      TestBed.inject(HttpTestingController).expectOne('/api/workspaces/w1/deposits/d1/content')
        .flush(new Blob(['nom,note\n<b>Ana</b>,12\n'], { type: 'text/csv' }));
      await settle(fixture);
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('.dp__table th')?.textContent).toBe('nom');
      expect(el.querySelector('.dp__table td')?.textContent).toBe('<b>Ana</b>');
      expect(el.querySelector('.dp__table b')).toBeNull();
    });

    it('un xlsx illisible : la bibliothèque est chargée au clic, l’échec est dit', async () => {
      path = 'a/budget.xlsx';
      const fixture = TestBed.createComponent(DepositPreviewDialogComponent);
      fixture.detectChanges();
      TestBed.inject(HttpTestingController).expectOne('/api/workspaces/w1/deposits/d1/content')
        .flush(new Blob(['pas un classeur'], { type: 'application/octet-stream' }));
      await settle(fixture);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('n’a pas pu être lu');
    });
  });
});
