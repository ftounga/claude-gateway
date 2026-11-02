/**
 * **Ce que Jarvis dit, et ce qu'il comprend sans le cerveau** (F-231) — fonctions pures, testées seules.
 *
 * - `oralPhrase` : la phrase à dire d'une réponse (D3) — la ligne « 🎙 » si l'agent l'a posée, sinon les deux
 *   premières phrases de prose. Le détail reste à l'écran.
 * - `subjectSwitch` : « passe sur CAGIP » change de sujet **sur l'appareil**, sans tour ni coût (D6).
 */

import { spokenWordCount } from './jarvis-turn-clock';

/** La marque de la phrase orale en tête de réponse (D3). */
export const ORAL_MARK = '🎙';

/** F-234 / SF-234-13 — la marque de la ligne « Montrer » (lue par l'écran, jamais dite). */
export const SHOW_LINE_MARK = '🖥';

/** Au-delà, la phrase orale est coupée : Jarvis ne lit pas un compte rendu. */
export const MAX_ORAL_CHARS = 400;

/** La phrase à dire d'une réponse, ou une chaîne vide si la réponse n'a rien de lisible à voix haute. */
export function oralPhrase(reply: string | null | undefined): string {
  return oralLine(reply).phrase;
}

/**
 * F-240 / SF-240-02 — la phrase à dire d'une réponse et si l'écran l'a coupée (D5 : au-delà de
 * {@link MAX_SPOKEN_WORDS} mots, seule sa première phrase est dite ; le reste est à l'étage 2).
 */
export function oralLine(reply: string | null | undefined): { phrase: string; cut: boolean } {
  if (!reply) {
    return { phrase: '', cut: false };
  }
  const prose: string[] = [];
  let inFence = false;
  for (const raw of reply.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('```')) {
      inFence = !inFence;
      continue;
    }
    if (inFence || !line || line.startsWith(SHOW_LINE_MARK) || line.startsWith(INVITE_MARK)) {
      // F-234 / SF-234-13 : la ligne « 🖥 montrer: » est pour l'écran, jamais dite ; F-240 / SF-240-02 : l'invitation
      // « ↪ » se dit à part (invitationOf), après la phrase.
      continue;
    }
    if (line.startsWith(ORAL_MARK)) {
      const guarded = guardOral(cutOral(clean(line.slice(ORAL_MARK.length))));
      return { phrase: guarded.text, cut: guarded.cut };
    }
    // Les tableaux et les lignes de code ne se lisent pas à voix haute.
    if (line.startsWith('|') || line.startsWith('    ')) {
      continue;
    }
    prose.push(clean(line));
  }
  const text = prose.filter((l) => l.length > 0).join(' ');
  const guarded = guardOral(cutOral(firstSentences(text, 2)));
  return { phrase: guarded.text, cut: guarded.cut };
}

/** F-240 / SF-240-02 (D6) — la marque de l'invitation ciblée : dite après la ligne 🎙, jamais montrée. */
export const INVITE_MARK = '↪';

/** F-240 / SF-240-02 (D5) — au-delà, la ligne dite est coupée à sa première phrase (filet ; la consigne dit 30). */
export const MAX_SPOKEN_WORDS = 35;

/** F-240 / SF-240-02 (D6) — au-delà, une invitation n'en est plus une (la consigne en demande 15 au plus). */
export const MAX_INVITE_WORDS = 20;

/**
 * F-240 / SF-240-02 (D5) — **la garde de longueur** : une ligne de plus de {@link MAX_SPOKEN_WORDS} mots n'est dite
 * que par sa première phrase (`cut` vrai) ; une phrase unique trop longue est dite telle quelle (rien à couper).
 */
export function guardOral(text: string): { text: string; cut: boolean } {
  if (spokenWordCount(text) <= MAX_SPOKEN_WORDS) {
    return { text, cut: false };
  }
  const first = firstSentences(text, 1);
  return first && first.length < text.trim().length ? { text: first, cut: true } : { text, cut: false };
}

/**
 * F-240 / SF-240-02 (D6) — **une invitation générique** (« voulez-vous plus de détails ? », « je peux développer si
 * vous le souhaitez ») : écartée sur l'appareil, quelle que soit sa formulation exacte (même règle que la garde
 * `isDetailOffer` du cerveau rapide, élargie). Une invitation ciblée NOMME un point : elle passe.
 */
export function isGenericInvitation(text: string | null | undefined): boolean {
  const n = normalize(text ?? '').replace(/\bmonsieur\b/g, '').replace(/\s+/g, ' ').trim();
  if (!n) {
    return true;
  }
  return /^(je peux|je puis|puis je) (vous )?(en )?(detailler|donner le detail|preciser|developper|vous en dire plus|en dire plus|entrer dans le detail|continuer)/.test(n)
    || /^(souhaitez vous|voulez vous|desirez vous|vous voulez) (le|les|plus de|davantage de|d autres|des) (detail|precision|information|explication)/.test(n)
    || /^(souhaitez vous|voulez vous|desirez vous|vous voulez) (en savoir plus|que je (detaille|developpe|precise|continue|vous en dise plus|poursuive)|la suite|le reste)/.test(n)
    || /^(autre chose|d autres questions|une autre question|des questions)\b/.test(n)
    || (n.includes('detail') && n.includes('si vous le souhaitez'))
    || /\b(plus de details|en savoir plus|le detail complet|davantage de details)\b/.test(n);
}

/**
 * F-240 / SF-240-02 (D6) — **l'invitation ciblée** d'une réponse : sa ligne « ↪ » (nettoyée), ou `null` quand il n'y
 * en a pas, qu'elle est générique, trop longue, sans question, ou que la phrase dite demande une confirmation
 * (« Vous confirmez ? » : jamais d'invitation par-dessus).
 */
export function invitationOf(reply: string | null | undefined): string | null {
  if (!reply) {
    return null;
  }
  let inFence = false;
  let invite: string | null = null;
  for (const raw of reply.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('```')) {
      inFence = !inFence;
      continue;
    }
    if (!inFence && line.startsWith(INVITE_MARK)) {
      invite = clean(line.slice(INVITE_MARK.length));
      break;
    }
  }
  return acceptInvitation(invite, oralPhrase(reply));
}

/** F-240 / SF-240-02 (D6) — l'invitation `invite` si elle peut suivre la phrase `phrase`, sinon `null`. */
export function acceptInvitation(invite: string | null, phrase: string): string | null {
  if (!invite || !invite.trim().endsWith('?') || spokenWordCount(invite) > MAX_INVITE_WORDS
      || isGenericInvitation(invite) || /(?:vous confirmez|tu confirmes)\s*\?\s*$/i.test(phrase.trim())) {
    return null;
  }
  return invite.trim();
}

/** F-240 / SF-240-02 — le texte sans ses lignes d'invitation « ↪ » (jamais montrées, comme « 🖥 »). */
export function stripInvitationLines(text: string | null | undefined): string {
  return (text ?? '').split('\n').filter((l) => !l.trim().startsWith(INVITE_MARK)).join('\n');
}

/** Le texte sans balisage Markdown ni marqueurs de l'essentiel. */
export function clean(line: string): string {
  return line
    .replace(/<<\s*\/?\s*[a-zA-Zéè]+\s*>>/g, '')
    .replace(/!\[[^\]]*]\([^)]*\)/g, '')
    .replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/^[#>*\-+\s]+/, '')
    .replace(/[*_`~]+/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/** Les `count` premières phrases de `text`. */
export function firstSentences(text: string, count: number): string {
  const sentences = text.match(/[^.!?…]+[.!?…]+["»”)]*\s*|[^.!?…]+$/g) ?? [];
  return sentences.slice(0, count).join('').trim();
}

function cutOral(text: string): string {
  if (text.length <= MAX_ORAL_CHARS) {
    return text;
  }
  const head = text.slice(0, MAX_ORAL_CHARS);
  const space = head.lastIndexOf(' ');
  return `${(space > 0 ? head.slice(0, space) : head).trimEnd()}…`;
}

// F-233 / SF-233-11 — l'accusé et les clips de réaction vivent désormais dans `jarvis-phrases.ts` (répertoire varié).
/** SF-231-03 — au-delà, la réponse est précédée d'une annonce de fin : le PO a pu passer à autre chose. */
export const LONG_TURN_MS = 30_000;

/** SF-231-03 — la phrase dite, précédée de l'annonce de fin pour un tour long. */
export function withEndAnnouncement(phrase: string, elapsedMs: number): string {
  return elapsedMs >= LONG_TURN_MS ? `C’est terminé. ${phrase}` : phrase;
}

/** SF-231-03 — « stop », « arrête », « annule » : l'ordre d'interrompre le travail en cours (dit seul). */
export function isStopCommand(said: string): boolean {
  const words = normalize(said).replace(/^jarvis\s+/, '');
  return /^(stop|arrete|arrete tout|arrete toi|arrete ca|annule|laisse tomber|interromps)$/.test(words);
}

/** F-233 / SF-233-09 (D9) — ce que vaut une parole qui a coupé Jarvis. */
export type InterruptionKind = 'ack' | 'silence' | 'cancel' | 'request';

const ACKS = /^(ok|okay|ok ok|d accord|ah d accord|mmh|mm|mmm|hum|hmm|oui|oui oui|ouais|ouais ouais|je vois|tres bien|parfait|super|compris|vas y|allez y|continue|continuez|je t ecoute|je vous ecoute|ah oui|ah ok)$/;
const SILENCES = /^(stop|chut|tais toi|taisez vous|silence|c est bon|ca suffit|ca va|merci c est bon|c est bon merci|arrete de parler|merci)$/;
const CANCELS = /^(annule|laisse tomber|arrete ca|arrete tout|interromps|arrete)$/;

/**
 * F-233 / SF-233-09 (D9) — classement **local**, sans appel modèle, de la parole qui a coupé Jarvis :
 * - `ack` (« ok », « mmh », « d'accord ») → il reprend à la phrase coupée ;
 * - `silence` (« stop », « chut », « c'est bon ») → il se tait, et rien d'autre ;
 * - `cancel` (« laisse tomber », « annule », « arrête ça ») → l'interruption du travail en cours (D11) ;
 * - `request` → tout le reste : écouté et traité comme une demande. **Dans le doute, c'est une demande** (jamais une
 *   action).
 */
export function classifyInterruption(said: string | null | undefined): InterruptionKind {
  const words = normalize(said ?? '').replace(/^jarvis\s+/, '').trim();
  if (!words) {
    return 'request';
  }
  if (ACKS.test(words)) {
    return 'ack';
  }
  if (SILENCES.test(words)) {
    return 'silence';
  }
  if (CANCELS.test(words)) {
    return 'cancel';
  }
  return 'request';
}

/** Sans accents ni casse, espaces repliés : de quoi comparer ce qui a été dit à un nom de sujet. */
export function normalize(text: string): string {
  return text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

// La préposition est exigée : « va voir les journaux » est une demande, pas un changement de sujet.
const SWITCH = /^(?:jarvis\W+)?(?:passe|bascule|va|change)\s+(?:sur|à|au|vers|dans)\s+(?:le\s+)?(?:sujet\s+)?(.+?)[\s.!?]*$/i;

/**
 * « Passe sur CAGIP » → le sujet nommé, s'il n'y en a qu'un qui correspond ; `null` sinon (la phrase part alors au
 * cerveau comme une demande ordinaire).
 */
export function subjectSwitch<T extends { id: string; name: string }>(said: string, subjects: readonly T[]): T | null {
  const match = SWITCH.exec(said.trim());
  if (!match) {
    return null;
  }
  const wanted = normalize(match[1]);
  if (!wanted) {
    return null;
  }
  const exact = subjects.filter((s) => normalize(s.name) === wanted);
  if (exact.length === 1) {
    return exact[0];
  }
  const partial = subjects.filter((s) => normalize(s.name).includes(wanted));
  return partial.length === 1 ? partial[0] : null;
}

/** F-233 / SF-233-03 — au plus autant de phrases dites par ligne 🎙 (la consigne en demande deux au plus). */
export const MAX_LINE_SENTENCES = 2;

/**
 * F-233 / SF-233-03 — les phrases de `raw` (le texte d'une ligne 🎙, sans la marque). Ligne **en cours** : seules les
 * phrases dont la ponctuation finale est suivie d'un blanc sont complètes (« 3.5 » ne coupe pas) ; ligne **close** :
 * le reste compte aussi.
 */
export function oralSentences(raw: string, complete: boolean): string[] {
  const out: string[] = [];
  let rest = raw.replace(/^\s+/, '');
  const pattern = complete ? /^([\s\S]*?[.!?…]+["»”)]*)(?:\s+|$)/ : /^([\s\S]*?[.!?…]+["»”)]*)\s+/;
  for (;;) {
    const match = pattern.exec(rest);
    if (!match) {
      break;
    }
    const sentence = cutOral(clean(match[1]));
    if (sentence) {
      out.push(sentence);
    }
    rest = rest.slice(match[0].length);
  }
  if (complete) {
    const tail = cutOral(clean(rest));
    if (tail) {
      out.push(tail);
    }
  }
  return out;
}

/**
 * F-233 / SF-233-03 (D4) — **la parole au fil de l'écriture** : reçoit les deltas du flux et rend, une seule fois
 * chacune, les phrases complètes des lignes « 🎙 » (hors blocs de code), au plus deux par ligne.
 */
export class OralStream {
  private buffer = '';
  /** Par position de ligne dans le tampon : le nombre de phrases déjà rendues. */
  private readonly emitted = new Map<number, number>();
  private readonly said: string[] = [];

  /** Ajoute un delta ; rend les phrases nouvellement complètes. */
  push(delta: string): string[] {
    return this.pushLines(delta).map((s) => s.sentence);
  }

  /** F-240 / SF-240-01 — la position de la dernière ligne 🎙 lue (la réponse finale à la fin du tour), ou `-1`. */
  get lastLine(): number {
    return this.lastOralLine;
  }

  /** F-240 / SF-240-01 — les phrases déjà dites hors de la ligne `line` (l'annonce avant outils, pour la réponse). */
  spokenOutside(line: number): readonly string[] {
    return this.tagged.filter((s) => s.line !== line).map((s) => s.sentence);
  }

  private lastOralLine = -1;
  private readonly tagged: { sentence: string; line: number }[] = [];

  /** F-240 / SF-240-01 — comme {@link push}, chaque phrase avec la position de sa ligne 🎙. */
  pushLines(delta: string): { sentence: string; line: number; invitation?: boolean }[] {
    this.buffer += delta;
    const out: { sentence: string; line: number; invitation?: boolean }[] = [];
    const lines = this.buffer.split('\n');
    let offset = 0;
    let inFence = false;
    lines.forEach((raw, index) => {
      const complete = index < lines.length - 1;
      const line = raw.trim();
      if (line.startsWith('```')) {
        inFence = !inFence;
      } else if (!inFence && line.startsWith(INVITE_MARK) && complete && !this.emitted.has(offset)) {
        // F-240 / SF-240-02 (D6) — l'invitation ciblée, dite une fois sa ligne close, après la phrase 🎙 ; écartée si
        // elle est générique ou si la phrase demande une confirmation.
        this.emitted.set(offset, 1);
        const phrase = this.tagged.filter((s) => s.line === this.lastOralLine).map((s) => s.sentence).join(' ');
        const invite = acceptInvitation(clean(line.slice(INVITE_MARK.length)), phrase);
        if (invite && !this.invited) {
          this.invited = invite;
          out.push({ sentence: invite, line: offset, invitation: true });
        }
      } else if (!inFence && line.startsWith(ORAL_MARK)) {
        this.lastOralLine = Math.max(this.lastOralLine, offset);
        const body = raw.slice(raw.indexOf(ORAL_MARK) + ORAL_MARK.length);
        let sentences = oralSentences(body, complete).slice(0, MAX_LINE_SENTENCES);
        // F-240 / SF-240-02 (D5) — la garde de longueur : au-delà de 35 mots, seule la première phrase est dite.
        if (sentences.length > 1 && spokenWordCount(sentences.join(' ')) > MAX_SPOKEN_WORDS) {
          sentences = sentences.slice(0, 1);
          this.cutLines.add(offset);
        }
        const done = this.emitted.get(offset) ?? 0;
        for (let k = done; k < sentences.length; k++) {
          out.push({ sentence: sentences[k], line: offset });
          this.tagged.push({ sentence: sentences[k], line: offset });
          this.said.push(sentences[k]);
        }
        this.emitted.set(offset, Math.max(done, sentences.length));
      }
      offset += raw.length + 1;
    });
    return out;
  }

  /** Les phrases déjà rendues. */
  get spoken(): readonly string[] {
    return this.said;
  }

  private invited: string | null = null;
  private readonly cutLines = new Set<number>();

  /** F-240 / SF-240-02 (D6) — l'invitation ciblée déjà dite au fil de l'écriture, ou `null`. */
  get invitation(): string | null {
    return this.invited;
  }

  /** F-240 / SF-240-02 (D5) — la ligne `line` a été coupée par la garde de longueur. */
  wasCut(line: number): boolean {
    return this.cutLines.has(line);
  }

  /** Ce qui reste à dire de `phrase` (la phrase orale finale) : ses phrases pas encore rendues. */
  remaining(phrase: string): string {
    const said = new Set(this.said.map((s) => normalize(s)));
    return oralSentences(phrase, true).filter((s) => !said.has(normalize(s))).join(' ');
  }
}
