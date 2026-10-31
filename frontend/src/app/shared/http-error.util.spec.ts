import { HttpErrorResponse } from '@angular/common/http';

import {
  EXCLUDE_HINT,
  GENERIC_ERROR,
  httpErrorMessage,
  httpErrorMessageAsync,
  MAX_UPLOAD_BYTES,
  oversizeMessage,
  TOO_LARGE_MESSAGE,
} from './http-error.util';

describe('httpErrorMessage', () => {
  it('renvoie le message du corps JSON backend { error, message }', () => {
    const error = new HttpErrorResponse({
      status: 400,
      error: { error: 'invalid_archive', message: "Un fichier de l'archive est trop volumineux." },
    });
    expect(httpErrorMessage(error)).toBe("Un fichier de l'archive est trop volumineux.");
  });

  it('traduit un 413 (ingress) en message « trop volumineuse » sans exposer le HTML', () => {
    const error = new HttpErrorResponse({
      status: 413,
      error: '<html><head><title>413 Request Entity Too Large</title></head></html>',
    });
    expect(httpErrorMessage(error)).toBe(TOO_LARGE_MESSAGE);
    expect(httpErrorMessage(error)).toContain(EXCLUDE_HINT);
  });

  it('parse un corps texte JSON non désérialisé', () => {
    const error = new HttpErrorResponse({
      status: 402,
      error: '{"error":"quota_exceeded","message":"Quota de consommation atteint."}',
    });
    expect(httpErrorMessage(error)).toBe('Quota de consommation atteint.');
  });

  it('retombe sur le fallback pour un corps HTML non-JSON', () => {
    const error = new HttpErrorResponse({ status: 500, error: '<html>Internal Server Error</html>' });
    expect(httpErrorMessage(error, 'Repli.')).toBe('Repli.');
  });

  it('retombe sur le fallback pour un statut 0 (réseau) sans corps', () => {
    const error = new HttpErrorResponse({ status: 0, error: null });
    expect(httpErrorMessage(error, 'Repli.')).toBe('Repli.');
  });

  it('retombe sur le fallback pour un message vide', () => {
    const error = new HttpErrorResponse({ status: 400, error: { message: '   ' } });
    expect(httpErrorMessage(error, 'Repli.')).toBe('Repli.');
  });

  it('utilise le message générique par défaut si aucun fallback fourni', () => {
    expect(httpErrorMessage('pas une HttpErrorResponse')).toBe(GENERIC_ERROR);
  });
});

describe('oversizeMessage', () => {
  it('exprime la taille en Mo et rappelle les exclusions', () => {
    const message = oversizeMessage(200 * 1024 * 1024);
    expect(message).toContain('200 Mo');
    expect(message).toContain('max 150 Mo');
    expect(message).toContain(EXCLUDE_HINT);
  });

  it('arrondit au minimum à 1 Mo pour une archive minuscule hors contexte', () => {
    expect(oversizeMessage(1024)).toContain('1 Mo');
  });

  it('MAX_UPLOAD_BYTES vaut 150 Mo', () => {
    expect(MAX_UPLOAD_BYTES).toBe(150 * 1024 * 1024);
  });
});

/** F-228 / SF-228-04 — l'erreur d'une requête blob arrive en Blob : on la lit. */
describe('httpErrorMessageAsync', () => {
  it('lit le message JSON d’un corps Blob', async () => {
    const error = new HttpErrorResponse({
      status: 503,
      error: new Blob([JSON.stringify({ error: 'deposit_unreadable', message: 'Le poste ne répond pas.' })],
        { type: 'application/json' }),
    });
    expect(await httpErrorMessageAsync(error, 'repli')).toBe('Le poste ne répond pas.');
  });

  it('ne lit jamais une page HTML en Blob : repli', async () => {
    const error = new HttpErrorResponse({ status: 502, error: new Blob(['<html>Bad gateway</html>']) });
    expect(await httpErrorMessageAsync(error, 'repli')).toBe('repli');
  });

  it('un corps déjà désérialisé : comme la version synchrone', async () => {
    const error = new HttpErrorResponse({ status: 400, error: { message: 'motif' } });
    expect(await httpErrorMessageAsync(error)).toBe('motif');
  });
});
