import {
  emptyCounters,
  insertAtCaret,
  markerOf,
  mediaKindOf,
  nextMarker,
  removeMarker,
  translateMarkers,
  vanishedMarkers,
} from './media-markers';
import { depositFileName } from '../../core/services/deposit-file-name';

/** Marqueurs des médias collés (F-201 / SF-201-02) — fonctions pures. */
describe('media-markers (F-201 / SF-201-02)', () => {
  it('reconnaît le type d\'un média par son extension', () => {
    expect(mediaKindOf('.atelier/entrees/capture-20261016-100509.png')).toBe('image');
    expect(mediaKindOf('entrees/demo.MP4')).toBe('video');
    expect(mediaKindOf('entrees/rapport.pdf')).toBe('file');
    expect(mediaKindOf('entrees/Makefile')).toBe('file');
    expect(mediaKindOf(undefined)).toBe('file');
  });

  it('numérote de façon monotone par type', () => {
    let counters = emptyCounters();
    const a = nextMarker(counters, 'image');
    counters = a.counters;
    const b = nextMarker(counters, 'image');
    counters = b.counters;
    const c = nextMarker(counters, 'video');
    expect([a.marker, b.marker, c.marker]).toEqual(['[Image #1]', '[Image #2]', '[Vidéo #1]']);
    expect(markerOf('file', 3)).toBe('[Fichier #3]');
  });

  it('insère au curseur, en espaçant seulement contre un mot', () => {
    expect(insertAtCaret('regarde  ici', 8, '[Image #1]')).toEqual({ draft: 'regarde [Image #1] ici', caret: 18 });
    expect(insertAtCaret('regardeici', 7, '[Image #1]').draft).toBe('regarde [Image #1] ici');
    expect(insertAtCaret('', null, '[Image #1]')).toEqual({ draft: '[Image #1]', caret: 10 });
    expect(insertAtCaret('fin', undefined, '[Image #1]').draft).toBe('fin [Image #1]');
    // La sélection est remplacée.
    expect(insertAtCaret('avant XXX après', 6, '[texte collé #1]', 9).draft).toBe('avant [texte collé #1] après');
    // Curseur hors bornes : ramené dans le texte.
    expect(insertAtCaret('abc', 99, 'M').draft).toBe('abc M');
  });

  it('retire un marqueur sans laisser d\'espaces doublés', () => {
    expect(removeMarker('vois [Image #1] et la suite', '[Image #1]')).toBe('vois et la suite');
  });

  it('traduit chaque marqueur présent en référence au fichier, à sa place', () => {
    const out = translateMarkers('compare [Image #1] avec [Image #2]', [
      { marker: '[Image #1]', path: '.atelier/entrees/capture-a.png' },
      { marker: '[Image #2]', path: '.atelier/entrees/capture-b.png' },
      { marker: '[Image #3]', path: '.atelier/entrees/capture-c.png' },
    ]);
    expect(out).toBe(
      'compare [Image #1 = .atelier/entrees/capture-a.png] avec [Image #2 = .atelier/entrees/capture-b.png]',
    );
    expect(out).not.toContain('capture-c');
  });

  it('repère les pièces dont le marqueur a été effacé', () => {
    const media = [{ id: 'a', marker: '[Image #1]' }, { id: 'b', marker: '[Image #2]' }, { id: 'c' }];
    expect(vanishedMarkers('il reste [Image #2]', media).map((m) => m.id)).toEqual(['a']);
  });
});

describe('depositFileName (F-201 / SF-201-02)', () => {
  const now = new Date(2026, 9, 16, 10, 5, 9);

  it('renomme une capture générique en nom horodaté', () => {
    expect(depositFileName('image.png', now)).toBe('capture-20261016-100509.png');
    expect(depositFileName('image (2).PNG', now)).toBe('capture-20261016-100509.png');
    expect(depositFileName('blob', now)).toBe('capture-20261016-100509.png');
  });

  it('conserve un nom parlant', () => {
    expect(depositFileName('schema-archi.png', now)).toBe('schema-archi.png');
    expect(depositFileName('', now)).toBe('fichier');
  });
});
