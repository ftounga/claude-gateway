import { HttpErrorResponse } from '@angular/common/http';
import { Component, InjectionToken, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatRadioModule } from '@angular/material/radio';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AtelierService } from '../../core/services/atelier.service';
import { RunnerDownloadFormats, RunnerPairingCode } from '../../core/models/atelier.models';

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

/**
 * État de repli : le jar seul. C'est celui d'une gateway antérieure à F-44, et celui qu'on retient
 * quand la disponibilité des formats est illisible — le jar est le format historique, toujours servi.
 */
const NO_PACKAGE: RunnerDownloadFormats = {
  jar: true,
  windowsPackage: false,
  macosAarch64Package: false,
  macosX64Package: false,
};

/** Nom du fichier téléchargé, aligné sur le `Content-Disposition` du backend (SF-38-03). */
const JAR_FILENAME = 'claude-runner.jar';
/** Paquet autonome Windows (F-44) : le runner ET sa propre JVM. */
const WINDOWS_PACKAGE_FILENAME = 'claude-runner-windows-x64.zip';
/** Paquets autonomes macOS (F-44 / SF-44-03) : `.tar.gz`, seul format qui garde le bit exécutable. */
const MACOS_AARCH64_PACKAGE_FILENAME = 'claude-runner-macos-aarch64.tar.gz';
const MACOS_X64_PACKAGE_FILENAME = 'claude-runner-macos-x64.tar.gz';

/** Format de runner proposé par l'écran. `jar` est le seul qui suppose une JVM sur le poste. */
export type RunnerFormat = 'windows' | 'macos-aarch64' | 'macos-x64' | 'jar';

/**
 * Nom sous lequel chaque format est enregistré. Il doit correspondre au `Content-Disposition` du
 * backend **et** au nom que la commande affichée suppose : un fichier renommé au téléchargement
 * ferait échouer l'étape suivante.
 */
const PACKAGE_FILENAMES: Record<RunnerFormat, string> = {
  windows: WINDOWS_PACKAGE_FILENAME,
  'macos-aarch64': MACOS_AARCH64_PACKAGE_FILENAME,
  'macos-x64': MACOS_X64_PACKAGE_FILENAME,
  jar: JAR_FILENAME,
};

/** Système d'où la page est consultée — le seul indice fiable pour présélectionner un format. */
export type RunnerHostPlatform = 'windows' | 'macos' | 'other';

/**
 * Devine le système depuis l'`User-Agent`. Volontairement grossier : il ne sert qu'à **présélectionner**
 * une option que l'utilisateur voit et peut changer d'un clic — jamais à masquer quoi que ce soit.
 *
 * <p>iPhone et iPad sont renvoyés vers `other` : Safari sur iPad annonce `Macintosh`, et on ne fait
 * pas tourner un runner sur une tablette.</p>
 */
export function detectHostPlatform(userAgent: string): RunnerHostPlatform {
  const agent = userAgent.toLowerCase();
  if (agent.includes('iphone') || agent.includes('ipad')) {
    return 'other';
  }
  if (agent.includes('windows')) {
    return 'windows';
  }
  if (agent.includes('mac os') || agent.includes('macintosh')) {
    return 'macos';
  }
  return 'other';
}

/**
 * Système d'où la page est consultée, injecté plutôt que lu en dur : le composant n'a pas à
 * connaître `navigator`, et un test peut décrire le poste qu'il simule au lieu de maquiller
 * l'environnement du navigateur qui l'exécute.
 */
export const RUNNER_HOST_PLATFORM = new InjectionToken<RunnerHostPlatform>('RUNNER_HOST_PLATFORM', {
  providedIn: 'root',
  factory: () => detectHostPlatform(typeof navigator === 'undefined' ? '' : navigator.userAgent),
});

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
    MatRadioModule,
    MatTooltipModule,
  ],
  templateUrl: './runner-pairing-dialog.component.html',
  styleUrl: './runner-pairing-dialog.component.scss',
})
export class RunnerPairingDialogComponent implements OnDestroy {
  private readonly atelier = inject(AtelierService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject<MatDialogRef<RunnerPairingDialogComponent>>(MatDialogRef);

  /** Système d'où la page est consultée : il ne sert qu'à présélectionner un format. */
  private readonly hostPlatform = inject(RUNNER_HOST_PLATFORM);

  constructor() {
    // D3 : on demande ce qui existe AVANT de le proposer. En cas d'échec de la lecture, on retombe
    // sur le jar seul — le format historique, toujours servi : ne rien proposer serait pire.
    this.atelier.runnerDownloadFormats().subscribe({
      next: (formats) => {
        this.formats.set(formats);
        this.format.set(this.preferredFormat(formats));
      },
      error: () => {
        this.formats.set(NO_PACKAGE);
        this.format.set('jar');
      },
    });
  }
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

  /**
   * Format choisi (F-44). La valeur initiale suit le **système d'où la page est consultée**, avant
   * même de savoir ce que la gateway sert : c'est le seul moment où l'écran peut se tromper, et il
   * se corrige dès la réponse de `formats`.
   */
  readonly format = signal<RunnerFormat>(
    this.hostPlatform === 'macos' ? 'macos-aarch64' : 'windows');

  /**
   * Disponibilité des formats sur cette gateway. Lue au chargement pour **masquer** un format
   * absent plutôt que d'offrir un lien qui répondrait 404 : une gateway déployée avant F-44
   * n'empaquette aucun paquet, et doit rester utilisable avec le seul jar.
   */
  readonly formats = signal<RunnerDownloadFormats>(NO_PACKAGE);

  /** Conservé tel quel : le reste de l'écran et les tests raisonnent format par format. */
  readonly windowsPackageAvailable = computed(() => this.formats().windowsPackage);

  /** Paquet macOS Apple Silicon servi par cette gateway. */
  readonly macosAarch64Available = computed(() => this.formats().macosAarch64Package);

  /** Paquet macOS Intel servi par cette gateway. */
  readonly macosX64Available = computed(() => this.formats().macosX64Package);

  /** Vrai dès qu'un paquet autonome, quel qu'il soit, est servi : l'écran propose alors un choix. */
  readonly anyPackageAvailable = computed(
    () => this.windowsPackageAvailable() || this.macosAarch64Available() || this.macosX64Available());

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
    // Le paquet autonome s'exécute par son lanceur : il ne faut surtout pas préfixer par `java`,
    // qui rappellerait la JVM du système — celle-là même qui manque ou qui est trop ancienne.
    // Sur macOS le lanceur est un `.command` exécutable, d'où le `./` : l'archive est un `.tar.gz`
    // précisément pour que le bit exécutable survive à la décompression.
    const selected = this.selectedPackage();
    const launcher = selected === 'windows'
      ? 'claude-runner.cmd'
      : selected !== null
        ? './claude-runner.command'
        : `java -jar ${JAR_FILENAME}`;
    // Guillemets autour du chemin (F-38 / SF-38-23) : sans eux, Git Bash interprète les antislashs
    // d'un chemin Windows comme des échappements — « C:\Users\moi » arrive au runner en
    // « C:Usersmoi », que Windows résout ensuite comme un chemin RELATIF au lecteur C:. C'est le
    // deuxième obstacle rencontré par un client, juste après le prérequis Java. Les guillemets ne
    // gênent aucun shell, et suppriment le piège pour tous ceux qui copient la commande.
    return `${launcher} --gateway ${this.gatewayUrl}`
      + ` --workspace "${path}" --code ${code}`;
  });

  /**
   * Paquet autonome retenu **et réellement servi**, ou `null` quand c'est le jar qui sera
   * téléchargé. Cette double condition est le cœur de D3 : un format choisi mais absent de la
   * gateway ne doit jamais produire ni bouton ni commande.
   */
  readonly selectedPackage = computed<Exclude<RunnerFormat, 'jar'> | null>(() => {
    const format = this.format();
    if (format === 'windows') {
      return this.windowsPackageAvailable() ? 'windows' : null;
    }
    if (format === 'macos-aarch64') {
      return this.macosAarch64Available() ? 'macos-aarch64' : null;
    }
    if (format === 'macos-x64') {
      return this.macosX64Available() ? 'macos-x64' : null;
    }
    return null;
  });

  /** Vrai quand le format retenu est le paquet Windows, et qu'il est réellement disponible. */
  readonly usesWindowsPackage = computed(() => this.selectedPackage() === 'windows');

  /** Vrai quand le format retenu est l'un des deux paquets macOS, et qu'il est disponible. */
  readonly usesMacosPackage = computed(() => {
    const selected = this.selectedPackage();
    return selected === 'macos-aarch64' || selected === 'macos-x64';
  });

  /** Libellé du bouton de téléchargement, qui doit dire ce qu'on va réellement obtenir. */
  readonly downloadLabel = computed(() => {
    switch (this.selectedPackage()) {
      case 'windows':
        return 'Télécharger le runner pour Windows (~39 Mo)';
      case 'macos-aarch64':
        return 'Télécharger le runner pour Mac Apple Silicon (~39 Mo)';
      case 'macos-x64':
        return 'Télécharger le runner pour Mac Intel (~40 Mo)';
      default:
        return 'Télécharger le runner (.jar)';
    }
  });

  /**
   * Format présélectionné : celui du système d'où la page est consultée, s'il est servi ici.
   *
   * <p>Sur un Mac, c'est **Apple Silicon** — le navigateur ne sait pas distinguer les deux
   * architectures de façon fiable (Safari comme Chrome annoncent `MacIntel` sur un M3), et l'option
   * Intel reste visible d'un clic (D4). Tout ce qui n'est ni Windows ni Mac — Linux compris —
   * retombe sur le jar : ces postes ont un JDK, et 39 Mo pour en utiliser 2,5 serait absurde.</p>
   */
  private preferredFormat(formats: RunnerDownloadFormats): RunnerFormat {
    const platform = this.hostPlatform;
    if (platform === 'windows' && formats.windowsPackage) {
      return 'windows';
    }
    if (platform === 'macos') {
      if (formats.macosAarch64Package) {
        return 'macos-aarch64';
      }
      if (formats.macosX64Package) {
        return 'macos-x64';
      }
    }
    return 'jar';
  }

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
    const selected = this.selectedPackage();
    this.downloading.set(true);
    const request = selected === 'windows'
      ? this.atelier.downloadRunnerWindowsPackage()
      : selected === 'macos-aarch64'
        ? this.atelier.downloadRunnerMacosPackage('aarch64')
        : selected === 'macos-x64'
          ? this.atelier.downloadRunnerMacosPackage('x64')
          : this.atelier.downloadRunnerJar();
    request.subscribe({
      next: (blob) => {
        this.downloading.set(false);
        this.jarUnavailable.set(false);
        this.saveBlob(blob, PACKAGE_FILENAMES[selected ?? 'jar']);
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
  private saveBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
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
