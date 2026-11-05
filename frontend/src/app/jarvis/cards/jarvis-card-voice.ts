import { normalize } from '../jarvis-oral';

/**
 * F-248 / SF-248-06 — **les ordres vocaux des pancartes, sur l'appareil, sans IA** (cadrage §4.5) : liste **fermée**,
 * phrase **entière** après normalisation, comme `jarvis-piece-voice`. N'agit que quand un jeu de pancartes est ouvert.
 * Une phrase plus riche (« celle de Rémi ») part au cerveau rapide, qui a déjà `afficher selection` : outil inchangé.
 */
export type CardVoiceAction =
  | { type: 'close' }
  | { type: 'select'; index: number }
  | { type: 'page' }
  | { type: 'compare'; a: number; b: number }
  | { type: 'detail' };

const ORD: Record<string, number> = {
  premier: 1, premiere: 1, un: 1, '1': 1, deuxieme: 2, second: 2, seconde: 2, deux: 2, '2': 2, troisieme: 3,
  trois: 3, '3': 3, quatrieme: 4, quatre: 4, '4': 4, cinquieme: 5, cinq: 5, '5': 5, sixieme: 6, six: 6, '6': 6,
};
const N = '(premier|premiere|un|deuxieme|second|seconde|deux|troisieme|trois|quatrieme|quatre|cinquieme|cinq|sixieme|six|[1-6])';

const CLOSE = /^(ferme ca|ferme les pancartes|ferme tout|efface ca|masque ca)$/;
const SHOW = new RegExp(`^(?:montre|ouvre|affiche)(?: moi)? (?:le |la |l )?(?:numero )?${N}$`);
const PAGE = /^(la suite|les suivantes|les suivants|et les autres|montre les autres)$/;
const COMPARE = new RegExp(`^compare (?:le |la )?${N} et (?:le |la )?${N}$`);
const DETAIL = /^(en grand|plein ecran|agrandis|montre en grand|ouvre en grand)$/;

/** L'ordre d'une phrase, ou `null` (la phrase suit alors son chemin : voix des pièces, puis cerveau rapide). */
export function cardVoiceCommand(said: string | null | undefined, deckSize: number): CardVoiceAction | null {
  if (deckSize <= 0) {
    return null;
  }
  const words = normalize(said ?? '').replace(/^jarvis\s+/, '').trim();
  if (!words) {
    return null;
  }
  if (CLOSE.test(words)) {
    return { type: 'close' };
  }
  if (PAGE.test(words)) {
    return { type: 'page' };
  }
  if (DETAIL.test(words)) {
    return { type: 'detail' };
  }
  const show = SHOW.exec(words);
  if (show) {
    const index = ORD[show[1]];
    return index && index <= deckSize ? { type: 'select', index } : null;
  }
  const cmp = COMPARE.exec(words);
  if (cmp) {
    const a = ORD[cmp[1]];
    const b = ORD[cmp[2]];
    return a && b && a !== b && a <= deckSize && b <= deckSize ? { type: 'compare', a, b } : null;
  }
  return null;
}
