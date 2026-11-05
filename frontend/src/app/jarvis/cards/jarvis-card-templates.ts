import { CARD_TYPES, CardTone, CardType, JarvisCard } from './jarvis-card.models';

/**
 * F-248 / SF-248-03 — **les gabarits des pancartes, côté écran** (cadrage §4.1) : des **données**, pas des composants —
 * pour chaque type, la forme (icône), le mot du type ; et la table ton → couleur de la palette §23. Ajouter un type =
 * une entrée ici, aucun composant. Un type inconnu prend `GENERIQUE`, jamais rien (garde `cardTypesWithoutTemplate`).
 */
export interface CardTemplate {
  /** La forme (glyphe) : elle dit le type, la couleur ne le dit jamais seule. */
  icon: string;
  /** Le mot du type, écrit en capitales (Space Grotesk). */
  word: string;
}

const DEFAULT_TEMPLATES: Record<CardType, CardTemplate> = {
  TICKET: { icon: '◈', word: 'Ticket' },
  MR: { icon: '⑂', word: 'Demande de fusion' },
  PIPELINE: { icon: '⟿', word: 'Pipeline' },
  DEPLOIEMENT: { icon: '⬆', word: 'Déploiement' },
  RESULTAT: { icon: '›_', word: 'Résultat' },
  RENDEZ_VOUS: { icon: '◷', word: 'Rendez-vous' },
  COURRIEL: { icon: '✉', word: 'Courriel' },
  PERSONNE: { icon: '◉', word: 'Personne' },
  ACCES: { icon: '⚿', word: 'Accès' },
  ATTENTE: { icon: '⧗', word: 'Attente' },
  GENERIQUE: { icon: '◇', word: 'Fait' },
};

/** Le registre courant (remplaçable, comme `PIECE_VIEWERS`). */
export const CARD_TEMPLATES: Record<CardType, CardTemplate> = { ...DEFAULT_TEMPLATES };

/** Le gabarit d'un type — `GENERIQUE` pour un type inconnu, jamais rien. */
export function templateFor(type: CardType | string | null | undefined): CardTemplate {
  return (type && (CARD_TEMPLATES as Record<string, CardTemplate>)[type]) || CARD_TEMPLATES.GENERIQUE
    || DEFAULT_TEMPLATES.GENERIQUE;
}

/** La garde : les types sans gabarit (doit être vide). */
export function cardTypesWithoutTemplate(): CardType[] {
  return CARD_TYPES.filter((t) => !CARD_TEMPLATES[t]);
}

/** Remet les gabarits d'origine (tests). */
export function resetCardTemplates(): void {
  for (const t of CARD_TYPES) {
    CARD_TEMPLATES[t] = DEFAULT_TEMPLATES[t];
  }
}

/** Le jeton de couleur de chaque ton (§23 en 3D ; le repli 2D garde §22 par la feuille de style). */
export const TONE_TOKENS: Readonly<Record<CardTone, string>> = {
  normal: 'var(--cg-holo, #E07B39)',
  ok: 'var(--cg-holo-green, #4CF2A0)',
  attend: 'var(--cg-holo-wait, #5FD4FF)',
  alerte: 'var(--cg-holo-red, #FF5A6E)',
  eteint: 'var(--cg-holo-off, #2A4256)',
};

const TONES: readonly CardTone[] = ['normal', 'ok', 'attend', 'alerte', 'eteint'];

/** Le ton admis (un ton inconnu devient `normal`). */
export function toneOf(card: Pick<JarvisCard, 'status'>): CardTone {
  const tone = card.status?.tone;
  return tone && TONES.includes(tone) ? tone : 'normal';
}

/** Moins de 15 min, poste connecté : « à l'instant ». */
export const FRESH_MS = 15 * 60_000;
/** Plus de 24 h : « ancien », pancarte atténuée (palier 0,45, §23). */
export const OLD_MS = 24 * 3_600_000;

const PARTS = new Intl.DateTimeFormat('fr-FR', {
  timeZone: 'Europe/Paris', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  hourCycle: 'h23',
});
const WEEKDAY = new Intl.DateTimeFormat('fr-FR', { timeZone: 'Europe/Paris', weekday: 'short' });
const DAY = new Intl.DateTimeFormat('fr-FR', { timeZone: 'Europe/Paris', day: 'numeric', month: 'short' });

function parisParts(t: number): { day: string; hour: string; minute: string } {
  const parts = PARTS.formatToParts(new Date(t));
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return { day: `${get('year')}-${get('month')}-${get('day')}`, hour: get('hour'), minute: get('minute') };
}

/** La fraîcheur d'une pancarte : le libellé du badge et le drapeau « ancien ». */
export interface CardFreshness {
  label: string;
  old: boolean;
}

/**
 * « à l'instant » (poste connecté, < 15 min), « relevé 18 h 40 » (aujourd'hui), « relevé hier 18 h 40 »,
 * « relevé lun. 18 h 40 » (cette semaine), « relevé 3 nov. 18 h 40 » ; plus de 24 h ⇒ `old`. L'heure vient de la
 * passerelle ; l'écran ne fait que la formater (heure de Paris).
 */
export function cardFreshness(observedAt: string | null | undefined, now: number, connected: boolean): CardFreshness {
  const t = observedAt ? Date.parse(observedAt) : NaN;
  if (Number.isNaN(t)) {
    return { label: '', old: false };
  }
  const age = now - t;
  if (connected && age >= 0 && age < FRESH_MS) {
    return { label: 'à l’instant', old: false };
  }
  const at = parisParts(t);
  const today = parisParts(now);
  const yesterday = parisParts(now - 86_400_000);
  const hm = `${at.hour} h ${at.minute}`;
  let when: string;
  if (at.day === today.day) {
    when = hm;
  } else if (at.day === yesterday.day) {
    when = `hier ${hm}`;
  } else if (age < 7 * 86_400_000) {
    when = `${WEEKDAY.format(new Date(t))} ${hm}`;
  } else {
    when = `${DAY.format(new Date(t))} ${hm}`;
  }
  return { label: `relevé ${when}`, old: age > OLD_MS };
}

/** Un lien rapide sûr : `http(s)://…` ou une route de l'application (`/…`, jamais `//…`) ; sinon aucun. */
export function safeLink(link: string | null | undefined): { href: string; external: boolean } | null {
  const l = (link ?? '').trim();
  if (/^https?:\/\/[^\s]+$/i.test(l)) {
    return { href: l, external: true };
  }
  if (/^\/(?!\/)[^\s]*$/.test(l)) {
    return { href: l, external: false };
  }
  return null;
}
