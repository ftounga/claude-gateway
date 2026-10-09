import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  Output,
  SimpleChanges,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

import { AtelierAnswerRequest, AtelierQuestionAnswerEntry } from '../../core/models/atelier.models';
import { AtelierPendingQuestion } from '../atelier.types';

/**
 * La **carte de question structurée** dans le fil du terminal (F-164 / SF-164-02) — le rendu de
 * l'outil `demander` (SF-164-01, parité Claude Code `AskUserQuestion`).
 *
 * <p>Vue de **présentation** : l'état du **tour** (la question en attente, le minuteur, l'envoi HTTP)
 * vit dans {@code AtelierComponent}, exactement comme la porte d'autorisation. Ici vivent uniquement
 * les **choix en cours** de l'utilisateur (radios / cases / texte libre) et la composition de la
 * réponse, émise à l'envoi.</p>
 *
 * <p>Chaque question rend ses options en choix **simple** (radio) ou **multiple** (cases) selon
 * {@code multiSelect}, avec l'option **recommandée** repérée, et **toujours** un champ libre
 * « Autre / tape ta réponse ». Le bouton Envoyer n'est actif que lorsque **chaque** question porte au
 * moins un choix ou un texte libre — le contrat backend exige une réponse par question.</p>
 *
 * <p><b>Toujours envoyable (SF-164-07)</b> : l'option recommandée est cochée d'avance, et la barre
 * d'action (collante en bas du fil) dit combien de questions restent sans réponse et mène à la
 * première. Sans cela, sur une carte de plusieurs questions, le bouton restait grisé hors de l'écran
 * et la question expirait alors que l'utilisateur avait commencé à répondre.</p>
 */
@Component({
  selector: 'app-atelier-terminal-demande',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatIconModule],
  changeDetection: ChangeDetectionStrategy.Default,
  templateUrl: './atelier-terminal-demande.component.html',
  styleUrl: './atelier-terminal-demande.component.scss',
})
export class AtelierTerminalDemandeComponent {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** La question en attente, ou {@code null} : rien à rendre. Fournie par le parent (état du tour). */
  @Input() pending: AtelierPendingQuestion | null = null;

  /** Le compte à rebours déjà mis en forme (« Il reste 1 min … »), ou {@code null} : rien affiché. */
  @Input() countdown: string | null = null;

  /** En lecture seule (mosaïque / F-83), on **signale** la question sans jamais proposer d'y répondre. */
  @Input() readOnly = false;

  /** La réponse composée, prête pour {@code POST /chat/answer}. Émise à l'envoi. */
  @Output() answer = new EventEmitter<AtelierAnswerRequest>();

  /** `callId` déjà initialisé : sert à réarmer les choix quand une NOUVELLE question arrive (pauses répétées). */
  private initializedCallId: string | null = null;

  /** Choix simple retenu par question (index → label ; `''` si aucun). */
  single: string[] = [];
  /** Choix multiples retenus par question (index → ensemble de labels). */
  multi: Set<string>[] = [];
  /** Texte libre saisi par question (index → texte). */
  free: string[] = [];

  ngOnChanges(changes: SimpleChanges): void {
    if (!('pending' in changes)) {
      return;
    }
    const callId = this.pending?.callId ?? null;
    // Une NOUVELLE question (nouveau `callId`) réarme les choix ; la même question rejouée à l'attache
    // (`question_state`) garde ce que l'utilisateur a déjà commencé à cocher.
    if (callId !== this.initializedCallId) {
      this.initializedCallId = callId;
      const count = this.pending?.questions.length ?? 0;
      // SF-164-07 : l'option recommandée est cochée d'avance — l'utilisateur valide ou change.
      const questions = this.pending?.questions ?? [];
      this.single = Array.from({ length: count }, (_, i) =>
        questions[i]?.multiSelect ? '' : (questions[i]?.options.find((o) => o.recommended)?.label ?? ''));
      this.multi = Array.from({ length: count }, (_, i) => new Set<string>(
        questions[i]?.multiSelect ? questions[i].options.filter((o) => o.recommended).map((o) => o.label) : []));
      this.free = Array.from({ length: count }, () => '');
    }
  }

  /** Vrai quand la carte est encore interactive (en attente, pas en lecture seule, pas déjà répondue). */
  get interactive(): boolean {
    return !this.readOnly
      && this.pending?.status === 'awaiting'
      && !(this.pending?.answering ?? false);
  }

  /** Vrai si l'option est cochée pour une question à choix multiple. */
  isChecked(index: number, label: string): boolean {
    return this.multi[index]?.has(label) ?? false;
  }

  /** Coche / décoche une option d'une question à choix multiple. */
  toggleMulti(index: number, label: string, checked: boolean): void {
    const set = this.multi[index];
    if (!set) {
      return;
    }
    if (checked) {
      set.add(label);
    } else {
      set.delete(label);
    }
  }

  /** Retient (ou non) le choix simple d'une question. */
  setSingle(index: number, label: string): void {
    this.single[index] = label;
  }

  /** Met à jour le texte libre d'une question. */
  setFree(index: number, value: string): void {
    this.free[index] = value;
  }

  /** Vrai quand une question porte au moins un choix ou un texte libre non vide. */
  private hasAnswer(index: number): boolean {
    const question = this.pending?.questions[index];
    if (!question) {
      return false;
    }
    const chosen = question.multiSelect
      ? (this.multi[index]?.size ?? 0) > 0
      : (this.single[index] ?? '').length > 0;
    const other = (this.free[index] ?? '').trim().length > 0;
    return chosen || other;
  }

  /** Indices des questions encore sans réponse, dans l'ordre de la carte (SF-164-07). */
  get unanswered(): number[] {
    return (this.pending?.questions ?? []).map((_, i) => i).filter((i) => !this.hasAnswer(i));
  }

  /** Fait défiler jusqu'à la première question sans réponse et y place le focus (SF-164-07). */
  goToFirstUnanswered(): void {
    const index = this.unanswered[0];
    if (index === undefined) {
      return;
    }
    const fieldset = this.host.nativeElement.querySelectorAll<HTMLElement>('.terminal-demande-question')[index];
    if (!fieldset) {
      return;
    }
    fieldset.scrollIntoView({ behavior: 'smooth', block: 'center' });
    fieldset.querySelector<HTMLElement>('input, textarea')?.focus({ preventScroll: true });
  }

  /**
   * Le bouton Envoyer n'est actif que lorsque **chaque** question porte une réponse : le contrat
   * backend (SF-164-01) exige une entrée répondue par question, et un envoi partiel serait refusé (400).
   */
  get canSend(): boolean {
    if (!this.interactive || !this.pending) {
      return false;
    }
    return this.pending.questions.every((_, i) => this.hasAnswer(i));
  }

  /** Compose et émet la réponse : une entrée par question (choix cochés + éventuel texte libre). */
  send(): void {
    if (!this.canSend || !this.pending) {
      return;
    }
    const entries: AtelierQuestionAnswerEntry[] = this.pending.questions.map((question, i) => {
      const selected = question.multiSelect
        ? Array.from(this.multi[i] ?? [])
        : (this.single[i] ? [this.single[i]] : []);
      const other = (this.free[i] ?? '').trim();
      return {
        header: question.header,
        selected,
        ...(other.length > 0 ? { other } : {}),
      };
    });
    this.answer.emit({ callId: this.pending.callId, answers: entries });
  }
}
