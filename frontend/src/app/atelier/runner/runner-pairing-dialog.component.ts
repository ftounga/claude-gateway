import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AtelierService } from '../../core/services/atelier.service';
import { RunnerPairingCode } from '../../core/models/atelier.models';

/** Données d'ouverture du dialogue : le projet à appairer. */
export interface RunnerPairingDialogData {
  workspaceId: string;
  workspaceName: string;
}

/** Chemin d'exemple affiché tant que l'utilisateur n'a pas saisi la racine de son projet. */
export const DEFAULT_WORKSPACE_PATH = '/chemin/vers/le/projet';

/** Commande de construction du fat-jar, proposée quand la gateway ne publie pas de binaire. */
export const RUNNER_BUILD_COMMAND = './mvnw -pl runner package';

/** Préfixe sous lequel l'API est servie — le même que celui utilisé par tous les appels du front. */
const API_PREFIX = '/api';

/** Nom du fichier téléchargé, aligné sur le `Content-Disposition` du backend (SF-38-03). */
const JAR_FILENAME = 'claude-runner.jar';

/**
 * Écran d'appairage d'une machine (F-38 / SF-38-06). Trois étapes dans un seul dialogue :
 * <b>un code</b> à usage unique, <b>le binaire</b> du runner, <b>la commande</b> à coller.
 *
 * <p>Trois partis pris, tous dictés par le comportement réel du backend :</p>
 * <ul>
 *   <li><b>Le code est éphémère et à usage unique</b> (TTL 5 min, {@code app.runner.pairing-code-ttl}) :
 *       il n'existe que dans cette instance de composant, n'est jamais rechargé ni persisté, et
 *       disparaît de l'écran à l'expiration. Un code déjà consommé n'est donc jamais ré-affiché.</li>
 *   <li><b>Un 404 sur le téléchargement est un état normal</b>, pas une panne : {@code app.runner.jar-path}
 *       est vide par défaut (le jar n'est pas empaqueté dans l'image), et l'écran bascule alors sur
 *       « binaire non publié » + la commande de construction, sans erreur technique.</li>
 *   <li><b>Le chemin du projet ne quitte pas le navigateur</b> : il ne sert qu'à composer la commande
 *       affichée. L'envoyer au backend divulguerait l'arborescence de la machine pour rien.</li>
 * </ul>
 */
@Component({
  selector: 'app-runner-pairing-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './runner-pairing-dialog.component.html',
  styleUrl: './runner-pairing-dialog.component.scss',
})
export class RunnerPairingDialogComponent implements OnDestroy {
  private readonly atelier = inject(AtelierService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject<MatDialogRef<RunnerPairingDialogComponent>>(MatDialogRef);
  readonly data = inject<RunnerPairingDialogData>(MAT_DIALOG_DATA);

  /** Code d'appairage en cours, ou `null` : jamais rechargé, jamais ré-affiché après expiration. */
  readonly pairingCode = signal<RunnerPairingCode | null>(null);

  /** Génération en vol : le bouton reste inerte le temps de l'aller-retour. */
  readonly generating = signal(false);

  /** Message d'erreur de génération affiché dans le dialogue, ou `null`. */
  readonly generationError = signal<string | null>(null);

  /** Téléchargement du jar en vol. */
  readonly downloading = signal(false);

  /**
   * Vrai quand la gateway a répondu **404** : le binaire n'est pas publié ici. État de déploiement
   * normal — on montre la commande de construction plutôt qu'une erreur.
   */
  readonly jarUnavailable = signal(false);

  /** Racine du projet sur la machine, saisie par l'utilisateur ; sert seulement à la commande. */
  readonly workspacePath = signal('');

  /** Secondes restantes avant expiration du code, recalculées chaque seconde. */
  readonly secondsLeft = signal(0);

  private countdown: ReturnType<typeof setInterval> | null = null;

  readonly buildCommand = RUNNER_BUILD_COMMAND;

  /**
   * URL de la gateway à passer au runner : l'origine réelle de cette page, **suivie du préfixe
   * d'API**.
   *
   * <p>Le `/api` n'est pas décoratif : le runner compose ses appels en `{gateway}/runner/pair` et
   * `{gateway}/runner/ws`, or l'API vit derrière ce préfixe (`context-path` du backend, et route
   * d'ingress). Sans lui, la requête d'appairage atteint le serveur du **frontend**, qui répond
   * `405` sur un POST vers une route d'application — une erreur d'autant plus déroutante qu'elle
   * ressemble à une panne de la gateway.</p>
   *
   * <p>L'origine reste lue de la page, jamais devinée ni codée en dur : seul le préfixe est
   * constant, et il l'est déjà partout ailleurs dans ce frontend.</p>
   */
  readonly gatewayUrl =
    typeof window !== 'undefined' ? `${window.location.origin}${API_PREFIX}` : '';

  /** Vrai tant que le code affiché est exploitable (généré et non expiré). */
  readonly codeUsable = computed(() => this.pairingCode() !== null && this.secondsLeft() > 0);

  /** Vrai quand un code a été généré puis a expiré sans être utilisé. */
  readonly codeExpired = computed(() => this.pairingCode() !== null && this.secondsLeft() <= 0);

  /** Compte à rebours au format `m:ss`. */
  readonly countdownLabel = computed(() => {
    const total = Math.max(0, this.secondsLeft());
    const minutes = Math.floor(total / 60);
    const seconds = total % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  });

  /**
   * Commande à coller sur la machine. Tant qu'aucun code n'est utilisable, un marqueur explicite
   * prend sa place : mieux vaut une commande visiblement incomplète qu'une commande qui échouera
   * silencieusement à l'appairage.
   */
  readonly runCommand = computed(() => {
    const path = this.workspacePath().trim() || DEFAULT_WORKSPACE_PATH;
    const code = this.codeUsable() ? this.pairingCode()!.code : '<code-appairage>';
    return `java -jar ${JAR_FILENAME} --gateway ${this.gatewayUrl}`
      + ` --workspace ${path} --code ${code}`;
  });

  /** Demande un nouveau code d'appairage ; remplace celui affiché, le cas échéant. */
  generateCode(): void {
    if (this.generating()) {
      return;
    }
    this.generating.set(true);
    this.generationError.set(null);
    this.atelier.createRunnerPairingCode(this.data.workspaceId).subscribe({
      next: (code) => {
        this.generating.set(false);
        this.pairingCode.set(code);
        this.startCountdown();
      },
      error: (err: unknown) => {
        this.generating.set(false);
        this.pairingCode.set(null);
        this.stopCountdown();
        this.generationError.set(
          err instanceof HttpErrorResponse && err.status === 404
            ? 'Projet introuvable.'
            : "Le code d'appairage n'a pas pu être généré. Veuillez réessayer.",
        );
      },
    });
  }

  /**
   * Télécharge le fat-jar. Un **404** bascule l'écran sur « binaire non publié sur cette gateway »
   * (état normal de déploiement) ; tout autre échec est une vraie erreur et le dit.
   */
  downloadJar(): void {
    if (this.downloading()) {
      return;
    }
    this.downloading.set(true);
    this.atelier.downloadRunnerJar().subscribe({
      next: (blob) => {
        this.downloading.set(false);
        this.jarUnavailable.set(false);
        this.saveBlob(blob);
      },
      error: (err: unknown) => {
        this.downloading.set(false);
        if (err instanceof HttpErrorResponse && err.status === 404) {
          // Pas une panne : le jar n'est simplement pas déposé sur cette gateway.
          this.jarUnavailable.set(true);
          return;
        }
        this.snackBar.open('Le téléchargement du runner a échoué.', 'Fermer', {
          duration: 4000,
          panelClass: 'snack-error',
        });
      },
    });
  }

  /** Copie un texte dans le presse-papiers, avec un repli lisible si l'API est indisponible. */
  copy(text: string, label: string): void {
    const clipboard = navigator.clipboard;
    if (!clipboard || typeof clipboard.writeText !== 'function') {
      this.snackBar.open('Copie impossible : sélectionnez le texte manuellement.', 'Fermer', {
        duration: 4000,
      });
      return;
    }
    clipboard.writeText(text).then(
      () => this.snackBar.open(`${label} copié.`, 'Fermer', { duration: 2000 }),
      () =>
        this.snackBar.open('Copie impossible : sélectionnez le texte manuellement.', 'Fermer', {
          duration: 4000,
        }),
    );
  }

  close(): void {
    this.dialogRef.close();
  }

  ngOnDestroy(): void {
    this.stopCountdown();
  }

  /** Déclenche l'enregistrement du blob téléchargé sous le nom attendu par la commande affichée. */
  private saveBlob(blob: Blob): void {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = JAR_FILENAME;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  /** (Re)démarre le compte à rebours du code affiché. */
  private startCountdown(): void {
    this.stopCountdown();
    this.tick();
    this.countdown = setInterval(() => this.tick(), 1000);
  }

  private tick(): void {
    const code = this.pairingCode();
    if (!code) {
      this.secondsLeft.set(0);
      return;
    }
    const remaining = Math.floor((new Date(code.expiresAt).getTime() - Date.now()) / 1000);
    this.secondsLeft.set(Number.isFinite(remaining) ? Math.max(0, remaining) : 0);
    if (this.secondsLeft() <= 0) {
      this.stopCountdown();
    }
  }

  private stopCountdown(): void {
    if (this.countdown !== null) {
      clearInterval(this.countdown);
      this.countdown = null;
    }
  }
}
