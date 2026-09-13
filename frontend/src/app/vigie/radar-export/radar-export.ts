import { HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ExportService } from '../../core/services/export.service';
import { VigieService } from '../../core/services/vigie.service';

/**
 * **Exporter le Radar d'un client** (F-99 / SF-99-07) — le téléchargement Markdown, proposé dans la
 * Vigie et avant toute purge (retrait, clôture de mission, suppression du poste).
 */

/** Longueur maximale du nom de repli, celle de la gateway (SF-99-05). */
const FILE_NAME_MAX = 80;

/** Le nom de repli d'un export, quand la gateway ne le dit pas : `radar-<client>.md`. */
export function fallbackExportName(hostName: string | null | undefined): string {
  const slug = (hostName ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, FILE_NAME_MAX - 'radar-.md'.length)
    .replace(/-+$/g, '');
  return slug ? `radar-${slug}.md` : 'radar.md';
}

/** Le nom de fichier de `Content-Disposition`, ou le repli. */
export function exportFileName(disposition: string | null | undefined, hostName: string | null | undefined): string {
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition ?? '');
  if (match) {
    try {
      return decodeURIComponent(match[1]);
    } catch {
      return match[1];
    }
  }
  return fallbackExportName(hostName);
}

/** Télécharge l'export ; rend le nom du fichier enregistré. */
@Injectable({ providedIn: 'root' })
export class RadarExporter {
  private readonly vigie = inject(VigieService);
  private readonly files = inject(ExportService);

  download(hostId: string, hostName: string | null | undefined): Observable<string> {
    return this.vigie.exportRadar(hostId).pipe(
      map((response: HttpResponse<Blob>) => {
        const name = exportFileName(response.headers.get('Content-Disposition'), hostName);
        this.files.triggerDownload(response, name);
        return name;
      }),
    );
  }
}
