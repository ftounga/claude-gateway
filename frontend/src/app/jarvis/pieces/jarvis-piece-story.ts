import { Piece, PieceKind } from './jarvis-piece.models';

/**
 * F-237 / SF-237-10 — **le diaporama narré** (« raconte-moi le chantier 3 », cadrage §8.3) : une pièce = une phrase de
 * Jarvis, faite **uniquement** de ce qui est déjà écrit (§11) ; la pièce suivante arrive **quand la phrase se
 * termine** (fin de la voix), jamais sur une minuterie ; une vidéo se lit sur son extrait (chapitre ou 15 s) puis le
 * récit reprend ; « pause » fige, « suivante » saute, « ferme » arrête. Ordre du récit : besoin → entrées → étapes
 * prouvées → productions → envois. Aucun appel modèle : 0 €.
 */

/** Durée au plus d'un extrait vidéo / audio dans le récit. */
export const STORY_EXCERPT_S = 15;
/** Pièces au plus dans un récit (≈ 1 min). */
export const STORY_MAX = 12;

/** Le rang d'une pièce dans le récit. */
function rank(piece: Piece): number {
  if (piece.id.startsWith('need:')) {
    return 0;
  }
  if (piece.category === 'ENTREE') {
    return 1;
  }
  if (piece.id.startsWith('send:') || piece.id.startsWith('mail:')) {
    return 4;
  }
  return piece.category === 'PREUVE' ? 2 : 3;
}

function step(piece: Piece): string {
  return String(piece.meta?.step ?? '');
}

/** L'ordre du récit (stable) : besoin, entrées, preuves par étape puis date, productions, envois. */
export function storyOrder(pieces: readonly Piece[]): Piece[] {
  return [...pieces]
    .map((piece, i) => ({ piece, i }))
    .sort((a, b) => rank(a.piece) - rank(b.piece)
      || (rank(a.piece) === 2 ? step(a.piece).localeCompare(step(b.piece), 'fr', { numeric: true }) : 0)
      || (a.piece.at ?? '').localeCompare(b.piece.at ?? '')
      || a.i - b.i)
    .map((x) => x.piece)
    .slice(0, STORY_MAX);
}

const KIND_WORDS: Record<PieceKind, string> = {
  IMAGE: 'l’image', VIDEO: 'la vidéo', AUDIO: 'l’enregistrement', PDF: 'le document', PAGE: 'la page',
  SLIDES: 'la présentation', ARCHITECTURE: 'le schéma d’architecture', DIAGRAM: 'le schéma', TEXT: 'le texte',
  FILE: 'le fichier', FAIT: 'la pancarte',
};

const DAY = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'Europe/Paris' });

function clip(text: string, max: number): string {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length <= max ? t : `${t.slice(0, max - 1).trimEnd()}…`;
}

function end(sentence: string): string {
  return /[.!?…»]$/.test(sentence) ? sentence : `${sentence}.`;
}

function dated(piece: Piece): string {
  const at = piece.at ? new Date(piece.at) : null;
  return at && !Number.isNaN(at.getTime()) ? ` du ${DAY.format(at)}` : '';
}

/**
 * La phrase d'une pièce : sa description existante si elle en a une, sinon son titre et sa date — jamais ce que
 * l'image contient. Le rattachement « d'après la date » est dit.
 */
export function storyPhrase(piece: Piece, first: boolean): string {
  const caption = piece.caption?.trim() ? clip(piece.caption, 220) : null;
  const title = clip(piece.title || 'sans titre', 100);
  const deduced = piece.chantierLink === 'WINDOW' ? ' (rattaché d’après la date)' : '';
  switch (rank(piece)) {
    case 0:
      return end(`${first ? 'Au départ' : 'Le besoin'}, ${caption ?? title}`);
    case 1:
      return end(`Vous m’aviez fourni ${KIND_WORDS[piece.kind]} « ${title} »${deduced}${caption ? ` : ${caption}` : dated(piece)}`);
    case 2:
      return end(`Preuve : ${caption ?? `« ${title} »${dated(piece)}`}${deduced}`);
    case 4:
      return end(`Envoi : ${caption ?? `« ${title} »${dated(piece)}`}`);
    default:
      return end(`J’ai produit ${KIND_WORDS[piece.kind]} « ${title} »${deduced}${caption ? ` : ${caption}` : dated(piece)}`);
  }
}

/** L'extrait d'une vidéo ou d'un audio : début (chapitre en cours, sinon 0) et durée (≤ 15 s). */
export function storyExcerpt(piece: Piece): { from: number; seconds: number } | null {
  if (piece.kind !== 'VIDEO' && piece.kind !== 'AUDIO') {
    return null;
  }
  const duration = piece.meta?.durationS ?? STORY_EXCERPT_S;
  const chapters = piece.meta?.chapters ?? [];
  const from = chapters.length > 1 ? chapters[1].t : 0; // le premier chapitre utile, après l'introduction
  return { from: Math.min(from, Math.max(0, duration - 1)), seconds: Math.max(1, Math.min(STORY_EXCERPT_S, duration - from)) };
}

/** Ce que le récit fait faire à l'écran et à la voix (injecté : testable sans voix ni horloge). */
export interface StoryHooks {
  /** Met la pièce n° `index` (1..n) en héros. */
  select(index: number): void;
  /** Dit la phrase ; se résout à la fin de la voix (ou à sa coupure). */
  say(phrase: string): Promise<void>;
  /** Lit l'extrait puis se résout (lecture coupée si le récit s'arrête). */
  excerpt(index: number, from: number, seconds: number): Promise<void>;
  /** Coupe la voix en cours (« suivante », « ferme »). */
  cut(): void;
}

export type StoryState = 'running' | 'paused' | 'done' | 'stopped';

/**
 * **Le récit** : avance d'une pièce à la fin de chaque phrase (ou de chaque extrait). Une seule instance vit à la
 * fois ; `stop()` est définitif.
 */
export class StoryRunner {
  private cursor = 0;
  private state: StoryState = 'running';
  private resume: (() => void) | null = null;
  private skipped = false;

  constructor(private readonly pieces: readonly Piece[], private readonly hooks: StoryHooks) {
  }

  get status(): StoryState {
    return this.state;
  }

  /** La pièce racontée (1..n). */
  get index(): number {
    return this.cursor + 1;
  }

  async run(): Promise<StoryState> {
    while (this.cursor < this.pieces.length) {
      if (this.state === 'stopped') {
        return this.state;
      }
      if (this.state === 'paused') {
        await new Promise<void>((resolve) => (this.resume = resolve));
        continue;
      }
      const piece = this.pieces[this.cursor];
      this.skipped = false;
      this.hooks.select(this.cursor + 1);
      await this.hooks.say(storyPhrase(piece, this.cursor === 0));
      const excerpt = storyExcerpt(piece);
      if (excerpt && !this.skipped && this.status === 'running') {
        await this.hooks.excerpt(this.cursor + 1, excerpt.from, excerpt.seconds);
      }
      if (this.status === 'stopped') {
        return 'stopped';
      }
      if (this.status === 'paused' && !this.skipped) {
        continue; // « pause » pendant la phrase : la même pièce reprend à la reprise
      }
      this.cursor++;
    }
    this.state = 'done';
    return this.state;
  }

  /** « pause » : fige le récit (la phrase en cours se termine). */
  pause(): void {
    if (this.state === 'running') {
      this.state = 'paused';
    }
  }

  /** « reprends » : le récit repart de la pièce figée. */
  play(): void {
    if (this.state === 'paused') {
      this.state = 'running';
      this.resume?.();
      this.resume = null;
    }
  }

  /** « suivante » : la phrase en cours est coupée, la pièce suivante arrive. */
  next(): void {
    if (this.state === 'stopped' || this.state === 'done') {
      return;
    }
    this.skipped = true;
    this.hooks.cut();
    if (this.state === 'paused') {
      this.cursor = Math.min(this.cursor + 1, this.pieces.length);
      this.play();
    }
  }

  /** « ferme », « retour » : fin du récit. */
  stop(): void {
    this.state = 'stopped';
    this.hooks.cut();
    this.resume?.();
    this.resume = null;
  }
}
