import { Component, EventEmitter, Input, Output, computed, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AtelierGuideStep, AtelierGuideSteps } from '../../core/services/atelier-guide.service';

/** Une étape du guide, telle qu'elle s'affiche. */
export interface AtelierGuideStepView {
  id: AtelierGuideStep;
  title: string;
  /** Ce que l'étape demande de faire, en une phrase. */
  text: string;
  /** Libellé du bouton d'action, ou `null` quand l'étape n'a rien à ouvrir. */
  action: string | null;
}

/**
 * Les trois étapes du premier succès (F-53). L'étape « poste » ne redit rien du dialogue
 * d'appairage — vérification réseau, fiche DSI, commande de lancement vivent dans F-45, et le guide
 * s'y rend au lieu de les recopier.
 */
export const ATELIER_GUIDE_STEPS: readonly AtelierGuideStepView[] = [
  {
    id: 'project',
    title: 'Créez votre projet',
    text: "Déclarez le dossier sur lequel vous travaillez. C'est tout ce qui est demandé ici : le chemin, lui, sera donné par votre machine.",
    action: 'Créer un projet',
  },
  {
    id: 'host',
    title: 'Connectez votre poste',
    text: "L'écran de connexion vérifie l'accès réseau avant toute installation, et prépare la fiche à transmettre à votre DSI si quelque chose bloque. Une seule connexion suffit pour toute la machine.",
    action: 'Connecter mon poste',
  },
  {
    id: 'command',
    title: 'Faites exécuter une commande',
    text: "Demandez par exemple la liste des fichiers du projet. L'étape se coche quand un tour s'achève réellement sur votre machine.",
    action: null,
  },
];

/**
 * Guide d'accueil de l'Atelier (F-53 / SF-53-01) : un panneau superposé qui mène au **premier succès
 * réel** — créer un projet, connecter son poste, voir une commande aboutir.
 *
 * <p>Panneau <b>non modal</b> : les deux premières étapes s'accomplissent dans des dialogues, et un
 * guide modal se fermerait à chaque geste. Il ne porte aucune logique — il reçoit l'avancement et
 * émet des intentions ; ce sont les faits observés par l'Atelier qui cochent les étapes.</p>
 */
@Component({
  selector: 'app-atelier-guide',
  standalone: true,
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './atelier-guide.component.html',
  styleUrl: './atelier-guide.component.scss',
})
export class AtelierGuideComponent {
  /** Avancement des trois étapes. */
  @Input({ required: true }) set steps(value: AtelierGuideSteps) {
    this.stepsState.set(value);
  }

  /** Vrai quand un projet est ouvert : sans projet, les étapes 2 et 3 n'ont rien à ouvrir. */
  @Input() projectOpen = false;

  /** L'utilisateur ouvre le parcours « Sur ma machine ». */
  @Output() readonly createProject = new EventEmitter<void>();

  /** L'utilisateur ouvre le dialogue de connexion du poste (F-45 / F-48). */
  @Output() readonly connectHost = new EventEmitter<void>();

  /** L'utilisateur abandonne le guide. */
  @Output() readonly dismiss = new EventEmitter<void>();

  /** L'utilisateur acquitte la conclusion. */
  @Output() readonly finish = new EventEmitter<void>();

  private readonly stepsState = signal<AtelierGuideSteps>({
    project: false,
    host: false,
    command: false,
  });

  readonly views = ATELIER_GUIDE_STEPS;

  /** Avancement lisible depuis le gabarit. */
  readonly progress = computed(() => this.stepsState());

  /** Nombre d'étapes franchies, affiché en tête. */
  readonly doneCount = computed(
    () => ATELIER_GUIDE_STEPS.filter((step) => this.stepsState()[step.id]).length,
  );

  /** Les trois étapes sont franchies : le guide n'a plus qu'à conclure. */
  readonly completed = computed(() => this.doneCount() === ATELIER_GUIDE_STEPS.length);

  /**
   * L'étape courante est la **première non franchie**. Les autres sont repliées sur leur titre :
   * un guide qui déploie tout redevient un mur (leçon de SF-45-05).
   */
  readonly currentStep = computed<AtelierGuideStep | null>(
    () => ATELIER_GUIDE_STEPS.find((step) => !this.stepsState()[step.id])?.id ?? null,
  );

  /** Vrai si l'étape est franchie. */
  isDone(step: AtelierGuideStep): boolean {
    return this.progress()[step];
  }

  /** Vrai si l'étape est celle en cours. */
  isCurrent(step: AtelierGuideStep): boolean {
    return this.currentStep() === step;
  }

  /**
   * L'action d'une étape n'est proposée que si elle a quelque chose à ouvrir : l'étape « poste »
   * suppose un projet ouvert, faute de quoi il n'y a rien à connecter.
   */
  canAct(step: AtelierGuideStepView): boolean {
    if (!step.action) {
      return false;
    }
    return step.id === 'project' ? true : this.projectOpen;
  }

  /** Ouvre ce que l'étape courante propose. */
  act(step: AtelierGuideStepView): void {
    if (step.id === 'project') {
      this.createProject.emit();
      return;
    }
    if (step.id === 'host') {
      this.connectHost.emit();
    }
  }
}
