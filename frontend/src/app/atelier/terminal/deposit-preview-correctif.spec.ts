import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { MAT_DIALOG_DATA } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { DepositPreviewDialogComponent } from './deposit-preview-dialog.component';
import {
  MAX_PREVIEW_BYTES, cellText, decodeCsv, decodeTextPreview, depositDownloadUrl, parseCsv, previewKindOf, truncateText,
} from './deposit-preview';

/**
 * F-228 / SF-228-04 — correctif de l'aperçu : téléchargement au-delà de 20 Mo, texte en <pre> (200 Ko), docx en texte
 * brut, CSV Windows-1252, dates xlsx en UTC.
 */
describe('F-228 / SF-228-04 — correctif de l’aperçu', () => {
  it('texte : les vues txt/md/log/json ; la route de téléchargement', () => {
    expect(previewKindOf('a/notes.TXT')).toBe('text');
    expect(previewKindOf('a/README.md')).toBe('text');
    expect(previewKindOf('a/run.log')).toBe('text');
    expect(previewKindOf('a/data.json')).toBe('text');
    expect(previewKindOf('a/lettre.docx')).toBe('docx');
    expect(depositDownloadUrl('w1', 'd1')).toBe('/api/workspaces/w1/deposits/d1/download');
  });

  it('texte coupé à la borne, sans casser un caractère accentué', () => {
    const bytes = new TextEncoder().encode('abé'); // a b 0xC3 0xA9
    expect(decodeTextPreview(bytes, 3)).toEqual({ text: 'ab', truncated: true });
    expect(decodeTextPreview(bytes)).toEqual({ text: 'abé', truncated: false });
    expect(truncateText('abcdef', 4)).toEqual({ text: 'abcd', truncated: true });
  });

  it('CSV : UTF-8 tel quel ; sinon Windows-1252 (export Excel français)', () => {
    expect(decodeCsv(new TextEncoder().encode('nom;ville\nÉlise;Orléans'))).toBe('nom;ville\nÉlise;Orléans');
    // « Élise;Orléans » en Windows-1252 : É = 0xC9, é = 0xE9.
    const cp1252 = new Uint8Array([0xc9, 0x6c, 0x69, 0x73, 0x65, 0x3b, 0x4f, 0x72, 0x6c, 0xe9, 0x61, 0x6e, 0x73]);
    expect(parseCsv(decodeCsv(cp1252)).rows).toEqual([['Élise', 'Orléans']]);
  });

  it('date xlsx : lue en UTC (jamais la veille), l’heure seulement si elle n’est pas minuit', () => {
    expect(cellText(new Date(Date.UTC(2026, 0, 1)))).toBe('01/01/2026');
    expect(cellText(new Date(Date.UTC(2026, 0, 1, 14, 30)))).toBe('01/01/2026 14:30');
    expect(cellText(new Date(Date.UTC(2026, 0, 1, 14, 30, 5)))).toBe('01/01/2026 14:30:05');
    expect(cellText(new Date(NaN))).toBe('');
  });

  describe('dans la boîte d’aperçu', () => {
    let data = { workspaceId: 'w1', depositId: 'd1', path: 'a/notes.txt', size: 100 };
    const http = (): HttpTestingController => TestBed.inject(HttpTestingController);

    beforeEach(() => {
      TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(),
          { provide: MAT_DIALOG_DATA, useFactory: () => data }],
      });
    });

    afterEach(() => http().match(() => true));

    async function settle(fixture: { detectChanges: () => void; whenStable: () => Promise<unknown> }): Promise<void> {
      for (let i = 0; i < 6; i++) {
        fixture.detectChanges();
        await fixture.whenStable();
        await new Promise((resolve) => setTimeout(resolve, 20));
      }
      fixture.detectChanges();
    }

    it('un texte : en <pre>, jamais de HTML interprété', async () => {
      data = { workspaceId: 'w1', depositId: 'd1', path: 'a/notes.txt', size: 100 };
      const fixture = TestBed.createComponent(DepositPreviewDialogComponent);
      fixture.detectChanges();
      http().expectOne('/api/workspaces/w1/deposits/d1/content').flush(new Blob(['<b>gras</b> et é'], { type: 'text/plain' }));
      await settle(fixture);
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('pre.dp__text')?.textContent).toBe('<b>gras</b> et é');
      expect(el.querySelector('pre.dp__text b')).toBeNull();
    });

    it('un docx : son texte brut par mammoth, en <pre>', async () => {
      const { default: JSZip } = await import('jszip');
      const zip = new JSZip();
      zip.file('[Content_Types].xml', '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        + '<Default Extension="xml" ContentType="application/xml"/>'
        + '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>');
      zip.file('_rels/.rels', '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>');
      zip.file('word/document.xml', '<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        + '<w:body><w:p><w:r><w:t>Bonjour &lt;b&gt;Élise&lt;/b&gt;</w:t></w:r></w:p></w:body></w:document>');
      const docx = await zip.generateAsync({ type: 'blob' });
      data = { workspaceId: 'w1', depositId: 'd1', path: 'a/lettre.docx', size: docx.size };
      const fixture = TestBed.createComponent(DepositPreviewDialogComponent);
      fixture.detectChanges();
      http().expectOne('/api/workspaces/w1/deposits/d1/content').flush(docx);
      await settle(fixture);
      const pre = (fixture.nativeElement as HTMLElement).querySelector('pre.dp__text');
      expect(pre?.textContent).toContain('Bonjour <b>Élise</b>');
      expect(pre?.querySelector('b')).toBeNull();
    });

    it('un docx illisible : mammoth chargé au clic, l’échec est dit', async () => {
      data = { workspaceId: 'w1', depositId: 'd1', path: 'a/lettre.docx', size: 100 };
      const fixture = TestBed.createComponent(DepositPreviewDialogComponent);
      fixture.detectChanges();
      http().expectOne('/api/workspaces/w1/deposits/d1/content').flush(new Blob(['pas un docx']));
      await settle(fixture);
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Ce document n’a pas pu être lu');
    });

    it('plus de 20 Mo : aucun aperçu demandé, [Télécharger] passe par la route en flux', async () => {
      data = { workspaceId: 'w1', depositId: 'd1', path: 'a/film.mp4', size: MAX_PREVIEW_BYTES + 1 };
      const fixture = TestBed.createComponent(DepositPreviewDialogComponent);
      fixture.detectChanges();
      http().expectNone('/api/workspaces/w1/deposits/d1/content');
      const el: HTMLElement = fixture.nativeElement;
      expect(el.querySelector('.dp__too-large')).not.toBeNull();
      const save = spyOn(HTMLAnchorElement.prototype, 'click');
      (el.querySelector('.dp__download') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(fixture.componentInstance.downloading()).toBeTrue();
      http().expectOne('/api/workspaces/w1/deposits/d1/download').flush(new Blob(['octets']));
      await settle(fixture);
      expect(save).toHaveBeenCalled();
      expect(fixture.componentInstance.downloading()).toBeFalse();
    });
  });
});
