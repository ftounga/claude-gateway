import { Injectable, computed, signal } from '@angular/core';

/** Clé unique de mémorisation du guide. Aucune donnée sensible n'y est écrite. */
const STORAGE_KEY = 'cg_atelier_guide';

/** Version de la forme mémorisée : toute autre valeur est relue comme un guide neuf. */
const STATE_VERSION = 1;

/** Les trois étapes du premier succès (F-53 / SF-53-01), dans l'ordre où elles se franchissent. */
export type AtelierGuideStep = 'project' | 'host' | 'command';

/** `active` tant que le parcours court, `dismissed` après abandon, `done` après conclusion. */
export type AtelierGuideStatus = 'active' | 'dismissed' | 'done';

/** Avancement des trois étapes. Une étape franchie ne redescend jamais. */
export type AtelierGuideSteps = Record<AtelierGuideStep, boolean>;

/** État complet du guide, tel qu'il est mémorisé dans le navigateur. */
export interface AtelierGuideState {
  status: AtelierGuideStatus;
  steps: AtelierGuideSteps;
}

const ALL_STEPS: readonly AtelierGuideStep[] = ['project', 'host', 'command'];

function freshState(): AtelierGuideState {
  return { status: 'active', steps: { project: false, host: false, command: false } };
}

function allDone(steps: AtelierGuideSteps): boolean {
  return ALL_STEPS.every((step) => steps[step]);
}

/**
 * Mémoire locale du **guide d'accueil** de l'Atelier (F-53 / SF-53-01).
 *
 * <p>Le guide accompagne le seul parcours qui compte — créer un projet, connecter son poste, voir
 * une commande aboutir — et son avancement est retenu <b>dans le navigateur</b> : aucune persistance
 * serveur n'est prévue (cadrage F-53, décision n° 5), et rien de sensible n'est écrit. Un stockage
 * indisponible (navigation privée verrouillée, quota) ne doit jamais casser l'écran : toute lecture
 * ou écriture qui lève est traitée comme un guide neuf.</p>
 */
@Injectable({ providedIn: 'root' })
export class AtelierGuideService {
  private readonly state = signal<AtelierGuideState>(this.read());

  /**
   * Vrai quand les trois étapes viennent d'être franchies <b>dans cette session</b>.
   *
   * <p>C'est ce qui distingue la conclusion — « votre poste exécute » — du cas d'un utilisateur qui
   * a déjà tout accompli auparavant : à celui-là, le guide n'a plus rien à raconter, et il ne
   * réapparaît pas.</p>
   */
  private readonly completedHere = signal(false);

  /**
   * Le dernier tour lancé sur le poste s'est arrêté sur une erreur (F-53 / SF-53-02).
   *
   * <p>État d'un <b>instant</b>, jamais mémorisé : un guide qui rouvrirait sur l'échec de la semaine
   * dernière raconterait une histoire fausse.</p>
   */
  private readonly turnFailedHere = signal(false);

  /** Avancement courant des trois étapes. */
  readonly steps = computed<AtelierGuideSteps>(() => this.state().steps);

  /** Vrai quand les trois étapes sont franchies. */
  readonly completed = computed(() => allDone(this.state().steps));

  /** Vrai quand le dernier tour lancé sur le poste a échoué, et que rien n'a abouti depuis. */
  readonly turnFailed = computed(() => this.turnFailedHere());

  /** Statut courant du guide. */
  readonly status = computed<AtelierGuideStatus>(() => this.state().status);

  /**
   * Vrai tant que le guide doit rester à l'écran : abandonné ou terminé, il ne revient jamais de
   * lui-même ; déjà accompli avant cette session, il ne s'ouvre pas.
   */
  readonly visible = computed(() => {
    const current = this.state();
    if (current.status !== 'active') {
      return false;
    }
    return this.completedHere() || !allDone(current.steps);
  });

  /** Coche une étape sur un fait réel. Une étape déjà cochée ne change rien. */
  markStep(step: AtelierGuideStep): void {
    const current = this.state();
    if (current.steps[step]) {
      return;
    }
    const next: AtelierGuideState = { ...current, steps: { ...current.steps, [step]: true } };
    if (step === 'command') {
      // Un tour vient d'aboutir : l'échec précédent n'a plus rien à dire.
      this.turnFailedHere.set(false);
    }
    this.apply(next);
    if (next.status === 'active' && allDone(next.steps)) {
      this.completedHere.set(true);
    }
  }

  /**
   * Signale que le dernier tour lancé sur le poste n'a pas abouti (F-53 / SF-53-02) : l'étape reste
   * ouverte, et le guide dit quoi faire au lieu de rester muet.
   */
  markTurnFailed(): void {
    this.turnFailedHere.set(true);
  }

  /**
   * Reprise délibérée du guide (F-53 / SF-53-02), après un abandon ou une conclusion. Les étapes
   * déjà franchies le restent ; un parcours accompli se rouvre sur sa conclusion.
   */
  reopen(): void {
    this.completedHere.set(allDone(this.state().steps));
    this.apply({ ...this.state(), status: 'active' });
  }

  /** Abandon : le guide se referme et ne réapparaît pas au rechargement. */
  dismiss(): void {
    this.completedHere.set(false);
    this.turnFailedHere.set(false);
    this.apply({ ...this.state(), status: 'dismissed' });
  }

  /** Conclusion acquittée : le guide est terminé pour de bon. */
  finish(): void {
    this.completedHere.set(false);
    this.turnFailedHere.set(false);
    this.apply({ ...this.state(), status: 'done' });
  }

  private apply(next: AtelierGuideState): void {
    this.state.set(next);
    this.write(next);
  }

  private read(): AtelierGuideState {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) {
        return freshState();
      }
      const parsed = JSON.parse(raw) as Partial<AtelierGuideState> & { version?: number };
      if (parsed?.version !== STATE_VERSION) {
        return freshState();
      }
      const steps = (parsed.steps ?? {}) as Partial<AtelierGuideSteps>;
      return {
        status:
          parsed.status === 'dismissed' || parsed.status === 'done' ? parsed.status : 'active',
        steps: {
          project: steps.project === true,
          host: steps.host === true,
          command: steps.command === true,
        },
      };
    } catch {
      // Stockage refusé ou contenu illisible : le guide repart neuf plutôt que de casser l'écran.
      return freshState();
    }
  }

  private write(state: AtelierGuideState): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ version: STATE_VERSION, ...state }));
    } catch {
      // Écriture refusée : l'avancement vaudra pour la session, pas au-delà. Rien à signaler.
    }
  }
}
