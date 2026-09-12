import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';

import { LiveTerminals } from '../models/atelier.models';
import {
  TerminalPreviewReport,
  samePreview,
} from '../../atelier/terminal/terminal-preview';
import { AuthService } from './auth.service';

/** Intervalle du battement de cœur. Aligné sur le délai de grâce du serveur (90 s). */
const HEARTBEAT_MS = 30_000;

/** Clé de l'identifiant d'onglet. `sessionStorage` : propre à l'onglet, pas au navigateur. */
const SESSION_KEY = 'cg.terminal.session';

/**
 * Délai minimal entre deux envois d'aperçu **à activité constante** (F-76 / SF-76-02).
 *
 * Un changement d'activité, lui, part **immédiatement** : « attend une autorisation » est le seul
 * état que l'utilisateur doit voir tout de suite, et le faire patienter cinq secondes de plus
 * n'économiserait rien de mesurable.
 */
export const PREVIEW_MIN_INTERVAL_MS = 5_000;

/**
 * **Le registre des terminaux vivants, vu de l'écran** (F-70 / SF-70-01).
 *
 * <p>Un terminal ouvert prend une place et la **tient** : le même appel sert à prendre et à
 * renouveler, toutes les trente secondes. Fermer l'onglet la libère. Le PO a tranché **quatre flux
 * vivants au maximum** — au-delà, un **refus explicite**, jamais une mise en veille silencieuse.</p>
 *
 * <p><b>Pourquoi pas un flux ouvert pour ça.</b> Un canal SSE par terminal pour transporter un
 * booléen consommerait, en HTTP/1.1 (le développement passe par le proxy `ng serve`), une des six
 * connexions par origine — quatre terminaux affameraient les appels ordinaires. Un battement court
 * ne coûte aucune connexion persistante et survit à une coupure : une place qu'on ne renouvelle
 * plus expire d'elle-même côté gateway.</p>
 *
 * <p><b>Le refus est le seul blocage.</b> Un 409 `terminal_limit_reached` bloque l'envoi et se dit à
 * l'écran. Toute autre panne — réseau, 500 — ne bloque **rien** : une gateway indisponible ne doit
 * pas interdire de travailler, et le battement suivant réessaiera.</p>
 */
@Injectable({ providedIn: 'root' })
export class LiveTerminalService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  /** Identifiant de **cet onglet**. Survit à un rechargement, jamais partagé avec un autre onglet. */
  private readonly sessionId = this.resolveSessionId();

  private timer: ReturnType<typeof setInterval> | null = null;
  private workspaceId: string | null = null;

  /** Dernier aperçu relevé par l'écran — c'est lui que le prochain battement emportera. */
  private preview: TerminalPreviewReport | null = null;

  /** Dernier aperçu réellement envoyé : sert à ne pas réémettre deux fois la même chose. */
  private sentPreview: TerminalPreviewReport | null = null;

  /** Instant du dernier envoi d'aperçu, pour apaiser le simple défilement de lignes. */
  private lastPreviewSentAt = 0;

  /** Envoi différé d'un aperçu trop rapproché du précédent. Annulé à l'arrêt. */
  private previewTimer: ReturnType<typeof setTimeout> | null = null;

  private readonly state = signal<LiveTerminals | null>(null);
  private readonly holding = signal(false);
  private readonly refused = signal(false);

  /** Vrai quand ce terminal tient sa place : c'est ce qui allume la pastille « connecté ». */
  readonly live = computed(() => this.holding());

  /** Vrai quand le plafond a refusé cette place — le seul cas qui bloque l'envoi. */
  readonly limitReached = computed(() => this.refused());

  /** Dernier état connu du registre, ou `null` tant que rien n'a répondu. */
  readonly registry = computed(() => this.state());

  /** Plafond en vigueur, tel que la gateway le déclare. Quatre, décision du PO. */
  readonly limit = computed(() => this.state()?.limit ?? 4);

  /** Nombre de terminaux vivants, ce compte-ci inclus. */
  readonly liveCount = computed(() => this.state()?.live ?? 0);

  /**
   * Ouvre une place pour ce projet et la tient jusqu'à {@link stop}. Rejouer l'appel sur un autre
   * projet **déplace** la place au lieu d'en consommer une seconde.
   */
  start(workspaceId: string): void {
    if (this.workspaceId === workspaceId && this.timer !== null) {
      return;
    }
    // Changer de projet, c'est changer de terminal : l'aperçu du précédent n'a plus cours, et le
    // laisser en place afficherait le `npm test` d'un autre projet sur la carte de celui-ci.
    this.stopPreviewTimer();
    this.preview = null;
    this.sentPreview = null;
    this.lastPreviewSentAt = 0;
    this.workspaceId = workspaceId;
    this.refused.set(false);
    this.beat();
    this.stopTimer();
    this.timer = setInterval(() => this.beat(), HEARTBEAT_MS);
  }

  /**
   * Libère la place et arrête le battement. Le `DELETE` part en `keepalive` : c'est ce qui lui
   * permet d'arriver alors que l'onglet se ferme, là où une requête ordinaire serait annulée.
   */
  stop(): void {
    const workspaceId = this.workspaceId;
    this.stopTimer();
    this.stopPreviewTimer();
    this.preview = null;
    this.sentPreview = null;
    this.lastPreviewSentAt = 0;
    this.workspaceId = null;
    this.holding.set(false);
    this.refused.set(false);
    if (!workspaceId) {
      return;
    }
    const token = this.auth.token();
    void fetch(
      `/api/workspaces/${workspaceId}/terminal/live?sessionId=${encodeURIComponent(this.sessionId)}`,
      {
        method: 'DELETE',
        keepalive: true,
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      },
    ).catch(() => {
      // Sans libération, la place expire seule côté gateway : rien à rattraper ici.
    });
  }

  /**
   * **Dit ce que ce terminal est en train de faire** (F-76 / SF-76-02).
   *
   * <p>L'aperçu voyage avec le battement de cœur : pas d'appel de plus, pas de canal de plus. Ce
   * que l'onglet relève ici est ce qui s'affichera sur la carte du poste et dans la tuile de
   * supervision — l'onglet qui travaille est le seul à savoir, à l'instant, ce qu'il fait.</p>
   *
   * <p><b>La cadence suit l'urgence, pas l'horloge.</b> Un <b>changement d'activité</b> part
   * immédiatement — c'est ce qui fait qu'« attend une autorisation » se voit en quelques secondes
   * plutôt qu'en trente, et c'est précisément ce qui a manqué le 2026-09-08 (F-47). Un simple
   * défilement de lignes, lui, est apaisé : il ne presse personne.</p>
   *
   * <p>Et l'envoi immédiat part d'ici, c'est-à-dire du gestionnaire d'événement du flux — pas
   * d'une minuterie. Un onglet en arrière-plan voit ses minuteries ralenties par le navigateur ;
   * il continue en revanche de traiter ce qui arrive sur son flux.</p>
   */
  report(preview: TerminalPreviewReport): void {
    if (!this.workspaceId || samePreview(preview, this.preview)) {
      return;
    }
    const activityChanged = preview.activity !== this.preview?.activity;
    this.preview = preview;
    if (activityChanged) {
      this.flushPreview();
      return;
    }
    const elapsed = Date.now() - this.lastPreviewSentAt;
    if (elapsed >= PREVIEW_MIN_INTERVAL_MS) {
      this.flushPreview();
      return;
    }
    if (this.previewTimer === null) {
      this.previewTimer = setTimeout(() => {
        this.previewTimer = null;
        this.flushPreview();
      }, PREVIEW_MIN_INTERVAL_MS - elapsed);
    }
  }

  /** Rejoue immédiatement la prise de place — le bouton « Réessayer » du bandeau de refus. */
  retry(): void {
    if (this.workspaceId) {
      this.beat();
    }
  }

  // ------------------------------------------------------------------ interne

  private beat(): void {
    const workspaceId = this.workspaceId;
    if (!workspaceId) {
      return;
    }
    const preview = this.preview;
    if (preview) {
      // Le battement EMPORTE le dernier relevé connu : même sans changement d'activité, l'aperçu
      // ne vieillit jamais de plus de trente secondes.
      this.sentPreview = preview;
      this.lastPreviewSentAt = Date.now();
    }
    this.http
      .post<LiveTerminals>(`/api/workspaces/${workspaceId}/terminal/live`, {
        sessionId: this.sessionId,
        ...(preview
          ? {
              activity: preview.activity,
              activityDetail: preview.activityDetail,
              previewLines: preview.previewLines,
            }
          : {}),
      })
      .subscribe({
        next: (registry) => {
          this.state.set(registry);
          this.holding.set(true);
          this.refused.set(false);
        },
        error: (error: HttpErrorResponse) => {
          this.holding.set(false);
          if (error.status === 409) {
            // Le SEUL refus qui bloque. On relit le registre pour pouvoir NOMMER les quatre
            // terminaux à fermer : un refus qui ne dit pas quoi fermer n'est pas actionnable.
            this.refused.set(true);
            this.refresh();
            return;
          }
          // Réseau, 500, projet disparu : pas de pastille, mais rien n'est interdit.
          this.refused.set(false);
        },
      });
  }

  /** Relit le registre sans rien prendre. */
  private refresh(): void {
    this.http.get<LiveTerminals>('/api/terminals/live').subscribe({
      next: (registry) => this.state.set(registry),
      error: () => undefined,
    });
  }

  /** Envoie tout de suite le dernier relevé : c'est le battement de cœur qui le porte. */
  private flushPreview(): void {
    this.stopPreviewTimer();
    if (samePreview(this.preview, this.sentPreview)) {
      return;
    }
    this.beat();
  }

  private stopPreviewTimer(): void {
    if (this.previewTimer !== null) {
      clearTimeout(this.previewTimer);
      this.previewTimer = null;
    }
  }

  private stopTimer(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  /**
   * Identifiant d'onglet : lu dans `sessionStorage` s'il existe, créé sinon. Un rechargement
   * retrouve **sa** place plutôt que d'en consommer une seconde ; un onglet dupliqué en prend une
   * nouvelle, ce qui est exact — c'est bien un second terminal.
   *
   * <p>Un `sessionStorage` indisponible (navigation privée verrouillée, cookies bloqués) n'empêche
   * rien : l'identifiant vit alors en mémoire, et seule la survie au rechargement est perdue.</p>
   */
  private resolveSessionId(): string {
    const fresh = this.randomId();
    try {
      const stored = sessionStorage.getItem(SESSION_KEY);
      if (stored && /^[A-Za-z0-9_-]{1,64}$/.test(stored)) {
        return stored;
      }
      sessionStorage.setItem(SESSION_KEY, fresh);
    } catch {
      return fresh;
    }
    return fresh;
  }

  /** Identifiant d'onglet : alphabet volontairement étroit, accepté tel quel par la gateway. */
  private randomId(): string {
    const uuid =
      typeof crypto !== 'undefined' && 'randomUUID' in crypto
        ? crypto.randomUUID()
        : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    return uuid.replace(/[^A-Za-z0-9_-]/g, '').slice(0, 64);
  }
}
