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

/** L'état d'un entretien en cours (sur l'appareil). */
export class JarvisInterview {
  private index = 0;
  private readonly given: InterviewAnswer[] = [];

  constructor(readonly mode: 'INITIAL' | 'UPDATE', readonly questions: readonly string[]) {}

  /** La question à dire, ou `null` à la fin. */
  current(): string | null {
    return this.index < this.questions.length ? this.questions[this.index] : null;
  }

  get position(): number {
    return this.index;
  }

  /** Une parole : réponse, « je passe » (réponse vide) ou « on arrête là ». Rend `true` si l'entretien est fini. */
  hear(text: string): boolean {
    const question = this.current();
    if (question === null) {
      return true;
    }
    if (isStop(text)) {
      this.index = this.questions.length;
      return true;
    }
    this.given.push({ question, answer: isSkip(text) ? '' : text.trim() });
    this.index++;
    return this.current() === null;
  }

  /** Les réponses données (les « je passe » compris, vides). */
  answers(): InterviewAnswer[] {
    return [...this.given];
  }

  /** Vrai si au moins une réponse non vide a été donnée. */
  hasAnswers(): boolean {
    return this.given.some((a) => a.answer.length > 0);
  }
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
