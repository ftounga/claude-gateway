import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';

import {
  DEPOSIT_MAX_BYTES,
  depositErrorOf,
  depositFileProblem,
  isoWithOffset,
  localInputValue,
  sendInChunks,
  titleFromFileName,
} from './radar-deposit';

/** Déposer un enregistrement — fonctions pures (F-104 / SF-104-04). */
describe('radar-deposit', () => {
  it('valide le fichier : extension, vide, 500 Mio', () => {
    expect(depositFileProblem({ name: 'Réunion.M4A', size: 10 })).toBeNull();
    expect(depositFileProblem({ name: 'rapport.pdf', size: 10 })).toContain('audio ou vidéo');
    expect(depositFileProblem({ name: 'a.mp3', size: 0 })).toContain('vide');
    expect(depositFileProblem({ name: 'a.mp4', size: DEPOSIT_MAX_BYTES + 1 })).toContain('500 Mio');
    expect(depositFileProblem(null)).toContain('Choisissez');
  });

  it('propose le titre et la date, et rend la date avec son décalage', () => {
    expect(titleFromFileName('point_mfa_salle.m4a')).toBe('point mfa salle');
    const local = localInputValue(new Date(2026, 8, 12, 10, 5).getTime());
    expect(local).toBe('2026-09-12T10:05');
    const iso = isoWithOffset(local);
    expect(iso).toMatch(/^2026-09-12T10:05:00[+-]\d{2}:\d{2}$/);
    expect(new Date(iso as string).getTime()).toBe(new Date(2026, 8, 12, 10, 5).getTime());
    expect(isoWithOffset('hier')).toBeNull();
  });

  it('envoie par morceaux, dans l\'ordre, avec la progression', async () => {
    const file = new Blob([new Uint8Array(25)]);
    const offsets: number[] = [];
    const sizes: number[] = [];
    const progress: number[] = [];
    const complete = await sendInChunks(file, 10, (offset, chunk) => {
      offsets.push(offset);
      sizes.push(chunk.size);
      return of({ received: offset + chunk.size });
    }, (sent) => progress.push(sent), () => false);

    expect(complete).toBeTrue();
    expect(offsets).toEqual([0, 10, 20]);
    expect(sizes).toEqual([10, 10, 5]);
    expect(progress).toEqual([10, 20, 25]);
  });

  it('retente une fois un morceau sur erreur réseau ; pas sur un refus ; s\'arrête sur annulation', async () => {
    const file = new Blob([new Uint8Array(20)]);
    let calls = 0;
    const flaky = (): Observable<unknown> => (++calls === 1
      ? throwError(() => new HttpErrorResponse({ status: 0 })) : of({}));
    expect(await sendInChunks(file, 10, flaky, () => undefined, () => false)).toBeTrue();
    expect(calls).toBe(3);

    const refused = () => throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'Le poste a reçu 0 octet(s)' } }));
    await expectAsync(sendInChunks(file, 10, refused, () => undefined, () => false)).toBeRejected();

    let sent = 0;
    const cancelled = await sendInChunks(file, 10, () => { sent++; return of({}); }, () => undefined, () => sent >= 1);
    expect(cancelled).toBeFalse();
    expect(sent).toBe(1);
  });

  it('dit les échecs', () => {
    expect(depositErrorOf(new HttpErrorResponse({ status: 0 }))).toContain('connexion');
    expect(depositErrorOf(new HttpErrorResponse({ status: 409, error: { message: 'Poste hors ligne.' } }))).toBe('Poste hors ligne.');
  });
});
