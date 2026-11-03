import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Portrait, PortraitSectionKey } from './jarvis-portrait';

/**
 * **L'entretien du portrait** (F-241 / SF-241-02, D13-D16) — le parcours vit **sur l'appareil** : questions fixes dites
 * par la voix existante, réponses captées par le STT existant, « je passe » toujours accepté, « on arrête là » garde
 * les réponses déjà données. Les réponses ne partent **ni** au fil, **ni** à la mémoire du jour, **ni** à la capture :
 * seulement, à la fin, au rangement (`POST /interview/draft`), qui n'écrit rien. Rien n'est enregistré avant
 * « Valider ».
 */

/** D14 — les dix questions du premier entretien. */
export const INTERVIEW_QUESTIONS: readonly string[] = [
  'Comment souhaitez-vous que je vous appelle ?',
  'Quel est votre métier, votre rôle aujourd’hui ?',
  'Votre parcours, en deux mots ?',
  'Qu’est-ce qui vous occupe le plus en ce moment ?',
  'Qui partage votre vie ? Vous pouvez passer.',
  'Un événement important, récent ou à venir ?',
  'Vos goûts : loisirs, sport, musique, lectures ?',
  'Quel humour vous fait rire ?',
  'Des dates que je ne dois pas oublier ?',
  'Y a-t-il des sujets que je ne dois jamais aborder ?',
];

/** D16 — les questions de la mise à jour, après les éléments « à revoir ». */
export const UPDATE_QUESTIONS: readonly string[] = [
  'Quoi de neuf côté travail ?',
  'Et côté vie personnelle ?',
  'Autre chose à retenir ?',
];

/** D13 — la proposition (une fois, puis au plus tous les 7 jours, 3 fois). */
export const INTERVIEW_OFFER = 'Puis-je vous poser une dizaine de questions pour mieux vous connaître ? Cinq minutes.';
export const INTERVIEW_INTRO = 'Très bien. Dites « je passe » quand vous voulez, « on arrête là » pour finir.';
export const INTERVIEW_UPDATE_INTRO = 'Mettons à jour votre portrait. « Je passe » ou « on arrête là » à tout moment.';
export const INTERVIEW_END = 'Merci. Je range vos réponses ; relisez-les à l’écran avant de valider.';
export const INTERVIEW_NOTHING = 'Entendu, je ne retiens rien.';

function normalize(text: string): string {
  return text.toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^a-z ]+/g, ' ')
    .replace(/\s+/g, ' ').trim().replace(/^(jarvis|monsieur) /, '');
}

/** « Je passe », « passe », « suivante »… (liste fermée). */
export function isSkip(text: string): boolean {
  return ['je passe', 'passe', 'je prefere passer', 'suivante', 'question suivante', 'joker', 'pas de reponse',
    'je ne sais pas', 'aucune idee', 'rien', 'non rien', 'pas vraiment'].includes(normalize(text));
}

/** « On arrête là », « stop », « c'est tout »… (liste fermée). */
export function isStop(text: string): boolean {
  return ['on arrete la', 'arretons la', 'arrete la', 'on arrete', 'stop', 'c est tout', 'ca suffit',
    'on s arrete la', 'fin de l entretien', 'terminons'].includes(normalize(text));
}

/** D16 — « Jarvis, mettons à jour mon portrait » (liste fermée). */
export function isInterviewCommand(text: string): boolean {
  return ['mettons a jour mon portrait', 'mets a jour mon portrait', 'mettez a jour mon portrait',
    'refaisons l entretien', 'on refait l entretien', 'mise a jour de mon portrait', 'faisons l entretien',
    'commencons l entretien'].includes(normalize(text));
}

/** D16 — les questions de mise à jour : d'abord chaque section « à revoir », puis les trois questions. */
export function updateQuestions(portrait: Portrait | null): string[] {
  const stale = (portrait?.sections ?? []).filter((s) => s.stale && s.text.trim());
  const first = stale.map((s) => `Votre fiche « ${s.title} » date de plus d’un an : est-ce toujours vrai ? ${clip(s.text)}`);
  const souvenirs = (portrait?.souvenirs ?? []).filter((m) => m.stale)
    .map((m) => `J’avais noté : « ${clip(m.text)} ». Est-ce toujours vrai ?`);
  return [...first, ...souvenirs, ...UPDATE_QUESTIONS];
}

function clip(text: string): string {
  const t = text.trim().replace(/\s+/g, ' ');
  return t.length <= 120 ? t : `${t.slice(0, 117)}…`;
}

export interface InterviewAnswer {
  question: string;
  answer: string;
}

/**
 * F-241 / SF-241-06 (E1, D1) — pendant un entretien **actif**, une réponse ne se clôt qu'après autant de silence : une
 * réponse d'entretien est un récit, avec des pauses de 2 à 3 s pour chercher ses mots (le banc `--vad` de SF-239-10
 * montre que 1,2 s coupe déjà une phrase de commande sur deux quand le PO réfléchit). Pas de relance « Autre chose ? »
 * après chaque réponse : elle doublerait les échanges et sonnerait comme un formulaire.
 */
export const INTERVIEW_END_MS = 3_500;
/** Une réponse concaténée ne dépasse pas la borne du rangement (SF-241-02 : 2 000 caractères). */
export const INTERVIEW_ANSWER_MAX = 2_000;
export const INTERVIEW_REOPENED = 'Je vous écoute.';
export const INTERVIEW_RESUME_AGAIN = 'On reprend l’entretien ? Oui ou non.';
export const INTERVIEW_SET_ASIDE = 'Entendu.';

/** E5 — « Nous en étions à la question 4 sur 10 de l'entretien. On reprend l'entretien ? » */
export function resumePrompt(position: number, total: number): string {
  return `Nous en étions à la question ${Math.min(position + 1, total)} sur ${total} de l’entretien. `
    + 'On reprend l’entretien ?';
}

/** E3 — « Jarvis », « Bonjour Jarvis », « Tu es là ? »… : un réveil ou une salutation, jamais une réponse. */
export function isWakeOnly(text: string): boolean {
  const t = text.toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/[^a-z ]+/g, ' ')
    .replace(/\s+/g, ' ').trim();
  return ['jarvis', 'ok jarvis', 'bonjour jarvis', 'bonsoir jarvis', 'salut jarvis', 'hello jarvis', 'he jarvis',
    'hey jarvis', 'coucou jarvis', 'jarvis tu es la', 'jarvis t es la', 'tu es la', 't es la', 'tu es la jarvis',
    'bonjour', 'bonsoir', 'salut', 'hello', 'coucou', 'me revoila', 'je suis la', 'je suis de retour',
    'rebonjour jarvis', 'rebonjour', 'jarvis jarvis', 'bonjour bonjour jarvis'].includes(t);
}

/** E2 — « attendez, je n'avais pas fini » : la réponse précédente se rouvre (liste fermée). */
export function isContinue(text: string): boolean {
  const said = normalize(text);
  const rest = said.replace(/^(attendez|attends|pardon|non|euh|oh) /, '');
  return said === 'attendez' || said === 'attends' || ['je n avais pas fini', 'j avais pas fini', 'je n ai pas fini',
    'j ai pas fini', 'je continue', 'laissez moi finir', 'laisse moi finir', 'pas fini', 'je n avais pas termine',
    'je n ai pas termine', 'j avais pas termine'].includes(rest);
}

/** E5 — « oui », « on reprend », « reprenons l'entretien »… : la reprise acceptée. */
export function isResumeYes(text: string): boolean {
  return ['oui', 'oui on reprend', 'on reprend', 'reprenons', 'reprends', 'reprenez', 'on continue', 'continuons',
    'oui on continue', 'oui reprenons', 'on reprend l entretien', 'reprenons l entretien', 'oui volontiers',
    'oui allons y', 'allons y', 'd accord', 'ok', 'oui bien sur', 'bien sur', 'vas y', 'allez y', 'ouais']
    .includes(normalize(text));
}

/** Ce que fait une parole pendant l'entretien. */
export type InterviewStep =
  /** la réponse est donnée : poser la question suivante. */
  | 'next'
  /** un segment gardé : la réponse continue (même question, rien à dire). */
  | 'more'
  /** la réponse précédente est rouverte (« je n'avais pas fini »). */
  | 'reopened'
  /** un réveil, une salutation : rien compté, reposer la question. */
  | 'ignored'
  /** l'entretien est fini (dernière réponse, ou « on arrête là »). */
  | 'done';

/** L'état d'un entretien en cours (sur l'appareil). */
export class JarvisInterview {
  private index = 0;
  private readonly given: InterviewAnswer[] = [];
  /** SF-241-06 (E2) — les segments de la réponse en cours, pas encore close. */
  private pending: string[] = [];
  /** SF-241-06 (E2) — la réponse précédente est rouverte : la parole suivante s'y ajoute. */
  private reopened = false;
  private paused = false;

  constructor(readonly mode: 'INITIAL' | 'UPDATE', readonly questions: readonly string[]) {}

  /** La question à dire, ou `null` à la fin. */
  current(): string | null {
    return this.index < this.questions.length ? this.questions[this.index] : null;
  }

  get position(): number {
    return this.index;
  }

  get total(): number {
    return this.questions.length;
  }

  /** SF-241-06 (E4) — suspendu (pause, veille, écran masqué) : aucune parole n'est une réponse. */
  get suspended(): boolean {
    return this.paused;
  }

  suspend(): void {
    this.paused = true;
  }

  resume(): void {
    this.paused = false;
  }

  /**
   * Une parole : réponse, segment (`cut` : l'écoute a été close par son plafond, la réponse continue), « je passe »
   * (réponse vide), « on arrête là », « je n'avais pas fini », ou un réveil / une salutation (jamais une réponse).
   */
  hear(text: string, cut = false): InterviewStep {
    const question = this.current();
    if (question === null && !this.reopened) {
      return 'done';
    }
    const said = text.trim();
    if (!said || isWakeOnly(said)) {
      return 'ignored';
    }
    if (isStop(said)) {
      this.flush();
      this.index = this.questions.length;
      return 'done';
    }
    if (this.reopened) {
      if (cut) {
        this.pending.push(said);
        return 'more';
      }
      const previous = this.given[this.given.length - 1];
      previous.answer = join([previous.answer, ...this.pending, said]);
      this.pending = [];
      this.reopened = false;
      return this.current() === null ? 'done' : 'next';
    }
    if (this.pending.length === 0 && this.given.length > 0 && isContinue(said)) {
      this.reopened = true;
      return 'reopened';
    }
    if (cut) {
      this.pending.push(said);
      return 'more';
    }
    // « je passe » après un début de réponse clôt la réponse commencée (rien n'est effacé).
    const answer = isSkip(said) ? join(this.pending) : join([...this.pending, said]);
    this.pending = [];
    this.given.push({ question: question!, answer });
    this.index++;
    return this.current() === null ? 'done' : 'next';
  }

  /** Les réponses données (les « je passe » compris, vides), avec la réponse en cours si elle a commencé. */
  answers(): InterviewAnswer[] {
    const out = this.given.map((a) => ({ ...a }));
    if (this.pending.length > 0) {
      const question = this.current();
      if (this.reopened && out.length > 0) {
        out[out.length - 1].answer = join([out[out.length - 1].answer, ...this.pending]);
      } else if (question !== null) {
        out.push({ question, answer: join(this.pending) });
      }
    }
    return out;
  }

  /** Vrai si au moins une réponse non vide a été donnée. */
  hasAnswers(): boolean {
    return this.answers().some((a) => a.answer.length > 0);
  }

  /** La réponse en cours (segments) devient la réponse de sa question. */
  private flush(): void {
    const all = this.answers();
    this.given.length = 0;
    this.given.push(...all);
    this.pending = [];
    this.reopened = false;
  }
}

function join(parts: string[]): string {
  const text = parts.map((p) => p.trim()).filter(Boolean).join(' ');
  return text.length <= INTERVIEW_ANSWER_MAX ? text : text.slice(0, INTERVIEW_ANSWER_MAX);
}

export interface InterviewDraft {
  sections: Partial<Record<PortraitSectionKey, string>>;
  souvenirs: { text: string; sensitive: boolean; source: 'ENTRETIEN' }[];
}

export interface InterviewOffer {
  offer: boolean;
  offers: number;
}

@Injectable({ providedIn: 'root' })
export class JarvisInterviewClient {
  private readonly http = inject(HttpClient);

  offer(): Observable<InterviewOffer> {
    return this.http.get<InterviewOffer>('/api/jarvis/portrait/interview');
  }

  offered(): Observable<InterviewOffer> {
    return this.http.post<InterviewOffer>('/api/jarvis/portrait/interview/offered', {});
  }

  draft(answers: InterviewAnswer[]): Observable<InterviewDraft> {
    return this.http.post<InterviewDraft>('/api/jarvis/portrait/interview/draft', { answers });
  }
}
