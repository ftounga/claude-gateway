import type { Alignment } from './jarvis-alignment';

export type { Alignment } from './jarvis-alignment';

/**
 * F-250 / SF-250-03 — **les visèmes** : du texte aligné (un temps de début et de fin par caractère, tel que la voix
 * ElevenLabs le fournit dans la même requête que l'audio) à la **forme de la bouche** du visage de Jarvis.
 *
 * <p>Fichier <b>pur</b> (ni `three`, ni Angular) : règles graphème → visème françaises simples et déterministes
 * (cadrage §3.1), co-articulation par noyaux pondérés, lèvres <b>en avance de 30 ms</b> sur le son. Les cas non
 * couverts (liaisons, mots étrangers) ne cassent rien : le minutage reste celui de la voix, seule la forme est
 * approximative.</p>
 */

/** 13 visèmes + silence (cadrage §3.1). */
export type VisemeId = 'SIL' | 'PBM' | 'FV' | 'A' | 'E' | 'I' | 'O' | 'OU' | 'U' | 'EU' | 'DTN' | 'SZ' | 'CHJ' | 'KGR';

/** Les quatre paramètres de la bouche, envoyés en uniformes au visage. */
export interface MouthShape {
  /** Ouverture de la mâchoire (0-1). */
  open: number;
  /** Étirement (−1 resserré … +1 étiré). */
  wide: number;
  /** Arrondi des lèvres (0-1). */
  round: number;
  /** Fermeture des lèvres (0-1) : M B P. */
  press: number;
}

export interface VisemeShape extends MouthShape {
  /** Poids dans la co-articulation : une fermeture M B P l'emporte sur ses voisines. */
  dominance: number;
  /** Ce que fait la bouche, en mots (télémétrie, banc). */
  label: string;
}

/** La table du cadrage §3.1. */
export const VISEMES: Readonly<Record<VisemeId, VisemeShape>> = {
  SIL: { open: 0.02, wide: 0, round: 0, press: 0.15, dominance: 0.5, label: 'bouche au repos' },
  PBM: { open: 0, wide: 0, round: 0.05, press: 1, dominance: 4, label: 'lèvres closes' },
  FV: { open: 0.12, wide: 0.15, round: 0, press: 0.35, dominance: 2.2, label: 'lèvre sous les dents' },
  A: { open: 0.9, wide: 0.15, round: 0, press: 0, dominance: 1, label: 'grande ouverture' },
  E: { open: 0.5, wide: 0.45, round: 0, press: 0, dominance: 1, label: 'ouverte, étirée' },
  I: { open: 0.22, wide: 0.9, round: 0, press: 0, dominance: 1.1, label: 'étirée, serrée' },
  O: { open: 0.55, wide: -0.1, round: 0.7, press: 0, dominance: 1.1, label: 'arrondie' },
  OU: { open: 0.18, wide: -0.25, round: 1, press: 0, dominance: 1.6, label: 'arrondie, avancée' },
  U: { open: 0.15, wide: -0.2, round: 0.95, press: 0, dominance: 1.5, label: 'arrondie, serrée' },
  EU: { open: 0.32, wide: 0, round: 0.6, press: 0, dominance: 1.1, label: 'mi-ouverte, arrondie' },
  DTN: { open: 0.24, wide: 0.25, round: 0, press: 0, dominance: 0.6, label: 'langue aux dents' },
  SZ: { open: 0.09, wide: 0.55, round: 0, press: 0, dominance: 0.9, label: 'dents serrées' },
  CHJ: { open: 0.22, wide: -0.05, round: 0.65, press: 0, dominance: 1.2, label: 'arrondie, sifflante' },
  KGR: { open: 0.38, wide: 0.1, round: 0, press: 0, dominance: 0.5, label: 'gorge, langue en arrière' },
};


/** Un événement de la frise : un visème tenu de `t0` à `t1` (secondes). */
export interface VisemeEvent {
  v: VisemeId;
  t0: number;
  t1: number;
}

/** Les lèvres se voient avant de s'entendre : avance de 30 ms (au-delà de 45 ms de retard, l'œil le remarque). */
export const LIP_LEAD_S = 0.03;
/** Élargissement des noyaux de co-articulation (cadrage : demi-durée + 40 ms). */
const SPREAD_S = 0.04;
/** Au-delà de cette distance, un événement ne pèse plus (noyau < e⁻⁹). */
const REACH_S = 0.35;

const VOWELS = 'aeiouyàâäéèêëîïôöûùüœ';
const isVowel = (ch: string | undefined): boolean => !!ch && VOWELS.includes(ch);
const LETTER = /[a-zà-ÿœç]/;

/** Graphèmes multiples, du plus long au plus court (essayés dans cet ordre). */
const MULTI = ['eaux', 'eau', 'ain', 'aim', 'ein', 'oin', 'œu', 'oeu', 'ou', 'où', 'oû', 'oi', 'au', 'ai', 'aî', 'ei',
  'eu', 'an', 'am', 'en', 'em', 'on', 'om', 'in', 'im', 'un', 'um', 'ch', 'gn', 'ph', 'qu', 'gu', 'ss', 'll', 'mm', 'nn',
  'tt', 'rr', 'pp', 'cc', 'ff'];
const NASAL = new Set(['ain', 'aim', 'ein', 'oin', 'an', 'am', 'en', 'em', 'on', 'om', 'in', 'im', 'un', 'um']);
const G2V: Readonly<Record<string, readonly VisemeId[]>> = {
  eaux: ['O'], eau: ['O'], au: ['O'], ou: ['OU'], 'où': ['OU'], 'oû': ['OU'], oi: ['OU', 'A'], ai: ['E'], 'aî': ['E'],
  ei: ['E'], eu: ['EU'], 'œu': ['EU'], oeu: ['EU'],
  an: ['A'], am: ['A'], en: ['A'], em: ['A'], on: ['O'], om: ['O'], in: ['E'], im: ['E'], ain: ['E'], aim: ['E'],
  ein: ['E'], un: ['E'], um: ['E'], oin: ['OU', 'E'],
  ch: ['CHJ'], gn: ['DTN'], ph: ['FV'], qu: ['KGR'], gu: ['KGR'], ss: ['SZ'], ll: ['DTN'], mm: ['PBM'], nn: ['DTN'],
  tt: ['DTN'], rr: ['KGR'], pp: ['PBM'], cc: ['KGR'], ff: ['FV'],
  a: ['A'], 'à': ['A'], 'â': ['A'], 'ä': ['A'], e: ['E'], 'é': ['E'], 'è': ['E'], 'ê': ['E'], 'ë': ['E'], i: ['I'],
  'î': ['I'], 'ï': ['I'], y: ['I'], o: ['O'], 'ô': ['O'], 'ö': ['O'], u: ['U'], 'û': ['U'], 'ù': ['U'], 'ü': ['U'],
  'œ': ['EU'], p: ['PBM'], b: ['PBM'], m: ['PBM'], f: ['FV'], v: ['FV'], w: ['OU'], t: ['DTN'], d: ['DTN'], n: ['DTN'],
  l: ['DTN'], s: ['SZ'], z: ['SZ'], 'ç': ['SZ'], j: ['CHJ'], k: ['KGR'], q: ['KGR'], r: ['KGR'], x: ['KGR', 'SZ'], h: [],
};

/** Les symboles prononcés (la voix les dit, l'alignement ne les développe pas). */
const SYMBOLS: Readonly<Record<string, string>> = { '%': 'pourcent', '€': 'euros', '$': 'dollars', '&': 'et', '+': 'plus' };

const UNITS = ['zéro', 'un', 'deux', 'trois', 'quatre', 'cinq', 'six', 'sept', 'huit', 'neuf', 'dix', 'onze', 'douze',
  'treize', 'quatorze', 'quinze', 'seize', 'dixsept', 'dixhuit', 'dixneuf'];
const TENS = ['', '', 'vingt', 'trente', 'quarante', 'cinquante', 'soixante', 'soixante', 'quatrevingt', 'quatrevingt'];

/**
 * Un nombre entier en toutes lettres (sans espaces : seule la suite des sons compte). Au-delà de 999 999, chiffre par
 * chiffre (la voix ne lit de toute façon pas un identifiant comme un nombre).
 */
export function numberWords(digits: string): string {
  const n = Number(digits);
  if (!Number.isFinite(n) || digits.length > 6) {
    return digits.split('').map((d) => UNITS[Number(d)] ?? '').join('');
  }
  return spell(n);
}

function spell(n: number): string {
  if (n < 20) {
    return UNITS[n];
  }
  if (n < 100) {
    const t = Math.floor(n / 10);
    const u = n % 10;
    const base = t === 7 || t === 9 ? n - (t === 7 ? 60 : 80) : u;
    const head = TENS[t];
    if (t === 7 || t === 9) {
      return head + (base === 11 && t === 7 ? 'etonze' : spell(base));
    }
    return head + (u === 0 ? '' : u === 1 && t < 8 ? 'etun' : UNITS[u]);
  }
  if (n < 1000) {
    const h = Math.floor(n / 100);
    const rest = n % 100;
    return (h === 1 ? 'cent' : UNITS[h] + 'cent') + (rest ? spell(rest) : '');
  }
  const k = Math.floor(n / 1000);
  const rest = n % 1000;
  return (k === 1 ? 'mille' : spell(k) + 'mille') + (rest ? spell(rest) : '');
}

/**
 * Le cœur de la SF : caractères alignés → frise d'événements `{v, t0, t1}`. Pur, déterministe. Les nombres et
 * symboles sont développés en mots, leurs sons répartis sur l'intervalle des caractères qui les portent.
 */
export function visemesFromAlignment(alignment: Alignment): VisemeEvent[] {
  const { starts: S, ends: E } = alignment;
  const ch = alignment.chars.map((c) => c.toLowerCase());
  const events: VisemeEvent[] = [];
  let i = 0;
  while (i < ch.length) {
    const c = ch[i];
    if (/[0-9]/.test(c)) {
      let j = i;
      while (j < ch.length && /[0-9]/.test(ch[j])) {
        j++;
      }
      spoken(numberWords(ch.slice(i, j).join('')), S[i], E[j - 1], events);
      i = j;
      continue;
    }
    if (SYMBOLS[c]) {
      spoken(SYMBOLS[c], S[i], E[i], events);
      i++;
      continue;
    }
    if (!LETTER.test(c)) {
      if (/[,.!?;:…]/.test(c)) {
        events.push({ v: 'SIL', t0: S[i], t1: E[i] });
      }
      i++;
      continue;
    }
    let j = i;
    while (j < ch.length && LETTER.test(ch[j])) {
      j++;
    }
    word(ch.slice(i, j).join(''), (k) => S[i + k], (k) => E[i + k], events);
    i = j;
  }
  return events;
}

/** Un mot développé (nombre, symbole), réparti uniformément sur `[t0, t1]`. */
function spoken(text: string, t0: number, t1: number, events: VisemeEvent[]): void {
  const n = Math.max(1, text.length);
  const step = (t1 - t0) / n;
  word(text, (k) => t0 + k * step, (k) => t0 + (k + 1) * step, events);
}

/** Les règles graphème → visème d'un mot (minuscules), temps par caractère donnés par `start` / `end`. */
function word(w: string, start: (k: number) => number, end: (k: number) => number, events: VisemeEvent[]): void {
  let p = 0;
  while (p < w.length) {
    let g: string | null = null;
    for (const m of MULTI) {
      if (!w.startsWith(m, p)) {
        continue;
      }
      if (NASAL.has(m)) {
        // Nasale seulement si la lettre suivante n'est ni voyelle ni n / m (« minutes » → i + n).
        const next = w[p + m.length];
        if (next && (isVowel(next) || next === 'n' || next === 'm')) {
          continue;
        }
      }
      g = m;
      break;
    }
    if (!g) {
      g = w[p];
    }
    const rest = w.slice(p + g.length);
    const last = rest.length === 0;
    let vis: readonly VisemeId[] = G2V[g] ?? [];
    if (g === 'c' || g === 'g') {
      // c / g doux devant e, i, y.
      vis = /[eiyéèê]/.test(w[p + 1] ?? '') ? [g === 'c' ? 'SZ' : 'CHJ'] : ['KGR'];
    }
    if (last && w.length > 1 && 'stdxzp'.includes(g)) {
      // Finales muettes.
      vis = [];
    }
    if (g === 'e' && (last || rest === 's' || rest === 'nt') && w.length > 2) {
      // e muet en fin de mot et devant -s / -nt.
      vis = [];
    }
    if (g === 's' && p > 0 && isVowel(w[p - 1]) && isVowel(w[p + 1])) {
      vis = ['SZ'];
    }
    const t0 = start(p);
    const t1 = end(p + g.length - 1);
    for (let n = 0; n < vis.length; n++) {
      events.push({ v: vis[n], t0: t0 + ((t1 - t0) * n) / vis.length, t1: t0 + ((t1 - t0) * (n + 1)) / vis.length });
    }
    p += g.length;
  }
}

/** La forme évaluée, et le visème qui domine (télémétrie). */
export interface MouthSample extends MouthShape {
  id: VisemeId;
}

/** Un échantillon vierge (à réutiliser : {@link sampleVisemes} écrit dedans, sans allouer). */
export function mouthSample(): MouthSample {
  return { open: VISEMES.SIL.open, wide: 0, round: 0, press: VISEMES.SIL.press, id: 'SIL' };
}

/** Le premier événement susceptible de peser à `t` (recherche dichotomique ; la frise est triée par `t0`). */
function firstNear(events: readonly VisemeEvent[], t: number): number {
  let lo = 0;
  let hi = events.length;
  const from = t - REACH_S - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (events[mid].t0 < from) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  return lo;
}

/**
 * Co-articulation à l'instant `t` (secondes, horloge **audio**) : moyenne pondérée des visèmes voisins, chacun pesant
 * `dominance × exp(−((t − centre) / (demi-durée + 40 ms))²)`, lèvres en avance de {@link LIP_LEAD_S}. Écrit dans
 * `out`, n'alloue rien.
 */
export function sampleVisemes(events: readonly VisemeEvent[], t: number, out: MouthSample): MouthSample {
  const at = t + LIP_LEAD_S;
  const rest = VISEMES.SIL;
  let sum = 0.05;
  let open = rest.open * sum;
  let wide = rest.wide * sum;
  let round = rest.round * sum;
  let press = rest.press * sum;
  let best: VisemeId = 'SIL';
  let bestWeight = 0;
  for (let k = firstNear(events, at); k < events.length; k++) {
    const e = events[k];
    if (e.t0 > at + REACH_S) {
      break;
    }
    if (e.t1 < at - REACH_S) {
      continue;
    }
    const shape = VISEMES[e.v];
    const half = Math.max(0.028, (e.t1 - e.t0) / 2) + SPREAD_S;
    const d = (at - (e.t0 + e.t1) / 2) / half;
    const w = shape.dominance * Math.exp(-d * d);
    sum += w;
    open += shape.open * w;
    wide += shape.wide * w;
    round += shape.round * w;
    press += shape.press * w;
    if (w > bestWeight) {
      bestWeight = w;
      best = e.v;
    }
  }
  out.open = open / sum;
  out.wide = wide / sum;
  out.round = round / sum;
  out.press = press / sum;
  out.id = bestWeight > 0.25 ? best : 'SIL';
  return out;
}

/** Repli amplitude (voix sans alignement) : la mâchoire suit le niveau sonore, sans forme. */
export function sampleAmplitude(level: number, out: MouthSample): MouthSample {
  const l = Math.max(0, Math.min(1, level));
  out.open = Math.min(0.85, l * 1.1);
  out.wide = 0.05;
  out.round = 0;
  out.press = 0;
  out.id = 'SIL';
  return out;
}

/** Le visème de l'événement le plus proche de `t` — pour les tests et la planche (frise lisible). */
export function visemeAt(events: readonly VisemeEvent[], t: number): VisemeId {
  for (const e of events) {
    if (t >= e.t0 && t < e.t1) {
      return e.v;
    }
  }
  return 'SIL';
}
