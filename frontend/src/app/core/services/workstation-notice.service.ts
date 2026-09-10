import { Injectable, computed, signal } from '@angular/core';

/** Clé unique de mémorisation du rappel. Aucune donnée sensible n'y est écrite. */
const STORAGE_KEY = 'cg_workstation_notice';

/** Version de la forme mémorisée : toute autre valeur est relue comme un rappel neuf. */
const STATE_VERSION = 1;

/** Périodicités proposées, en heures. `null` = jamais. */
export const WORKSTATION_NOTICE_INTERVALS: readonly (number | null)[] = [2, 8, 24, null];

/** Périodicité par défaut, en heures (F-57, cadrage, décision 7). */
export const WORKSTATION_NOTICE_DEFAULT_HOURS = 2;

const HOUR_MS = 60 * 60 * 1000;

/** État complet du rappel, tel qu'il est mémorisé dans le navigateur. */
export interface WorkstationNoticeState {
  /** Périodicité en heures, ou `null` quand l'utilisateur a choisi « jamais ». */
  intervalHours: number | null;
  /** Instant du dernier acquittement, en millisecondes, ou `null` s'il n'y en a jamais eu. */
  acknowledgedAt: number | null;
}

function freshState(): WorkstationNoticeState {
  return { intervalHours: WORKSTATION_NOTICE_DEFAULT_HOURS, acknowledgedAt: null };
}

/**
 * Rappel périodique de journalisation (F-57 / SF-57-03).
 *
 * <p>Sur un poste d'entreprise, les commandes exécutées sont vraisemblablement journalisées par
 * l'employeur. Ce service décide **quand** le redire — 2 h par défaut, réglable, et désactivable.</p>
 *
 * <p>C'est un rappel de <b>responsabilité</b>, pas une alerte de menace : le produit ne sait pas ce
 * qui observe ce poste, et ne cherche pas à le savoir. Chercher serait de la reconnaissance de
 * défenses, le comportement même qu'un dispositif de sécurité classe comme malveillant.</p>
 *
 * <p>La mémoire est <b>locale</b> (`localStorage`), comme celle du guide d'accueil (F-53, décision
 * 5) : la périodicité dépend du poste où l'on travaille, pas du compte. Un stockage indisponible
 * (navigation privée verrouillée, quota) ne doit jamais casser l'écran — toute lecture ou écriture
 * qui lève est traitée comme un rappel neuf.</p>
 */
@Injectable({ providedIn: 'root' })
export class WorkstationNoticeService {
  private readonly state = signal<WorkstationNoticeState>(this.read());

  /** Périodicité courante, en heures, ou `null` quand le rappel est éteint. */
  readonly intervalHours = computed(() => this.state().intervalHours);

  /**
   * Le rappel est-il dû à cet instant ?
   *
   * <p>Prend l'instant en paramètre plutôt que de lire l'horloge : c'est ce qui rend la règle
   * vérifiable sans faire attendre un test deux heures.</p>
   */
  isDue(nowMs: number): boolean {
    const current = this.state();
    if (current.intervalHours === null) {
      return false;
    }
    if (current.acknowledgedAt === null) {
      return true;
    }
    return nowMs - current.acknowledgedAt >= current.intervalHours * HOUR_MS;
  }

  /** Acquittement : le rappel se referme et le compteur repart. */
  acknowledge(nowMs: number = Date.now()): void {
    this.apply({ ...this.state(), acknowledgedAt: nowMs });
  }

  /**
   * Change la périodicité.
   *
   * <p>Le compteur repart aussi : sans cela, passer de 24 h à 2 h ferait réapparaître le bandeau
   * dans la seconde, ce qui n'est pas ce qu'on demande en réglant une périodicité.</p>
   */
  setIntervalHours(hours: number | null, nowMs: number = Date.now()): void {
    const valid = hours === null || WORKSTATION_NOTICE_INTERVALS.includes(hours);
    this.apply({
      intervalHours: valid ? hours : WORKSTATION_NOTICE_DEFAULT_HOURS,
      acknowledgedAt: nowMs,
    });
  }

  private apply(next: WorkstationNoticeState): void {
    this.state.set(next);
    this.write(next);
  }

  private read(): WorkstationNoticeState {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) {
        return freshState();
      }
      const parsed = JSON.parse(raw) as Partial<WorkstationNoticeState> & { version?: number };
      if (parsed?.version !== STATE_VERSION) {
        return freshState();
      }
      return {
        intervalHours: this.readInterval(parsed.intervalHours),
        acknowledgedAt: this.readAcknowledgedAt(parsed.acknowledgedAt),
      };
    } catch {
      // Stockage refusé ou contenu illisible : rappel neuf plutôt qu'un écran cassé.
      return freshState();
    }
  }

  /** `null` est un choix légitime (« jamais ») ; toute autre valeur inattendue retombe au défaut. */
  private readInterval(value: unknown): number | null {
    if (value === null) {
      return null;
    }
    return typeof value === 'number' && WORKSTATION_NOTICE_INTERVALS.includes(value)
      ? value
      : WORKSTATION_NOTICE_DEFAULT_HOURS;
  }

  /**
   * Un horodatage postérieur à l'instant présent — horloge du poste reculée, fuseau changé — est
   * ramené à l'instant : sinon la soustraction devient négative et le bandeau ne revient jamais,
   * ou revient en boucle selon le sens de l'écart.
   */
  private readAcknowledgedAt(value: unknown): number | null {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      return null;
    }
    const now = Date.now();
    return value > now ? now : value;
  }

  private write(state: WorkstationNoticeState): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ version: STATE_VERSION, ...state }));
    } catch {
      // Écriture refusée : le réglage vaudra pour la session, pas au-delà. Rien à signaler.
    }
  }
}
