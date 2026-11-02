import { NavOrigin } from './jarvis-piece-nav';
import { PieceCategory, PieceKind } from './jarvis-piece.models';

/**
 * F-237 / SF-237-14 — **la mesure d'intérêt** (cadrage §19) : des compteurs par jour, aux clés d'un vocabulaire
 * **fermé** (le même que `RenderCounts.KEYS` côté passerelle), portés par le compte-rendu de rendu de l'ouverture
 * (`RENDER`, F-236). **Aucun contenu** : ni titre, ni identifiant de pièce, ni parole.
 */

export const PIECE_COUNT_KEYS = [
  // galeries ouvertes, par catégorie ; pièces montrées demandées / d'initiative
  'p_gal', 'p_ent', 'p_prf', 'p_prd', 'p_ask', 'p_pro',
  // pièces ouvertes en héros, par type
  'p_img', 'p_vid', 'p_aud', 'p_pdf', 'p_pag', 'p_sld', 'p_arc', 'p_txt', 'p_fil',
  // ordres de navigation : voix locale, cerveau rapide / Opus, toucher
  'n_loc', 'n_ai', 'n_tch',
  // vidéos lues et secondes, pages de PDF tournées
  'v_play', 'v_sec', 'pdf_pg',
  // récits, avant / après, planches, épinglages, retours vocaux
  'p_story', 'p_cmp', 'p_sheet', 'p_pin', 'p_note',
  // propositions de preuve : dites, acceptées, captures générées
  'pf_off', 'pf_ok', 'pf_gen',
  // pièce d'initiative fermée en moins de 2 s (« mal montré »)
  'p_fclose',
  // délai parole → pièce (s) : 5 classes
  'lat_0_1', 'lat_1_2', 'lat_2_3', 'lat_3_5', 'lat_5p',
] as const;

export type PieceCountKey = typeof PIECE_COUNT_KEYS[number];

/** Ce qui reçoit les compteurs (la mesure du rendu de l'ouverture). */
export type PieceCounter = (key: PieceCountKey, n?: number) => void;

/** Une fermeture plus rapide que ce délai d'une pièce d'initiative = « mal montré ». */
export const FAST_CLOSE_MS = 2000;

const CATEGORY_KEYS: Record<PieceCategory, PieceCountKey> = { ENTREE: 'p_ent', PREUVE: 'p_prf', PRODUCTION: 'p_prd' };
const KIND_KEYS: Record<PieceKind, PieceCountKey> = {
  IMAGE: 'p_img', VIDEO: 'p_vid', AUDIO: 'p_aud', PDF: 'p_pdf', PAGE: 'p_pag', SLIDES: 'p_sld',
  ARCHITECTURE: 'p_arc', DIAGRAM: 'p_arc', TEXT: 'p_txt', FILE: 'p_fil',
};

export function categoryKey(category: PieceCategory | null | undefined): PieceCountKey | null {
  return category ? CATEGORY_KEYS[category] : null;
}

export function kindKey(kind: PieceKind | null | undefined): PieceCountKey {
  return kind ? KIND_KEYS[kind] ?? 'p_fil' : 'p_fil';
}

export function originKey(origin: NavOrigin): PieceCountKey {
  return origin === 'PROACTIVE' ? 'p_pro' : 'p_ask';
}

/** La classe du délai parole → pièce (ms). */
export function latencyKey(ms: number): PieceCountKey {
  const s = Math.max(0, ms) / 1000;
  return s < 1 ? 'lat_0_1' : s < 2 ? 'lat_1_2' : s < 3 ? 'lat_2_3' : s < 5 ? 'lat_3_5' : 'lat_5p';
}

/** Une phrase du cerveau rapide qui propose de générer une preuve (« Voulez-vous que je génère la preuve ? »). */
export function isProofOffer(said: string | null | undefined): boolean {
  const s = (said ?? '').toLowerCase();
  return s.includes('?') && /preuve/.test(s) && /(g[ée]n[èe]re|produise|capture)/.test(s);
}

/** Une délégation qui génère une preuve (chaîne §6.4). */
export function isProofDemand(demande: string | null | undefined, label: string | null | undefined): boolean {
  return /capturer_preuves/.test(demande ?? '') || /preuve/i.test(label ?? '');
}
