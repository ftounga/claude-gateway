import { offlinePiecesPhrase, showCorrection } from './jarvis-piece-store';
import { Piece } from './jarvis-piece.models';

/** F-263 / SF-263-02 — un refus d'écran se corrige à voix haute ; des pièces sur un poste éteint le disent. */
describe('Jarvis — correction d’un refus d’écran (F-263 / SF-263-02)', () => {
  it('une pièce introuvable nomme le sujet en vue', () => {
    expect(showCorrection('NOT_FOUND', 'Terminal du poste')).toBe('Pardon, je ne les trouve pas dans Terminal du poste.');
    expect(showCorrection('NOT_FOUND', null)).toBe('Pardon, je ne les trouve pas.');
  });

  it('rien à montrer se dit ; déjà montrée ou occupé reste silencieux', () => {
    expect(showCorrection('NOTHING', 'X')).toBe('Pardon, il n’y a rien à montrer.');
    expect(showCorrection('RECENT', 'X')).toBe('');
    expect(showCorrection('BUSY', 'X')).toBe('');
  });

  it('les autres refus utiles commencent par « Pardon, »', () => {
    expect(showCorrection('OUT_OF_RANGE', null)).toBe('Pardon, il n’y en a pas d’autre.');
    expect(showCorrection('NOT_IN_SCOPE', null)).toBe('Pardon, cette pièce n’est pas du sujet en cours.');
  });
});

describe('Jarvis — pièces sur un poste éteint (F-263 / SF-263-02)', () => {
  const piece = (id: string, available: Piece['available']): Piece => ({
    id, category: 'ENTREE', kind: 'IMAGE', title: id, available,
  });

  it('toutes sur le poste éteint : le dire, avec le nom du poste', () => {
    expect(offlinePiecesPhrase([piece('a', 'HOST_OFFLINE'), piece('b', 'HOST_OFFLINE')], 'CAGIP'))
      .toBe('Elles sont sur le poste CAGIP, éteint : je ne peux pas les ouvrir.');
    expect(offlinePiecesPhrase([piece('a', 'HOST_OFFLINE')], null))
      .toBe('Elles sont sur un poste éteint : je ne peux pas les ouvrir.');
  });

  it('une pièce en ligne, ou aucune pièce : rien', () => {
    expect(offlinePiecesPhrase([piece('a', 'HOST_OFFLINE'), piece('b', 'ONLINE')], 'CAGIP')).toBe('');
    expect(offlinePiecesPhrase([piece('a', undefined)], 'CAGIP')).toBe('');
    expect(offlinePiecesPhrase([], 'CAGIP')).toBe('');
  });
});
