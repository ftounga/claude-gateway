import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

// Import de TYPE uniquement : le parcours de mise en service importe, lui, ce composant. Un import
// de valeur dans les deux sens créerait un cycle à l'exécution ; celui-ci est effacé à la
// compilation.
import type { RunnerHostPlatform } from './runner-pairing-dialog.component';

/**
 * Ce que l'assistant sait en s'ouvrant : le système du poste, l'adresse à joindre, et — quand il est
 * ouvert depuis le parcours de mise en service — ce que le contrôle d'accès a donné.
 *
 * <p>Le verdict n'est pas décoratif : un `407` et une absence de réponse se ressemblent à l'écran et
 * <b>n'ont pas le même remède</b>. L'assistant s'ouvre donc à l'endroit utile plutôt qu'au début.</p>
 */
export interface ProxyAssistantDialogData {
  /** Système d'où la page est consultée — il pilote tous les gestes affichés. */
  platform: RunnerHostPlatform;
  /** URL publique interrogée par le contrôle d'accès (SF-45-01), reprise telle quelle. */
  checkUrl: string;
  /** Ce que l'utilisateur a déclaré au contrôle d'accès, ou `null` s'il n'a rien déclaré. */
  verdict?: 'proxy-auth' | 'no-answer' | null;
}

/** Étape dépliée de l'assistant. Une seule l'est à la fois, comme dans le parcours (SF-45-05). */
export type ProxyAssistantStep = 'address' | 'qualify' | 'relay' | 'verify';

/**
 * Ce que l'utilisateur déclare avoir obtenu au test de l'authentification <b>intégrée</b>.
 *
 * <p>La distinction Kerberos / NTLM n'est pas cosmétique : `cntlm` ne porte que NTLM, quand `px`
 * porte les deux. Un poste en Kerberos à qui l'on conseillerait `cntlm` réinstallerait tout.</p>
 */
export type IntegratedAuthVerdict = 'unknown' | 'negotiate' | 'ntlm' | 'refused';

/** Marqueur employé tant qu'aucune adresse n'a été trouvée : visiblement incomplet, jamais faux. */
export const PROXY_ADDRESS_PLACEHOLDER = 'hote:port';

/** Longueur maximale acceptée dans le champ d'adresse — au-delà, ce n'est plus une adresse. */
export const PROXY_ADDRESS_MAX_LENGTH = 255;

/**
 * Port exposé par le relais local. C'est celui que `px` comme `cntlm` prennent par défaut, et celui
 * que le contrôle de vol du runner nomme déjà dans son message de `407` (F-45 / SF-45-04).
 */
export const LOCAL_RELAY_PORT = 3128;

/** Adresse du relais local, telle qu'elle entre dans `-x` puis dans `HTTPS_PROXY`. */
export const LOCAL_RELAY_URL = `http://127.0.0.1:${LOCAL_RELAY_PORT}`;

/** Marqueurs tenant la place du domaine et de l'identifiant tant que rien n'est saisi. */
export const DOMAIN_PLACEHOLDER = 'DOMAINE';
export const USER_PLACEHOLDER = 'identifiant';

/** Longueur maximale du domaine et de l'identifiant : au-delà, ce n'est plus l'un ni l'autre. */
export const RELAY_FIELD_MAX_LENGTH = 64;

/** Ce que l'utilisateur déclare avoir obtenu en vérifiant le relais. */
export type RelayCheckVerdict = 'unknown' | 'carried' | 'failed';

/** Les deux relais que ce produit sait décrire. Il n'en embarque, n'en sert et n'en installe aucun. */
export type RelayKind = 'px' | 'cntlm';

/** Un relais proposé : ce qu'il est, quand il convient, et les gestes dans l'ordre. */
export interface RelayOption {
  readonly kind: RelayKind;
  readonly title: string;
  /** Le poste auquel cette option s'adresse — c'est l'utilisateur qui tranche, pas le navigateur. */
  readonly when: string;
  readonly commands: ProxyCommand[];
}

/** Une commande à copier, et ce qu'elle répond. */
export interface ProxyCommand {
  /** Ce que la commande cherche, en une phrase. */
  readonly purpose: string;
  /** La commande elle-même, telle qu'elle doit être collée. */
  readonly command: string;
}

/**
 * Où lire l'adresse du proxy, sur le système consulté.
 *
 * <p>Sous Windows, il en faut <b>trois</b>, et c'est le cœur de cette subfeature : `netsh` lit
 * <b>WinHTTP</b>, le navigateur lit <b>WinINET</b>, et l'adresse peut n'exister nulle part ailleurs
 * que dans un <b>fichier PAC</b>. Un poste dont le navigateur sort parfaitement peut répondre
 * `Direct access` à `netsh` — c'est précisément ce qui a coûté le plus de temps le 2026-09-07.</p>
 *
 * <p>Jamais vide : un système inconnu reçoit le geste générique.</p>
 */
export function proxyLookupCommands(platform: RunnerHostPlatform): ProxyCommand[] {
  const registry = 'reg query "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"';
  switch (platform) {
    case 'windows':
      return [
        {
          purpose: 'Le proxy vu par WinHTTP — celui des outils système. « Direct access » ne prouve '
            + 'rien : le navigateur, lui, ne lit pas cette configuration.',
          command: 'netsh winhttp show proxy',
        },
        {
          purpose: 'Le proxy du navigateur (WinINET) : celui qui fonctionne pendant que le terminal '
            + 'échoue.',
          command: `${registry} /v ProxyServer`,
        },
        {
          purpose: "L'URL du fichier PAC, quand l'adresse n'est écrite nulle part ailleurs.",
          command: `${registry} /v AutoConfigURL`,
        },
      ];
    case 'macos':
      return [
        {
          purpose: 'HTTPSProxy et HTTPSPort donnent l\'adresse ; ProxyAutoConfigURLString donne '
            + "l'URL d'un fichier PAC, le cas échéant.",
          command: 'scutil --proxy',
        },
      ];
    default:
      return [
        {
          purpose: 'Ce que ce terminal a déjà — et rien d\'autre : un shell ne lit pas la '
            + 'configuration système.',
          command: 'env | grep -i proxy',
        },
      ];
  }
}

/** Vrai quand le système consulté peut annoncer un fichier PAC : il y a alors quoi en faire. */
export function mayUsePacFile(platform: RunnerHostPlatform): boolean {
  return platform === 'windows' || platform === 'macos';
}

/**
 * Normalise une adresse de proxy saisie vers la forme <code>hôte</code> ou <code>hôte:port</code> —
 * la seule qu'attendent <code>curl -x</code>, <code>px</code> et <code>cntlm</code>.
 *
 * <p>Trois retraits, et un seul compte vraiment :</p>
 * <ul>
 *   <li>le <b>schéma</b> et le <b>chemin</b>, parce qu'ils sont recopiés sans y penser depuis un
 *       fichier de configuration ;</li>
 *   <li>la barre finale, pour la même raison ;</li>
 *   <li>les <b>identifiants inline</b> — <code>http://moi:secret&#64;px:8080</code> : un mot de passe
 *       collé par mégarde ne doit ressortir dans <b>aucune</b> commande affichée, ni dans aucune
 *       capture d'écran envoyée au support (D7).</li>
 * </ul>
 *
 * @return l'adresse normalisée, ou {@code null} si rien d'exploitable n'en sort
 */
export function normalizeProxyAddress(raw: string): string | null {
  let value = (raw ?? '').trim();
  if (value === '' || value.length > PROXY_ADDRESS_MAX_LENGTH) {
    return null;
  }
  const scheme = value.indexOf('://');
  if (scheme >= 0) {
    value = value.substring(scheme + 3);
  }
  // D7 : tout ce qui précède le « @ » est un identifiant. On le jette sans le regarder.
  const at = value.lastIndexOf('@');
  if (at >= 0) {
    value = value.substring(at + 1);
  }
  const slash = value.indexOf('/');
  if (slash >= 0) {
    value = value.substring(0, slash);
  }
  value = value.trim();
  if (value === '' || /\s/.test(value)) {
    return null;
  }
  const colon = value.lastIndexOf(':');
  if (colon < 0) {
    return isHost(value) ? value : null;
  }
  const host = value.substring(0, colon);
  const port = value.substring(colon + 1);
  if (!isHost(host) || !/^\d{1,5}$/.test(port)) {
    return null;
  }
  const portNumber = Number(port);
  if (portNumber < 1 || portNumber > 65535) {
    return null;
  }
  return `${host}:${portNumber}`;
}

/** Un hôte plausible : lettres, chiffres, points et tirets — assez pour écarter une saisie hasardeuse. */
function isHost(value: string): boolean {
  return /^[A-Za-z0-9._-]+$/.test(value) && value !== '.' && !value.startsWith('-');
}

/**
 * Comment <b>déclarer</b> le proxy trouvé dans le terminal courant — le remède de l'absence de
 * route, qui n'a rien à voir avec celui du `407` (D5).
 *
 * <p>Sous Windows, les deux formes sont données : PowerShell et l'invite de commandes ne partagent
 * pas la syntaxe, et l'utilisateur n'est pas tenu de savoir dans laquelle il se trouve.</p>
 */
export function proxyDeclareCommands(platform: RunnerHostPlatform, address: string): ProxyCommand[] {
  const url = `http://${address}`;
  if (platform === 'windows') {
    return [
      { purpose: 'PowerShell', command: `$env:HTTPS_PROXY="${url}"; $env:HTTP_PROXY="${url}"` },
      { purpose: 'Invite de commandes', command: `set HTTPS_PROXY=${url}\nset HTTP_PROXY=${url}` },
    ];
  }
  return [
    { purpose: 'Le terminal courant', command: `export HTTPS_PROXY=${url}\nexport HTTP_PROXY=${url}` },
  ];
}

/**
 * Les deux commandes qui disent si l'authentification <b>intégrée</b> du poste passe — Kerberos
 * d'abord, NTLM ensuite.
 *
 * <p>Deux partis pris :</p>
 * <ul>
 *   <li><b><code>--proxy-user :</code></b> — utilisateur et mot de passe <b>vides</b> : c'est ce qui
 *       demande à `curl` d'employer la session déjà ouverte sur le poste. Aucun identifiant n'est
 *       saisi dans cet écran, et aucun ne le sera (D4).</li>
 *   <li><b><code>-x http://…</code></b> plutôt que l'environnement : le cas « absence de route » est
 *       justement celui où rien n'est déclaré dans le terminal ; un test qui dépendrait de
 *       <code>HTTPS_PROXY</code> n'y testerait rien (D3).</li>
 * </ul>
 *
 * <p><code>curl.exe</code> sous Windows, jamais <code>curl</code> : dans PowerShell, ce dernier est
 * un alias d'<code>Invoke-WebRequest</code> dont les options n'ont rien à voir (SF-45-01).</p>
 */
export function integratedAuthCommands(
  platform: RunnerHostPlatform,
  address: string,
  checkUrl: string,
): ProxyCommand[] {
  const curl = platform === 'windows' ? 'curl.exe' : 'curl';
  const sink = platform === 'windows' ? 'NUL' : '/dev/null';
  const proxy = `-x http://${address}`;
  return [
    {
      purpose: 'Kerberos (Negotiate)',
      command: `${curl} -sS -o ${sink} -w "Kerberos : %{http_code}\\n" --proxy-negotiate `
        + `--proxy-user : ${proxy} ${checkUrl}`,
    },
    {
      purpose: 'NTLM',
      command: `${curl} -sS -o ${sink} -w "NTLM : %{http_code}\\n" --proxy-ntlm `
        + `--proxy-user : ${proxy} ${checkUrl}`,
    },
  ];
}

/**
 * Option d'authentification que `curl` doit employer pour <b>télécharger le relais à travers le
 * proxy</b> : celle-là même qui vient de répondre `200`. Un téléchargement qui échoue au milieu de
 * l'installation du remède est le meilleur moyen de croire que le remède ne marche pas.
 */
function curlAuthFlag(verdict: IntegratedAuthVerdict): string {
  return verdict === 'negotiate' ? '--proxy-negotiate' : '--proxy-ntlm';
}

/**
 * Les relais qui conviennent <b>à ce poste et à ce verdict</b>, en ordre d'exécution
 * (F-55 / SF-55-02).
 *
 * <p>Deux règles, et la première coûte cher quand on l'ignore :</p>
 * <ul>
 *   <li><b>`cntlm` ne porte que NTLM.</b> Le proposer à un poste en Kerberos fait installer,
 *       configurer, échouer — puis tout recommencer avec `px` (D1).</li>
 *   <li><b>Sous macOS, les deux architectures sont montrées</b> : le navigateur ne sait pas les
 *       distinguer (Safari comme Chrome annoncent `MacIntel` sur un M3, déjà constaté en F-44), et
 *       deviner enverrait la moitié des Mac sur un binaire qui n'existe pas (D2).</li>
 * </ul>
 *
 * <p>Un verdict `refused` ne renvoie <b>rien</b> : un relais s'authentifierait de la même façon et
 * échouerait pareil — c'est une demande DSI, pas une installation.</p>
 */
export function relayOptions(
  platform: RunnerHostPlatform,
  verdict: IntegratedAuthVerdict,
  address: string,
): RelayOption[] {
  if (verdict === 'refused' || verdict === 'unknown') {
    return [];
  }
  const flag = curlAuthFlag(verdict);
  const px = `--proxy=${address} --port=${LOCAL_RELAY_PORT}`;
  if (platform === 'windows') {
    return [
      {
        kind: 'px',
        title: 'px — le relais autonome',
        when: 'Windows, sans droits administrateur',
        commands: [
          {
            purpose: 'À essayer d\'abord. Il échoue souvent, et pour une raison circulaire : winget '
              + 'sort par WinHTTP, qui est précisément ce qui n\'a pas de proxy configuré.',
            command: 'winget install genotrance.px',
          },
          {
            purpose: 'Sinon, le binaire autonome — repérer le lien de la version Windows, téléchargé '
              + 'À TRAVERS le proxy, avec l\'authentification qui vient de répondre 200.',
            command: `curl.exe -sS ${flag} --proxy-user : -x http://${address} -L `
              + 'https://api.github.com/repos/genotrance/px/releases/latest'
              + ' | findstr browser_download_url',
          },
          {
            purpose: 'Récupérer l\'archive windows-amd64, puis la décompresser.',
            command: `curl.exe -sS ${flag} --proxy-user : -x http://${address} -L -o px.zip `
              + '"<url windows-amd64>"\ntar -xf px.zip',
          },
          {
            purpose: 'Lancer le relais. Il reste au premier plan : cette fenêtre ne doit pas être '
              + 'fermée tant que le runner tourne.',
            command: `px.exe ${px}`,
          },
        ],
      },
    ];
  }
  const pxUnix: RelayOption = {
    kind: 'px',
    title: 'px — le relais autonome',
    when: platform === 'macos'
      ? 'Mac Apple Silicon (binaire publié), et Mac Intel par pip3'
      : 'Linux et systèmes non reconnus',
    commands: [
      {
        purpose: platform === 'macos'
          ? 'Apple Silicon : repérer l\'archive mac-arm64, téléchargée À TRAVERS le proxy.'
          : 'Repérer l\'archive publiée, téléchargée À TRAVERS le proxy.',
        command: `curl -sS ${flag} --proxy-user : -x http://${address} -L `
          + 'https://api.github.com/repos/genotrance/px/releases/latest'
          + ' | grep browser_download_url',
      },
      {
        purpose: 'Sans binaire pour cette architecture (Mac Intel, la plupart des Linux) : px '
          + 's\'installe aussi par pip3, dans le compte de l\'utilisateur.',
        command: 'pip3 install --user px-proxy',
      },
      {
        purpose: 'Lancer le relais. Il reste au premier plan : ce terminal ne doit pas être fermé '
          + 'tant que le runner tourne.',
        command: `px ${px}`,
      },
    ],
  };
  if (verdict === 'negotiate') {
    // Kerberos : `cntlm` ne sait pas le porter. Le proposer ici serait une fausse piste (D1).
    return [pxUnix];
  }
  const cntlm: RelayOption = {
    kind: 'cntlm',
    title: 'cntlm — l\'alternative empaquetée partout',
    when: platform === 'macos' ? 'Mac Intel, si Homebrew est disponible' : 'Linux — NTLM uniquement',
    commands: [
      {
        purpose: 'Installer.',
        command: platform === 'macos' ? 'brew install cntlm' : 'sudo apt install cntlm',
      },
      {
        purpose: 'Lancer le relais, une fois le fichier de configuration rempli. `-f` le garde au '
          + 'premier plan : ce terminal ne doit pas être fermé tant que le runner tourne.',
        command: 'cntlm -f',
      },
    ],
  };
  return platform === 'macos' ? [pxUnix, cntlm] : [cntlm, pxUnix];
}

/** Une valeur de domaine ou d'identifiant retenue, ou son marqueur : jamais une saisie douteuse. */
function relayField(raw: string, placeholder: string, pattern: RegExp): string {
  const value = (raw ?? '').trim();
  if (value === '' || value.length > RELAY_FIELD_MAX_LENGTH || !pattern.test(value)) {
    return placeholder;
  }
  return value;
}

/** Domaine retenu pour la configuration `cntlm`, ou son marqueur. */
export function relayDomainOrPlaceholder(raw: string): string {
  return relayField(raw, DOMAIN_PLACEHOLDER, /^[A-Za-z0-9._-]+$/);
}

/** Identifiant retenu pour la configuration `cntlm`, ou son marqueur. */
export function relayUserOrPlaceholder(raw: string): string {
  return relayField(raw, USER_PLACEHOLDER, /^[A-Za-z0-9._\\-]+$/);
}

/**
 * Le fichier de configuration `cntlm` — <b>sans aucun mot de passe</b>.
 *
 * <p>Une ligne `Password` en clair est ce que fait tout le monde, et c'est un mot de passe de domaine
 * déposé en clair dans un fichier. `cntlm -H` produit à la place des lignes `PassNTLMv2` : c'est ce
 * haché qu'on colle ici (D4).</p>
 */
export function cntlmConfig(address: string, domain: string, user: string): string {
  return [
    `Username    ${user}`,
    `Domain      ${domain}`,
    `Proxy       ${address}`,
    `Listen      ${LOCAL_RELAY_PORT}`,
    'NoProxy     localhost, 127.0.0.*',
    '',
    '# Coller ici les lignes PassNTLMv2 produites par la commande ci-dessous.',
    '# Aucune ligne Password en clair.',
  ].join('\n');
}

/**
 * La commande qui produit le haché. Elle demande le mot de passe <b>dans le terminal</b> : il ne
 * transite ni par cet écran, ni par le réseau, ni par un fichier.
 */
export function cntlmHashCommand(domain: string, user: string): string {
  return `cntlm -H -d ${domain} -u ${user}`;
}

/**
 * La vérification du relais (F-55 / SF-55-02) : <b>aucune</b> option d'authentification, et c'est
 * tout l'objet du test. Un `200` obtenu sans `--proxy-ntlm` prouve que le relais authentifie à la
 * place de celui qui l'appelle — donc à la place de la JVM, qui ne sait pas le faire.
 *
 * <p>Elle se lance dans un <b>second</b> terminal : le premier est occupé par le relais.</p>
 */
export function relayCheckCommand(platform: RunnerHostPlatform, checkUrl: string): string {
  const curl = platform === 'windows' ? 'curl.exe' : 'curl';
  const sink = platform === 'windows' ? 'NUL' : '/dev/null';
  return `${curl} -sS -o ${sink} -w "via le relais : %{http_code}\\n" `
    + `-x ${LOCAL_RELAY_URL} ${checkUrl}`;
}

/**
 * Rediriger le runner vers le relais — <b>après</b> l'avoir vérifié (D3).
 *
 * <p>Le `NO_PROXY` porte le piège qui traverse toute cette feature : <b>Windows sépare ses
 * exclusions par des `;`</b>, quand `curl` et le runner attendent des `,`. Recopiée telle quelle, la
 * liste devient un seul nom d'hôte, et plus rien n'est exclu.</p>
 */
export function runnerRedirectCommands(platform: RunnerHostPlatform): ProxyCommand[] {
  const exclusions = '.domaine-interne.local,localhost,127.0.0.1';
  if (platform === 'windows') {
    return [
      {
        purpose: 'PowerShell — dans le terminal qui lancera le runner.',
        command: `$env:HTTPS_PROXY="${LOCAL_RELAY_URL}"; $env:HTTP_PROXY="${LOCAL_RELAY_URL}"`,
      },
      {
        purpose: 'Les exclusions, séparées par des VIRGULES.',
        command: `$env:NO_PROXY="${exclusions}"`,
      },
    ];
  }
  return [
    {
      purpose: 'Dans le terminal qui lancera le runner.',
      command: `export HTTPS_PROXY=${LOCAL_RELAY_URL}\nexport HTTP_PROXY=${LOCAL_RELAY_URL}`,
    },
    {
      purpose: 'Les exclusions, séparées par des VIRGULES.',
      command: `export NO_PROXY="${exclusions}"`,
    },
  ];
}

/**
 * Assistant proxy (F-55 / SF-55-01) : de « ça ne passe pas » jusqu'à un remède nommé.
 *
 * <p>F-45 a posé le diagnostic et s'arrête à une phrase — « le remède est un relais local ». Entre
 * cette phrase et un runner qui passe, il reste à <b>trouver l'adresse du proxy</b> (que le poste ne
 * dit pas spontanément, et qui peut n'exister que dans un fichier PAC) et à <b>savoir lequel des
 * deux remèdes</b> s'applique : un `407` se lève avec un relais, une absence de route avec une
 * simple déclaration dans le terminal.</p>
 *
 * <p>L'assistant <b>compose</b> des commandes ; il n'en exécute aucune, n'émet aucun appel réseau et
 * ne demande jamais un mot de passe.</p>
 */
@Component({
  selector: 'app-proxy-assistant-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatTooltipModule,
  ],
  templateUrl: './proxy-assistant-dialog.component.html',
  styleUrl: './proxy-assistant-dialog.component.scss',
})
export class ProxyAssistantDialogComponent {
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialogRef = inject<MatDialogRef<ProxyAssistantDialogComponent>>(MatDialogRef);

  readonly data = inject<ProxyAssistantDialogData>(MAT_DIALOG_DATA);

  /** Marqueur exposé au gabarit, pour que les tests puissent l'affirmer sans le retaper. */
  readonly placeholder = PROXY_ADDRESS_PLACEHOLDER;

  readonly maxLength = PROXY_ADDRESS_MAX_LENGTH;

  /**
   * Étape dépliée. Un `407` déjà constaté ouvre la qualification : l'utilisateur sait déjà qu'un
   * proxy répond — c'est ce qu'il en fait qui lui manque.
   */
  readonly step = signal<ProxyAssistantStep>(
    this.data.verdict === 'proxy-auth' ? 'qualify' : 'address');

  /** Adresse saisie, telle que tapée. Elle ne quitte pas le navigateur. */
  readonly proxyAddress = signal('');

  /** Ce que l'utilisateur déclare avoir obtenu au test de l'authentification intégrée. */
  readonly authVerdict = signal<IntegratedAuthVerdict>('unknown');

  /** Où lire l'adresse, sur le système consulté. */
  readonly lookupCommands = proxyLookupCommands(this.data.platform);

  /** Vrai quand le système consulté peut annoncer un fichier PAC. */
  readonly pacPossible = mayUsePacFile(this.data.platform);

  /** L'adresse normalisée, ou `null` : les commandes gardent alors le marqueur. */
  readonly resolvedAddress = computed(() => normalizeProxyAddress(this.proxyAddress()));

  /** Vrai quand quelque chose a été saisi mais que rien d'exploitable n'en sort. */
  readonly addressInvalid = computed(
    () => this.proxyAddress().trim() !== '' && this.resolvedAddress() === null);

  /** L'adresse à employer dans les commandes : celle qui est saisie, sinon le marqueur (D2). */
  readonly commandAddress = computed(() => this.resolvedAddress() ?? PROXY_ADDRESS_PLACEHOLDER);

  /** Comment déclarer le proxy dans ce terminal — le remède de l'absence de route. */
  readonly declareCommands = computed(
    () => proxyDeclareCommands(this.data.platform, this.commandAddress()));

  /** Les deux tests de l'authentification intégrée — le remède du `407` commence par eux. */
  readonly authCommands = computed(
    () => integratedAuthCommands(this.data.platform, this.commandAddress(), this.data.checkUrl));

  // ------------------------------ le relais local (SF-55-02) ------------------------------

  /** Domaine du compte, tel que saisi. Ce n'est pas un secret, et il ne quitte pas le navigateur. */
  readonly relayDomain = signal('');

  /** Identifiant du compte, tel que saisi. Il n'y a **aucun** champ de mot de passe (D4). */
  readonly relayUser = signal('');

  /** Ce que l'utilisateur déclare avoir obtenu en vérifiant le relais. */
  readonly relayVerified = signal<RelayCheckVerdict>('unknown');

  /** Longueur maximale du domaine et de l'identifiant, exposée au gabarit. */
  readonly relayFieldMaxLength = RELAY_FIELD_MAX_LENGTH;

  readonly domainPlaceholder = DOMAIN_PLACEHOLDER;

  readonly userPlaceholder = USER_PLACEHOLDER;

  /** L'adresse du relais local, celle que la vérification puis la redirection emploient. */
  readonly relayUrl = LOCAL_RELAY_URL;

  /** Les relais qui conviennent à ce poste et à ce verdict — vide quand il n'y a rien à installer. */
  readonly relays = computed(
    () => relayOptions(this.data.platform, this.authVerdict(), this.commandAddress()));

  /** Vrai quand `cntlm` figure parmi les relais proposés : sa configuration n'a de sens que là. */
  readonly cntlmProposed = computed(() => this.relays().some((relay) => relay.kind === 'cntlm'));

  /** Le fichier de configuration `cntlm`, composé — et sans aucun mot de passe. */
  readonly cntlmConfig = computed(() => cntlmConfig(
    this.commandAddress(),
    relayDomainOrPlaceholder(this.relayDomain()),
    relayUserOrPlaceholder(this.relayUser()),
  ));

  /** La commande qui produit le haché, sur le poste et dans le terminal. */
  readonly cntlmHashCommand = computed(() => cntlmHashCommand(
    relayDomainOrPlaceholder(this.relayDomain()),
    relayUserOrPlaceholder(this.relayUser()),
  ));

  /** La vérification du relais : aucune option d'authentification, c'est tout l'objet du test. */
  readonly relayCheckCommand = computed(
    () => relayCheckCommand(this.data.platform, this.data.checkUrl));

  /** La redirection du runner — affichée seulement après un `200` déclaré (D3). */
  readonly redirectCommands = computed(() => runnerRedirectCommands(this.data.platform));

  /** Enregistre ce que la vérification du relais a donné. */
  declareRelayResult(verdict: RelayCheckVerdict): void {
    this.relayVerified.set(verdict);
  }

  /** Ramène la vérification à ses deux choix : une déclaration se révise. */
  resetRelayResult(): void {
    this.relayVerified.set('unknown');
  }

  /** Vrai quand une étape est dépliée. Une seule l'est à la fois. */
  isOpen(step: ProxyAssistantStep): boolean {
    return this.step() === step;
  }

  /**
   * Déplie une étape. Toute étape reste atteignable à tout moment — le proxy peut avoir été trouvé
   * hier, et la qualification ne dépend d'aucune saisie : rien n'est verrouillé (SF-45-05, D3).
   */
  openStep(step: ProxyAssistantStep): void {
    this.step.set(step);
  }

  /** Enregistre ce que le test d'authentification intégrée a donné. */
  declareAuthResult(verdict: IntegratedAuthVerdict): void {
    this.authVerdict.set(verdict);
  }

  /** Ramène la qualification à ses trois choix : une déclaration se révise. */
  resetAuthResult(): void {
    this.authVerdict.set('unknown');
  }

  /** Copie un texte, avec un repli lisible quand le presse-papiers est indisponible. */
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
}
