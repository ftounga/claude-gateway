import { HttpErrorResponse } from '@angular/common/http';
import { Component, InjectionToken, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AtelierService } from '../../core/services/atelier.service';
import {
  ProxyAssistantDialogComponent,
  ProxyAssistantDialogData,
} from './proxy-assistant-dialog.component';
import {
  RunnerDownloadFormats,
  RunnerHost,
  RunnerPairingCode,
} from '../../core/models/atelier.models';

/**
 * Données d'ouverture du dialogue : le projet à mettre en service, et — depuis F-48 / SF-48-03 — le
 * <b>poste</b> auquel il est déjà rattaché, s'il en a un.
 *
 * <p>Quand le poste est connu et connecté, il n'y a plus rien à appairer : ouvrir un projet de plus
 * sous une machine déjà en service ne coûte rien, et c'est exactement ce que F-48 est venu
 * chercher.</p>
 */
export interface RunnerPairingDialogData {
  workspaceId: string;
  workspaceName: string;
  /** Poste déjà rattaché au projet, ou `null`/absent. */
  hostId?: string | null;
  /** Chemin du projet sous la racine du poste, ou `null`/absent. */
  projectPath?: string | null;
}

/** Chemin d'exemple affiché tant que l'utilisateur n'a pas saisi la racine de son poste. */
export const DEFAULT_WORKSPACE_PATH = '/chemin/vers/vos/projets';

/**
 * Valeur de la liste des postes qui signifie « en créer un » (F-48 / SF-48-03). Une constante
 * plutôt qu'une chaîne vide : la chaîne vide est un chemin de projet légitime — la racine — et les
 * deux ne doivent jamais pouvoir se confondre.
 */
export const NEW_HOST = '__nouveau__';

/**
 * Chemins d'exemple par système (F-45 / SF-45-02). Un chemin Unix affiché sous un bouton
 * « Télécharger le runner pour Windows » invite à recopier une forme qui ne marchera pas — c'est
 * là que commence le troisième obstacle rencontré chez le client, le chemin avalé par Git Bash.
 */
export const WINDOWS_WORKSPACE_PATH = 'C:\\Users\\moi\\projets\\mon-projet';
export const MACOS_WORKSPACE_PATH = '/Users/moi/projets/mon-projet';

/**
 * Période du relevé d'état de la machine (F-45 / SF-45-02).
 *
 * <p>Le backend calcule cet état avec une tolérance de <b>90 s</b> sur le dernier heartbeat
 * (`app.runner.heartbeat.stale-after`) : interroger plus souvent ne le rendrait pas plus frais,
 * seulement plus coûteux.</p>
 */
export const RUNNER_STATUS_POLL_MS = 5000;

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
 * Chemin interrogé par le contrôle d'accès réseau proposé à l'écran (F-45 / SF-45-01).
 *
 * <p>C'est <b>exactement</b> celui que le contrôle de vol du runner interroge au démarrage
 * ({@code NetworkPreflight}, F-38 / SF-38-25) : il est public, sans authentification, et deux
 * adresses différentes autoriseraient un verdict vert à l'écran et rouge au lancement (D5).</p>
 */
export const NETWORK_CHECK_PATH = '/runner/download/formats';

/**
 * Proxy exposé par un relais local d'authentification, proposé comme remède au `407` (D2 de la
 * feature) : le relais porte l'authentification intégrée que la JVM ne sait pas porter, et n'en
 * demande aucune. `3128` est le port qu'exposent par défaut `px` comme `cntlm`.
 */
export const LOCAL_RELAY_PROXY_URL = 'http://127.0.0.1:3128';

/**
 * Commande de vérification de la sortie réseau, adaptée au système d'où la page est consultée
 * (F-45 / SF-45-01).
 *
 * <p>Deux partis pris, tous deux dictés par ce qui échoue réellement sur un poste d'entreprise :</p>
 * <ul>
 *   <li><b>`curl.exe` et non `curl` sous Windows</b> : dans PowerShell, `curl` est un <b>alias</b> de
 *       `Invoke-WebRequest`, dont les options n'ont rien à voir — la commande échouerait sur une
 *       erreur de paramètre que personne ne relierait au réseau (D3).</li>
 *   <li><b>`curl` et non `Invoke-WebRequest`</b> : ce dernier emprunte le proxy <b>système</b>, donc
 *       réussirait là où le runner échoue et masquerait la panne. `curl` lit `HTTPS_PROXY` /
 *       `HTTP_PROXY` / `NO_PROXY`, exactement ce que lit le runner (D4).</li>
 * </ul>
 */
export function networkCheckCommand(platform: RunnerHostPlatform, gatewayUrl: string): string {
  const url = `${gatewayUrl}${NETWORK_CHECK_PATH}`;
  return platform === 'windows'
    ? `curl.exe -sS -o NUL -w "%{http_code}\\n" ${url}`
    : `curl -sS -o /dev/null -w "%{http_code}\\n" ${url}`;
}

/**
 * Comment <b>retrouver</b> le proxy du poste. Le produit dit où regarder, il ne va pas chercher :
 * lire le registre ou interpréter un fichier PAC reviendrait à exécuter la configuration réseau
 * d'un poste d'entreprise. Jamais vide — un système inconnu reçoit le geste générique.
 */
export function proxyDiscoveryCommand(platform: RunnerHostPlatform): string {
  switch (platform) {
    case 'windows':
      return 'netsh winhttp show proxy';
    case 'macos':
      return 'scutil --proxy';
    default:
      return 'env | grep -i proxy';
  }
}

/** Comment <b>déclarer</b> un proxy dans le terminal courant, sur le système consulté. */
export function proxyExportCommand(platform: RunnerHostPlatform, proxyUrl: string): string {
  return platform === 'windows'
    ? `$env:HTTPS_PROXY="${proxyUrl}"`
    : `export HTTPS_PROXY=${proxyUrl}`;
}

/**
 * Étape du parcours de mise en service (F-45 / SF-45-05).
 *
 * <p>Les quatre étapes sont celles qui existaient déjà ; ce qui change est qu'une seule est
 * <b>dépliée</b> à la fois. Le dialogue avait doublé de volume avec F-45 — quatre étapes et un arbre
 * de lecture à trois branches dans une seule colonne — et se lisait moins bien qu'avant d'avoir été
 * amélioré.</p>
 */
export type PairingStep = 'network' | 'host' | 'code' | 'download' | 'launch';

/**
 * Ce que l'utilisateur déclare avoir lu dans son terminal à l'étape 1 (F-45 / SF-45-05, décision D2).
 *
 * <p>C'est une <b>déclaration</b>, pas une mesure : le navigateur ne peut pas lire le terminal, et
 * c'est la limite assumée de F-45. Mais les trois branches affichées ensemble sont, par
 * construction, fausses aux deux tiers pour celui qui les lit — il n'a obtenu qu'un seul des trois
 * résultats.</p>
 */
export type NetworkVerdict = 'unknown' | 'reachable' | 'proxy-auth' | 'no-answer';

/**
 * Libellé de l'interpréteur élu par le runner (F-38 / SF-38-27), <b>écrit en dur par valeur</b>
 * (F-45 / SF-45-05, décision D8).
 *
 * <p>La valeur vient à l'origine d'une trame du runner. Elle est déjà filtrée par liste blanche côté
 * gateway ; elle n'est en plus <b>jamais</b> rendue telle quelle. Une valeur inattendue ne produit
 * rien du tout — la conclusion omet la ligne plutôt que d'écrire « inconnu », qui se lirait comme un
 * défaut alors qu'un runner antérieur à SF-38-27 n'a simplement rien déclaré.</p>
 */
const SHELL_LABELS: Record<string, string> = {
  posix: 'bash',
  powershell: 'PowerShell',
  cmd: 'cmd.exe',
};

/** Libellé affichable de l'interpréteur déclaré, ou `null` s'il n'y en a pas à afficher. */
export function shellLabel(declared: string | null | undefined): string | null {
  return declared ? SHELL_LABELS[declared] ?? null : null;
}

/** Nom du fichier de la fiche DSI, tel qu'il arrive dans les téléchargements (F-45 / SF-45-03). */
export const IT_SHEET_FILENAME = 'runner-acces-reseau-dsi.txt';

/**
 * Fiche <b>« Pour votre DSI »</b> (F-45 / SF-45-03) : la demande d'ouverture réseau, écrite.
 *
 * <p>Chez le client du 2026-09-07, la moitié des trois heures s'est passée à <b>reconstituer</b>
 * cette demande — quel domaine, quel port, dans quel sens, et pourquoi « HTTPS est déjà ouvert » ne
 * suffisait pas. Ces informations sont connues du produit ; les faire retrouver à un utilisateur,
 * c'est lui faire deviner ce qu'on sait.</p>
 *
 * <p>Fonction <b>pure</b> et générée côté écran : tout son contenu vient de l'origine de la page ou
 * du comportement constant du runner. Un endpoint n'ajouterait qu'une route à maintenir (D1).</p>
 *
 * <p>Elle est faite pour <b>sortir de l'écran</b> — collée dans un ticket. Elle ne contient donc
 * <b>aucune</b> donnée du projet : ni code d'appairage, ni nom de projet, ni chemin du poste (D6).</p>
 *
 * @param origin origine de la page, telle que `window.location.origin`
 * @param generatedAt date de génération, injectée pour rester testable
 */
export function itDepartmentSheet(origin: string, generatedAt: Date): string {
  let host = origin;
  let port = '';
  let secure = true;
  try {
    const url = new URL(origin);
    host = url.hostname;
    secure = url.protocol === 'https:';
    // D3 : le port vient de l'origine, jamais d'une constante. `443` en dur serait faux en
    // développement, et faux le jour où la passerelle est servie ailleurs.
    port = url.port || (secure ? '443' : '80');
  } catch {
    // Une fiche imparfaite vaut mieux qu'un bouton mort : on garde l'origine telle quelle.
    port = 'inconnu';
  }
  const web = secure ? 'HTTPS' : 'HTTP';
  const socket = secure ? 'WSS' : 'WS';
  return [
    'Mise en service du runner Claude Gateway — demande d\'ouverture réseau',
    '',
    'SENS DES FLUX',
    '  Sortant uniquement. Aucun port entrant à ouvrir, aucune règle de NAT,',
    '  aucune exposition du poste de travail. C\'est le poste qui appelle.',
    '',
    'À AUTORISER EN SORTIE',
    `  Domaine    : ${host}`,
    `  Port       : ${port} (TCP)`,
    `  Protocoles : ${web} et ${socket}`,
    '',
    `  ${socket} emprunte le MÊME hôte et le MÊME port que ${web} : la connexion`,
    `  commence par une requête ${web} que le serveur bascule en ${socket}`,
    '  (en-tête Upgrade). Un équipement qui autorise ' + web + ' mais refuse',
    '  l\'Upgrade WebSocket ne coupe pas le service : le runner bascule sur un',
    `  repli en long-polling ${web}, fonctionnel mais plus lent.`,
    '',
    'PROXY D\'ENTREPRISE',
    '  Le runner lit HTTPS_PROXY, HTTP_PROXY et NO_PROXY.',
    '',
    '  Il ne sait PAS porter une authentification proxy INTÉGRÉE (NTLM,',
    '  Kerberos) : la JVM n\'a aucun support SSPI, et l\'authentification Basic',
    '  est désactivée sur les tunnels CONNECT depuis Java 8u111. Ce n\'est pas',
    '  un défaut du runner et aucune version ne le corrigera.',
    '',
    '  Si le proxy exige une authentification intégrée, deux issues :',
    `    1. exclure ${host} de l'authentification proxy ;`,
    '    2. laisser l\'utilisateur passer par un relais local (px, cntlm) qui',
    '       porte l\'authentification et expose un proxy sans authentification',
    '       sur 127.0.0.1.',
    '',
    'INTERCEPTION TLS',
    '  Si le proxy déchiffre le TLS, le certificat de l\'autorité interne doit',
    '  être connu de la JVM du runner :',
    '    -Djavax.net.ssl.trustStore=<fichier>',
    '',
    'CE QUI N\'EST PAS DEMANDÉ',
    '  Aucun droit administrateur, aucun service installé, aucune tâche',
    '  planifiée. Le runner s\'exécute sous le compte de l\'utilisateur, s\'affiche',
    '  en clair dans son terminal et s\'arrête au Ctrl-C.',
    '',
    `Fiche générée le ${generatedAt.toLocaleString('fr-FR')} depuis ${origin}.`,
  ].join('\n');
}

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
    MatSelectModule,
    MatTooltipModule,
  ],
  templateUrl: './runner-pairing-dialog.component.html',
  styleUrl: './runner-pairing-dialog.component.scss',
})
export class RunnerPairingDialogComponent implements OnDestroy {
  /**
   * Largeur du parcours de mise en service — et de l'assistant proxy qui s'ouvre par-dessus.
   * Une seule constante pour les deux : c'est ce qui garantit qu'ils restent alignés (F-56).
   */
  static readonly DIALOG_WIDTH = '560px';

  private readonly atelier = inject(AtelierService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject<MatDialogRef<RunnerPairingDialogComponent>>(MatDialogRef);

  /** Ouvre l'assistant proxy PAR-DESSUS ce parcours, sans le fermer (F-55 / SF-55-01, D1). */
  private readonly dialog = inject(MatDialog);

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
    // F-48 / SF-48-03 : le poste précède tout le reste. On part de celui que le projet porte déjà,
    // s'il en a un, et on relève la liste pour que l'utilisateur puisse en choisir un autre.
    this.hostId.set(this.data.hostId ?? null);
    this.projectPath.set(this.data.projectPath ?? '');
    this.selectedHostId.set(this.data.hostId ?? NEW_HOST);
    this.loadHosts();
    // F-45 / SF-45-02 : une fois la commande lancée, rien à l'écran ne disait si la machine s'était
    // appairée. L'information existait déjà côté gateway ; il suffisait de la relever.
    this.readRunnerStatus();
    this.statusPoll = setInterval(() => this.readRunnerStatus(), RUNNER_STATUS_POLL_MS);
  }

  /**
   * Relève les postes de l'utilisateur. <b>Silencieux en cas d'échec</b> : la liste n'est qu'un
   * confort — créer un poste reste possible sans elle, et un rouge ici enverrait chercher au mauvais
   * endroit.
   */
  private loadHosts(): void {
    this.atelier.listRunnerHosts().subscribe({
      next: (hosts) => this.hosts.set(hosts),
      error: () => this.hosts.set([]),
    });
  }

  /**
   * Crée le poste s'il faut, puis <b>rattache</b> le projet avec son chemin sous la racine
   * (F-48 / SF-48-03). Un seul geste de l'utilisateur, deux appels au plus.
   */
  attachToHost(): void {
    if (!this.canAttach()) {
      return;
    }
    this.attaching.set(true);
    this.attachError.set(null);
    const selected = this.selectedHostId();
    if (selected === NEW_HOST) {
      this.atelier.createRunnerHost(this.newHostName().trim()).subscribe({
        next: (host) => {
          this.hosts.update((hosts) => [host, ...hosts]);
          this.attachWorkspace(host.id);
        },
        error: (err: unknown) => this.failAttach(err),
      });
      return;
    }
    this.attachWorkspace(selected);
  }

  private attachWorkspace(hostId: string): void {
    this.atelier.attachWorkspaceToHost(this.data.workspaceId, hostId, this.projectPath().trim())
      .subscribe({
        next: (detail) => {
          this.attaching.set(false);
          this.hostId.set(detail.hostId ?? hostId);
          this.selectedHostId.set(detail.hostId ?? hostId);
          this.projectPath.set(detail.projectPath ?? '');
          // Un poste déjà connecté n'a rien à appairer : on saute directement à la conclusion.
          this.step.set(this.hostAlreadyLive() ? null : 'code');
        },
        error: (err: unknown) => this.failAttach(err),
      });
  }

  /** Traduit un refus en phrase utile, et laisse l'étape ouverte : rien n'est perdu. */
  private failAttach(err: unknown): void {
    this.attaching.set(false);
    if (err instanceof HttpErrorResponse && err.status === 400) {
      this.attachError.set(
        "Ce chemin n'est pas exploitable : il est relatif à la racine du poste, sans « .. » "
        + 'ni chemin absolu.');
      return;
    }
    if (err instanceof HttpErrorResponse && err.status === 404) {
      this.attachError.set("Ce poste n'existe plus.");
      this.loadHosts();
      return;
    }
    this.attachError.set("Le projet n'a pas pu être rattaché. Veuillez réessayer.");
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

  /** Vrai dès qu'un runner a été vu sur ce projet (F-45 / SF-45-02). */
  readonly runnerConnected = signal(false);

  /** Dernier signe de vie relevé, ou `null` si aucun runner ne s'est jamais signalé. */
  readonly runnerLastSeenAt = signal<string | null>(null);

  /**
   * Interpréteur élu par le runner et déclaré à la gateway (F-38 / SF-38-27), relevé avec l'état
   * (F-45 / SF-45-05). `null` tant qu'aucun runner ne l'a déclaré.
   */
  readonly runnerShell = signal<string | null>(null);

  /**
   * Étape dépliée, ou `null` quand elles le sont toutes (c'est l'état de la conclusion).
   *
   * <p>Replier n'est pas verrouiller : tout en-tête reste cliquable à tout moment. Le seul obstacle
   * que l'utilisateur ne peut pas lever seul — le `407` — se lève côté DSI ; bloquer le parcours
   * ferait de ce dialogue un piège pendant que le remède arrive (D3).</p>
   */
  readonly step = signal<PairingStep | null>('network');

  // ------------------------------- le poste (F-48 / SF-48-03) -------------------------------

  /** Valeur « créer un poste » de la liste, exposée au gabarit. */
  readonly newHostValue = NEW_HOST;

  /** Postes connus de l'utilisateur, relevés à l'ouverture du dialogue. */
  readonly hosts = signal<RunnerHost[]>([]);

  /** Poste retenu : un identifiant existant, ou {@link NEW_HOST} pour en créer un. */
  readonly selectedHostId = signal<string>(NEW_HOST);

  /** Nom du poste à créer — libre, y compris le nom d'un client. */
  readonly newHostName = signal('');

  /** Chemin du projet sous la racine du poste ; vide = la racine elle-même. */
  readonly projectPath = signal('');

  /** Poste auquel le projet est rattaché, une fois le geste fait. */
  readonly hostId = signal<string | null>(null);

  readonly attaching = signal(false);

  readonly attachError = signal<string | null>(null);

  /** Poste rattaché, tel qu'on le connaît — sert au résumé de l'étape et à la commande. */
  readonly attachedHost = computed(
    () => this.hosts().find((host) => host.id === this.hostId()) ?? null);

  /**
   * Vrai quand le projet est rattaché à un poste <b>déjà connecté</b> : il n'y a alors ni code à
   * générer ni runner à télécharger. C'est le gain de F-48, et l'écran doit le montrer plutôt que
   * de rejouer une mise en service qui a déjà eu lieu.
   */
  readonly hostAlreadyLive = computed(() => this.attachedHost()?.connected === true);

  /** Vrai quand le geste « rattacher » est possible en l'état du formulaire. */
  readonly canAttach = computed(() => {
    if (this.attaching()) {
      return false;
    }
    return this.selectedHostId() !== NEW_HOST || this.newHostName().trim().length > 0;
  });

  /** Ce que l'utilisateur déclare avoir lu à l'étape 1 (D2). */
  readonly networkVerdict = signal<NetworkVerdict>('unknown');

  /** Vrai dès que le runner a été récupéré — téléchargé ici, ou déclaré déjà présent. */
  readonly runnerObtained = signal(false);

  private countdown: ReturnType<typeof setInterval> | null = null;

  private statusPoll: ReturnType<typeof setInterval> | null = null;

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

  /**
   * Commande de vérification de la sortie réseau (F-45 / SF-45-01), à coller dans le terminal
   * <b>où le runner sera lancé</b>.
   *
   * <p>Elle n'est pas exécutée par la page, et ne peut pas l'être utilement : une requête partie du
   * navigateur prouverait que <b>le navigateur</b> sort — ce qui est déjà acquis, l'utilisateur lit
   * cette page. Le poste du client d'où vient cette feature avait exactement ce profil : navigateur
   * d'accord, terminal muet (D2).</p>
   */
  readonly networkCheckCommand = networkCheckCommand(this.hostPlatform, this.gatewayUrl);

  /**
   * L'adresse que le contrôle d'accès interroge, telle quelle : c'est celle que l'assistant proxy
   * (F-55) réemploie dans ses tests d'authentification. Deux adresses différentes autoriseraient un
   * verdict vert ici et rouge là-bas.
   */
  readonly networkCheckUrl = `${this.gatewayUrl}${NETWORK_CHECK_PATH}`;


  /** Comment retrouver le proxy du poste, sur le système consulté. */
  readonly proxyDiscoveryCommand = proxyDiscoveryCommand(this.hostPlatform);

  /** Comment déclarer le proxy trouvé dans le terminal courant. */
  readonly proxyExportCommand = proxyExportCommand(this.hostPlatform, 'http://hote:port');

  /** Comment déclarer le relais local, remède au `407` d'un proxy à authentification intégrée. */
  readonly relayExportCommand = proxyExportCommand(this.hostPlatform, LOCAL_RELAY_PROXY_URL);

  /** Vrai quand la page est consultée depuis Windows : l'invite de commandes y coexiste avec PowerShell. */
  readonly onWindows = this.hostPlatform === 'windows';

  /**
   * Fiche à transmettre à la DSI (F-45 / SF-45-03). Construite depuis l'**origine de la page**, pas
   * depuis `gatewayUrl` : c'est un domaine et un port qu'une DSI ouvre, pas un préfixe d'API.
   */
  readonly itSheet = itDepartmentSheet(
    typeof window !== 'undefined' ? window.location.origin : '', new Date());

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
    const code = this.codeUsable() ? this.pairingCode()!.code : '<code-appairage>';
    // Guillemets autour du chemin (F-38 / SF-38-23) : sans eux, Git Bash interprète les antislashs
    // d'un chemin Windows comme des échappements — « C:\Users\moi » arrive au runner en
    // « C:Usersmoi », que Windows résout ensuite comme un chemin RELATIF au lecteur C:. C'est le
    // deuxième obstacle rencontré par un client, juste après le prérequis Java. Les guillemets ne
    // gênent aucun shell, et suppriment le piège pour tous ceux qui copient la commande.
    // `--root` depuis F-48 / SF-48-02 : ce qu'on désigne est la racine du POSTE, le dossier sous
    // lequel vivent les projets — un seul appairage y suffit pour tous.
    return `${this.launcher()} --gateway ${this.gatewayUrl}`
      + ` --root "${this.commandPath()}" --code ${code}`;
  });

  /**
   * La commande des **fois suivantes** (F-46 / SF-46-02).
   *
   * <p>Depuis SF-46-01, l'appairage mémorise la passerelle et la racine à côté du jeton : un
   * lancement sans argument, depuis le projet, suffit. C'est ce geste-là que l'utilisateur répète
   * tous les matins — l'écran n'affichait pourtant que celui du <b>premier</b> jour, code
   * d'appairage compris.</p>
   *
   * <p>Elle ne dépend d'aucun code : un code expiré n'a aucune raison de rendre illisible une
   * commande qui ne s'en sert pas (D2).</p>
   */
  readonly resumeCommand = computed(
    () => `cd "${this.commandPath()}" && ${this.launcher()}`);

  /**
   * Vrai quand le paquet retenu est réellement servi : son lanceur démarre alors au **double-clic**,
   * sans qu'aucune commande soit tapée (F-46 / SF-46-02). La mention n'a de sens que là — un `.jar`
   * seul ne se double-clique pas utilement, il lui faudrait la JVM du système, celle-là même que le
   * paquet existe pour remplacer.
   */
  readonly doubleClickAvailable = computed(() => this.selectedPackage() !== null);

  /** Nom du lanceur du paquet retenu, cité dans la mention du double-clic. */
  readonly launcherFilename = computed(() =>
    this.selectedPackage() === 'windows' ? 'claude-runner.cmd' : 'claude-runner.command');

  /**
   * Chemin employé par les **deux** commandes : celui qui est saisi, sinon l'exemple. Une commande
   * visiblement incomplète vaut mieux qu'une commande faussement prête (D1 de SF-45-02).
   */
  private commandPath(): string {
    return this.workspacePath().trim() || this.examplePath();
  }

  /**
   * Ce par quoi la commande commence, et le même pour les deux : deux lanceurs différents pour une
   * seule machine seraient une invitation à l'erreur. Le paquet autonome s'exécute par **son**
   * lanceur — surtout pas préfixé de `java`, qui rappellerait la JVM du système, celle qui manque ou
   * qui est trop ancienne. Sur macOS le lanceur est un `.command` exécutable, d'où le `./` :
   * l'archive est un `.tar.gz` précisément pour que le bit exécutable survive à la décompression.
   */
  private launcher(): string {
    const selected = this.selectedPackage();
    if (selected === 'windows') {
      return 'claude-runner.cmd';
    }
    return selected !== null ? './claude-runner.command' : `java -jar ${JAR_FILENAME}`;
  }

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

  /**
   * Chemin d'exemple, piloté par le **format retenu** (F-45 / SF-45-02).
   *
   * <p>Un paquet Windows ne s'exécute que sur Windows : le format est alors une information
   * <b>certaine</b>, plus forte que l'`User-Agent`. Le `.jar`, lui, ne dit rien du système — c'est
   * justement son intérêt ; dans ce seul cas, le poste d'où la page est consultée décide (D1).</p>
   *
   * <p>Il reste un <b>exemple</b> : affiché en `placeholder`, jamais pré-rempli. Pré-remplir
   * `C:\Users\moi\…` enverrait une commande vers un dossier inexistant chez la moitié des
   * utilisateurs, et le runner refuse tout accès en dehors de la racine annoncée (D2).</p>
   */
  readonly examplePath = computed(() => {
    const selected = this.selectedPackage();
    if (selected === 'windows') {
      return WINDOWS_WORKSPACE_PATH;
    }
    if (selected !== null) {
      return MACOS_WORKSPACE_PATH;
    }
    return this.hostPlatform === 'windows' ? WINDOWS_WORKSPACE_PATH : DEFAULT_WORKSPACE_PATH;
  });

  /**
   * Ce que l'écran dit de la machine. Un seul libellé plutôt que trois morceaux assemblés dans le
   * gabarit : c'est la phrase entière qu'un test doit pouvoir affirmer.
   */
  readonly machineLabel = computed(() => {
    if (!this.runnerConnected()) {
      return 'En attente de la machine…';
    }
    const seen = this.lastSeenLabel();
    return seen ? `Machine connectée — dernier signe de vie à ${seen}` : 'Machine connectée';
  });

  /** Heure du dernier signe de vie, ou `null` quand elle est absente ou illisible. */
  readonly lastSeenLabel = computed(() => {
    const raw = this.runnerLastSeenAt();
    if (!raw) {
      return null;
    }
    const seen = new Date(raw);
    return Number.isNaN(seen.getTime())
      ? null
      : seen.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  });

  /** Libellé de l'interpréteur élu, ou `null` : la conclusion omet alors la ligne (D8). */
  readonly shellLabel = computed(() => shellLabel(this.runnerShell()));

  /** Vrai quand une étape est dépliée. Une seule l'est à la fois. */
  isOpen(step: PairingStep): boolean {
    return this.step() === step;
  }

  /**
   * Déplie une étape, ou replie celle qui l'était déjà. Aucun état n'est perdu en repliant : le
   * parcours vit dans les signaux du composant, pas dans le gabarit.
   */
  toggleStep(step: PairingStep): void {
    this.step.set(this.isOpen(step) ? null : step);
  }

  /**
   * Vrai quand l'étape est <b>faite</b>. Déduit d'un fait à chaque fois qu'il en existe un — code
   * généré, runner récupéré, machine vue (D5). L'étape 1 est la seule sans fait disponible : le
   * navigateur ne lit pas le terminal, c'est donc la déclaration de l'utilisateur qui la conclut.
   */
  stepDone(step: PairingStep): boolean {
    switch (step) {
      case 'network':
        return this.networkVerdict() === 'reachable';
      case 'code':
        return this.codeUsable();
      case 'download':
        return this.runnerObtained();
      case 'launch':
        return this.runnerConnected();
      case 'host':
        return this.hostId() !== null;
    }
  }

  /**
   * Ce que l'en-tête replié rapporte de l'étape, ou une chaîne vide quand il n'y a aucun fait à
   * rapporter. C'est la phrase entière qu'un test doit pouvoir affirmer.
   */
  stepSummary(step: PairingStep): string {
    switch (step) {
      case 'network':
        switch (this.networkVerdict()) {
          case 'reachable':
            return 'Ce terminal atteint la passerelle.';
          case 'proxy-auth':
            return 'Un proxy exige une authentification — voir la fiche pour votre DSI.';
          case 'no-answer':
            return 'Aucune réponse : le proxy n\'est pas déclaré dans ce terminal.';
          default:
            return '';
        }
      case 'code':
        if (this.codeUsable()) {
          return `Code généré, valable encore ${this.countdownLabel()}.`;
        }
        return this.codeExpired() ? 'Code expiré.' : '';
      case 'download':
        return this.runnerObtained() ? 'Runner récupéré.' : '';
      case 'launch':
        return this.machineLabel();
      case 'host':
        return this.hostSummary();
    }
  }

  /** Ce que l'en-tête replié rapporte du poste, ou une chaîne vide tant qu'il n'y en a pas. */
  private hostSummary(): string {
    const host = this.attachedHost();
    if (host === null) {
      return this.hostId() === null ? '' : 'Projet rattaché à un poste.';
    }
    const where = this.projectPath().trim();
    return where === ''
      ? `Poste « ${host.name} » — le projet est à la racine.`
      : `Poste « ${host.name} », projet dans « ${where} ».`;
  }

  /**
   * Enregistre ce que l'utilisateur a lu dans son terminal (D2). Seul un `200` fait avancer : les
   * deux autres branches renvoient <b>ailleurs</b> — un relais local, ou la DSI —, et faire mine de
   * poursuivre reviendrait à envoyer télécharger 39 Mo qu'on ne pourra pas appairer.
   */
  declareNetworkResult(verdict: NetworkVerdict): void {
    this.networkVerdict.set(verdict);
    if (verdict === 'reachable') {
      // Le POSTE avant le code (F-48 / SF-48-03) : un code d'appairage appartient à une machine, on
      // ne peut donc pas en demander un avant de savoir laquelle.
      this.step.set(this.hostId() === null ? 'host' : 'code');
    }
  }

  /**
   * Ouvre l'<b>assistant proxy</b> (F-55 / SF-55-01) : retrouver l'adresse du proxy sur ce système,
   * puis savoir lequel des deux remèdes s'applique.
   *
   * <p>Il s'ouvre <b>par-dessus</b> ce parcours, qui reste ouvert dessous : le code d'appairage
   * expire en cinq minutes et le parcours porte l'avancement — le fermer pour aller chercher un
   * proxy le ferait perdre (D1).</p>
   */
  openProxyAssistant(): void {
    const data: ProxyAssistantDialogData = {
      platform: this.hostPlatform,
      checkUrl: this.networkCheckUrl,
      // Seules les deux branches en échec portent le bouton ; tout le reste ouvre l'assistant à son
      // début, ce qui est le comportement juste quand on ne sait pas ce qui refuse.
      verdict: this.networkVerdict() === 'proxy-auth' ? 'proxy-auth' : 'no-answer',
    };
    this.dialog.open(ProxyAssistantDialogComponent, {
      data,
      // La largeur du parcours qu'il recouvre, et pas une autre (F-56) : l'assistant s'ouvre
      // par-dessus ce dialogue-ci, et deux cadres décalés se lisent comme deux produits. Les
      // commandes longues se replient (`pre-wrap` / `break-all`), elles ne débordent pas.
      width: RunnerPairingDialogComponent.DIALOG_WIDTH,
      maxWidth: '95vw',
      autoFocus: false,
    });
  }

  /** Ramène l'étape 1 à ses trois choix : une déclaration se révise. */
  resetNetworkResult(): void {
    this.networkVerdict.set('unknown');
  }

  /**
   * L'utilisateur a déjà le runner (téléchargé à une session précédente, ou reçu par sa DSI) :
   * l'étape est faite sans rien télécharger.
   */
  markRunnerObtained(): void {
    this.runnerObtained.set(true);
    this.step.set('launch');
  }

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
    const hostId = this.hostId();
    if (hostId === null) {
      // Rien à appairer tant qu'on ne sait pas QUELLE machine (F-48 / SF-48-03).
      this.generating.set(false);
      this.step.set('host');
      return;
    }
    this.atelier.createHostPairingCode(hostId).subscribe({
      next: (code) => {
        this.generating.set(false);
        this.pairingCode.set(code);
        this.startCountdown();
        // Le code expire en 5 minutes : l'étape suivante s'ouvre d'elle-même (F-45 / SF-45-05).
        this.step.set('download');
      },
      error: (err: unknown) => {
        this.generating.set(false);
        this.pairingCode.set(null);
        this.stopCountdown();
        this.generationError.set(
          err instanceof HttpErrorResponse && err.status === 404
            ? "Ce poste n'existe plus."
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
        this.markRunnerObtained();
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

  /**
   * Enregistre la fiche DSI en texte brut (F-45 / SF-45-03). Texte et non PDF : une DSI colle ces
   * lignes dans un ticket, et le texte brut passe partout (D2). La copie reste offerte à côté —
   * les deux actions sont indépendantes, si l'une est bloquée l'autre reste utile.
   */
  downloadItSheet(): void {
    this.saveBlob(new Blob([this.itSheet], { type: 'text/plain;charset=utf-8' }), IT_SHEET_FILENAME);
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
    this.stopStatusPoll();
  }

  /**
   * Relève l'état de la machine. **Silencieux en cas d'échec** : ce dialogue est déjà celui où
   * quelque chose ne marche pas, et un rouge sur un aller-retour manqué ferait chercher au mauvais
   * endroit. Un `403` ou un `404` ne se réparant pas tout seuls, le minuteur s'arrête plutôt que de
   * battre à vide (D4).
   */
  private readRunnerStatus(): void {
    this.atelier.getRunnerStatus(this.data.workspaceId).subscribe({
      next: (status) => {
        this.runnerLastSeenAt.set(status.lastSeenAt);
        // Champ additif (F-45 / SF-45-05) : une gateway antérieure ne l'envoie pas, et la
        // conclusion omet alors la ligne plutôt que d'écrire « inconnu ».
        this.runnerShell.set(status.shell ?? null);
        if (status.connected) {
          this.runnerConnected.set(true);
          // La conclusion REMPLACE le parcours : c'est la seule information attendue depuis le
          // début, et la laisser en bas de l'étape 4 la rendait invisible sur un portable (D6).
          // Rien n'est perdu — tout en-tête reste cliquable.
          this.step.set(null);
          // La question posée par ce dialogue — « l'appairage a-t-il marché ? » — a sa réponse.
          // Continuer à interroger serait de la surveillance, pas de l'installation (D3).
          this.stopStatusPoll();
        }
      },
      error: (err: unknown) => {
        if (err instanceof HttpErrorResponse && (err.status === 403 || err.status === 404)) {
          this.stopStatusPoll();
        }
      },
    });
  }

  private stopStatusPoll(): void {
    if (this.statusPoll !== null) {
      clearInterval(this.statusPoll);
      this.statusPoll = null;
    }
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
