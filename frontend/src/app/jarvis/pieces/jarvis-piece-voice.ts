import { normalize } from '../jarvis-oral';
import { NavAction, PieceNav } from './jarvis-piece-nav';

/**
 * F-237 / SF-237-04 — les **ordres triviaux reconnus sur l'appareil, sans IA** (cadrage §5.3) : < 100 ms, 0 €, 0 jeton.
 * Liste **fermée**, phrase **entière** après normalisation (accents, casse, ponctuation) — comme `isStopCommand`.
 * Une phrase ambiguë ou plus riche (« celle sur le déploiement ») n'est **jamais** reconnue ici : elle part au cerveau
 * rapide. Rien n'est reconnu quand aucune galerie ni pièce n'est ouverte.
 */

const ORDINALS: Record<string, number> = {
  premiere: 1, premier: 1, deuxieme: 2, seconde: 2, second: 2, troisieme: 3, quatrieme: 4, cinquieme: 5,
  sixieme: 6, septieme: 7, huitieme: 8, neuvieme: 9, dixieme: 10, onzieme: 11, douzieme: 12,
};

const NUMBERS: Record<string, number> = {
  un: 1, une: 1, deux: 2, trois: 3, quatre: 4, cinq: 5, six: 6, sept: 7, huit: 8, neuf: 9, dix: 10, onze: 11,
  douze: 12,
};

const NEXT = /^(suivante|suivant|la suivante|le suivant|apres)$/;
const PREV = /^(precedente|precedent|la precedente|le precedent|avant)$/;
const PAUSE = /^(pause|mets en pause|stop la video|arrete la video)$/;
const PLAY = /^(lecture|reprends|continue)$/;
const BACK = /^(retour|reviens a la galerie|retour a la galerie)$/;
const UP = /^(remonte au chantier|vue d ensemble)$/;
const PAGE_NEXT = /^(page suivante|diapo suivante)$/;
const PAGE_PREV = /^(page precedente|diapo precedente)$/;
const PAGE_N = /^(?:page|diapo) (\d{1,3}|[a-z]+)$/;
const ZOOM_IN = /^(zoome|zoom|plus pres)$/;
const ZOOM_OUT = /^(dezoome|dezoom|recule|moins pres)$/;
const CLOSE = /^(ferme|c est bon|merci)$/;
const SELECT_ORDINAL = /^(?:la |le )?([a-z]+)$/;
const SELECT_NUMBER = /^(?:la |le )?(?:numero )?(\d{1,2}|[a-z]+)$/;

function number(word: string): number | null {
  if (/^\d+$/.test(word)) {
    return Number(word);
  }
  return NUMBERS[word] ?? null;
}

/**
 * L'action de navigation d'une phrase, ou `null` (la phrase part alors au cerveau rapide). N'agit que si une galerie
 * ou une pièce est ouverte.
 */
export function pieceVoiceCommand(said: string | null | undefined, nav: PieceNav): NavAction | null {
  if (nav.level === 'UNIVERS' || nav.level === 'CHANTIER') {
    return null;
  }
  const words = normalize(said ?? '').replace(/^jarvis\s+/, '').trim();
  if (!words) {
    return null;
  }
  if (NEXT.test(words)) {
    return { type: 'next' };
  }
  if (PREV.test(words)) {
    return { type: 'prev' };
  }
  if (PAUSE.test(words)) {
    return { type: 'pause' };
  }
  if (PLAY.test(words)) {
    return { type: 'play' };
  }
  if (BACK.test(words)) {
    return { type: 'back' };
  }
  if (UP.test(words)) {
    return { type: 'up' };
  }
  if (PAGE_NEXT.test(words)) {
    return { type: 'page', delta: 1 };
  }
  if (PAGE_PREV.test(words)) {
    return { type: 'page', delta: -1 };
  }
  const page = PAGE_N.exec(words);
  if (page) {
    const n = number(page[1]);
    return n == null ? null : { type: 'page', n };
  }
  if (ZOOM_IN.test(words)) {
    return { type: 'zoom', step: '+' };
  }
  if (ZOOM_OUT.test(words)) {
    return { type: 'zoom', step: '-' };
  }
  if (CLOSE.test(words)) {
    return { type: 'close' };
  }
  const ordinal = SELECT_ORDINAL.exec(words);
  if (ordinal && ORDINALS[ordinal[1]] != null) {
    return { type: 'select', ref: ORDINALS[ordinal[1]] };
  }
  const numbered = SELECT_NUMBER.exec(words);
  if (numbered && (words.startsWith('la ') || words.startsWith('le ') || words.startsWith('numero '))) {
    const n = number(numbered[1]);
    if (n != null) {
      return { type: 'select', ref: n };
    }
  }
  return null;
}
