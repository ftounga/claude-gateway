import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import { RunnerDownloadFormats } from '../../core/models/atelier.models';

import { AtelierService } from '../../core/services/atelier.service';
import {
  DEFAULT_WORKSPACE_PATH,
  IT_SHEET_FILENAME,
  LOCAL_RELAY_PROXY_URL,
  MACOS_WORKSPACE_PATH,
  NETWORK_CHECK_PATH,
  RUNNER_BUILD_COMMAND,
  RUNNER_HOST_PLATFORM,
  RUNNER_STATUS_POLL_MS,
  RunnerHostPlatform,
  RunnerPairingDialogComponent,
  shellLabel,
  WINDOWS_WORKSPACE_PATH,
  detectHostPlatform,
  itDepartmentSheet,
  networkCheckCommand,
  proxyDiscoveryCommand,
  proxyExportCommand,
} from './runner-pairing-dialog.component';

describe('RunnerPairingDialogComponent (F-38 SF-38-06)', () => {
  let fixture: ComponentFixture<RunnerPairingDialogComponent>;
  let component: RunnerPairingDialogComponent;
  let service: jasmine.SpyObj<AtelierService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<RunnerPairingDialogComponent>>;

  /** Gateway à jour : les quatre formats servis (F-44 / SF-44-03). */
  const EVERY_FORMAT: RunnerDownloadFormats = {
    jar: true,
    windowsPackage: true,
    macosAarch64Package: true,
    macosX64Package: true,
  };

  /**
   * Le poste d'où la page est consultée est **injecté** : sans cela, ces tests dépendraient du
   * navigateur qui les exécute (Chrome headless sous Linux en intégration continue), et
   * n'affirmeraient rien de stable sur la présélection.
   */
  function setup(
    formats: RunnerDownloadFormats = EVERY_FORMAT,
    hostPlatform: RunnerHostPlatform = 'windows',
  ): void {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'createRunnerPairingCode',
      'downloadRunnerJar',
      'downloadRunnerWindowsPackage',
      'downloadRunnerMacosPackage',
      'runnerDownloadFormats',
      'getRunnerStatus',
    ]);
    // F-45 / SF-45-02 : le dialogue relève l'état de la machine dès sa construction. Défaut =
    // « pas encore vue », l'état d'un projet dont le runner n'a pas encore été lancé.
    service.getRunnerStatus.and.returnValue(of({ connected: false, lastSeenAt: null }));
    // Le composant demande les formats disponibles dès sa construction (F-44 / SF-44-02) : sans
    // cette réponse, aucun test de ce fichier ne peut instancier le dialogue. Défaut = les deux
    // formats servis, qui est l'état d'une gateway à jour.
    service.runnerDownloadFormats.and.returnValue(of(formats));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    dialogRef = jasmine.createSpyObj<MatDialogRef<RunnerPairingDialogComponent>>('MatDialogRef', [
      'close',
    ]);

    TestBed.configureTestingModule({
      imports: [RunnerPairingDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: AtelierService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: { workspaceId: 'w1', workspaceName: 'projet' } },
        { provide: RUNNER_HOST_PLATFORM, useValue: hostPlatform },
      ],
    });

    fixture = TestBed.createComponent(RunnerPairingDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  /** Code d'appairage expirant dans `seconds` secondes. */
  function codeExpiringIn(seconds: number, code = 'AB12CD') {
    return { code, expiresAt: new Date(Date.now() + seconds * 1000).toISOString() };
  }

  afterEach(() => fixture?.destroy());

  it('n\'affiche aucun code tant qu\'aucun n\'a été demandé', () => {
    setup();
    expect(component.pairingCode()).toBeNull();
    expect(component.codeUsable()).toBeFalse();
    expect(service.createRunnerPairingCode).not.toHaveBeenCalled();
  });

  it('affiche le code généré et son compte à rebours', () => {
    setup();
    service.createRunnerPairingCode.and.returnValue(of(codeExpiringIn(300)));

    component.generateCode();

    expect(service.createRunnerPairingCode).toHaveBeenCalledWith('w1');
    expect(component.pairingCode()?.code).toBe('AB12CD');
    expect(component.codeUsable()).toBeTrue();
    expect(component.secondsLeft()).toBeGreaterThan(290);
    expect(component.countdownLabel()).toMatch(/^[45]:\d{2}$/);
  });

  it('masque un code expiré et propose d\'en générer un nouveau', () => {
    setup();
    service.createRunnerPairingCode.and.returnValue(of(codeExpiringIn(-1)));

    component.generateCode();

    expect(component.codeUsable()).toBeFalse();
    expect(component.codeExpired()).toBeTrue();
    expect(component.countdownLabel()).toBe('0:00');
    // La commande n'affiche jamais un code inutilisable : elle porte un marqueur explicite.
    expect(component.runCommand()).toContain('--code <code-appairage>');
  });

  it('signale l\'échec de génération sans fermer le dialogue', () => {
    setup();
    service.createRunnerPairingCode.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })));

    component.generateCode();

    expect(component.pairingCode()).toBeNull();
    expect(component.generationError()).toContain("n'a pas pu être généré");
    expect(dialogRef.close).not.toHaveBeenCalled();
  });

  it('compose la commande avec l\'origine, le chemin saisi et le code', () => {
    setup();
    component.format.set('jar');
    service.createRunnerPairingCode.and.returnValue(of(codeExpiringIn(300)));
    component.generateCode();
    component.workspacePath.set('  /home/moi/projet  ');

    const command = component.runCommand();

    expect(command).toContain('java -jar claude-runner.jar');
    // Le préfixe d'API n'est pas décoratif : sans lui, la requête d'appairage atteint le serveur
    // du frontend, qui répond 405 sur un POST vers une route d'application (F-38 / SF-38-06).
    expect(command).toContain(`--gateway ${window.location.origin}/api`);
    // Guillemets : sans eux, Git Bash mange les antislashs d'un chemin Windows (SF-38-23).
    expect(command).toContain('--workspace "/home/moi/projet"');
    expect(command).toContain('--code AB12CD');
  });

  it('propose un chemin d\'exemple tant que rien n\'est saisi', () => {
    // Le poste par défaut de ces tests est Windows, et le format retenu le paquet Windows : le
    // chemin d'exemple suit donc le format (F-45 / SF-45-02).
    setup();
    expect(component.runCommand()).toContain(`--workspace "${WINDOWS_WORKSPACE_PATH}"`);
  });

  it('traite un 404 de téléchargement comme un état normal, sans erreur technique', () => {
    setup();
    component.format.set('jar');
    service.downloadRunnerJar.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404 })));

    component.downloadJar();

    expect(component.jarUnavailable()).toBeTrue();
    expect(snackBar.open).not.toHaveBeenCalled();
    expect(component.buildCommand).toBe(RUNNER_BUILD_COMMAND);
  });

  it('signale un vrai échec de téléchargement', () => {
    setup();
    component.format.set('jar');
    service.downloadRunnerJar.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })));

    component.downloadJar();

    expect(component.jarUnavailable()).toBeFalse();
    expect(snackBar.open.calls.mostRecent().args[0])
      .toBe('Le téléchargement du runner a échoué.');
  });

  it('propose le paquet autonome par défaut et compose sa commande sans « java »', () => {
    // F-44 / SF-44-02 : sur le paquet, préfixer par `java` rappellerait la JVM du système —
    // celle-là même qui manque ou qui est trop ancienne. C'est tout l'objet de la feature.
    setup();
    service.createRunnerPairingCode.and.returnValue(of(codeExpiringIn(300)));
    component.generateCode();

    expect(component.format()).toBe('windows');
    expect(component.usesWindowsPackage()).toBeTrue();
    expect(component.runCommand()).toContain('claude-runner.cmd --gateway');
    expect(component.runCommand()).not.toContain('java -jar');
  });

  it('télécharge le paquet sous son propre nom', () => {
    setup();
    service.downloadRunnerWindowsPackage.and.returnValue(of(new Blob(['zip'])));
    const anchor = document.createElement('a');
    spyOn(anchor, 'click');
    spyOn(document, 'createElement').and.returnValue(anchor);

    component.downloadJar();

    expect(service.downloadRunnerWindowsPackage).toHaveBeenCalled();
    expect(service.downloadRunnerJar).not.toHaveBeenCalled();
    expect(anchor.download).toBe('claude-runner-windows-x64.zip');
  });

  it('retombe sur le jar quand la gateway n\'empaquette aucun paquet', () => {
    // D3 : une gateway déployée avant F-44 n'a aucun paquet. L'écran doit les masquer, pas offrir
    // un lien qui répondrait 404.
    setup({
      jar: true,
      windowsPackage: false,
      macosAarch64Package: false,
      macosX64Package: false,
    });

    expect(component.windowsPackageAvailable()).toBeFalse();
    expect(component.anyPackageAvailable()).toBeFalse();
    expect(component.format()).toBe('jar');
    expect(component.usesWindowsPackage()).toBeFalse();
    expect(component.runCommand()).toContain('java -jar claude-runner.jar');
  });

  it('retombe sur le jar si la disponibilité des formats est illisible', () => {
    setup();
    service.runnerDownloadFormats.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })));
    // Un nouveau composant, construit avec le service en échec.
    const rebuilt = TestBed.createComponent(RunnerPairingDialogComponent);
    rebuilt.detectChanges();

    expect(rebuilt.componentInstance.format()).toBe('jar');
    expect(rebuilt.componentInstance.windowsPackageAvailable()).toBeFalse();
  });

  it('enregistre le binaire quand la passerelle le sert', () => {
    setup();
    component.format.set('jar');
    service.downloadRunnerJar.and.returnValue(of(new Blob(['jar'])));
    const anchor = document.createElement('a');
    spyOn(anchor, 'click');
    spyOn(document, 'createElement').and.returnValue(anchor);

    component.downloadJar();

    expect(component.jarUnavailable()).toBeFalse();
    expect(anchor.download).toBe('claude-runner.jar');
    expect(anchor.click).toHaveBeenCalled();
  });

  it('reste lisible quand le presse-papiers est indisponible', () => {
    setup();
    const descriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });

    component.copy('texte', 'Code');

    expect(snackBar.open.calls.mostRecent().args[0]).toContain('Copie impossible');
    if (descriptor) {
      Object.defineProperty(navigator, 'clipboard', descriptor);
    } else {
      delete (navigator as unknown as { clipboard?: unknown }).clipboard;
    }
  });

  // ---------------------------------------------------------------------------------------------
  // F-44 / SF-44-03 — les paquets macOS
  // ---------------------------------------------------------------------------------------------

  it('présélectionne Apple Silicon sur un Mac et compose sa commande sans « java »', () => {
    // D4 : le navigateur ne sait pas distinguer arm64 d'Intel ; on présélectionne le majoritaire.
    // Et comme sur Windows, préfixer par `java` rappellerait la JVM du système — celle qui manque.
    setup(EVERY_FORMAT, 'macos');
    service.createRunnerPairingCode.and.returnValue(of(codeExpiringIn(300)));
    component.generateCode();

    expect(component.format()).toBe('macos-aarch64');
    expect(component.usesMacosPackage()).toBeTrue();
    expect(component.usesWindowsPackage()).toBeFalse();
    expect(component.runCommand()).toContain('./claude-runner.command --gateway');
    expect(component.runCommand()).not.toContain('java -jar');
    expect(component.runCommand()).toContain('--code AB12CD');
  });

  it('bascule sur Intel quand Apple Silicon n\'est pas servi', () => {
    setup({ ...EVERY_FORMAT, macosAarch64Package: false }, 'macos');

    expect(component.format()).toBe('macos-x64');
    expect(component.macosAarch64Available()).toBeFalse();
    expect(component.runCommand()).toContain('./claude-runner.command');
  });

  it('télécharge chaque paquet macOS par sa propre route, sous son propre nom', () => {
    setup(EVERY_FORMAT, 'macos');
    service.downloadRunnerMacosPackage.and.returnValue(of(new Blob(['tgz'])));
    const anchor = document.createElement('a');
    spyOn(anchor, 'click');
    spyOn(document, 'createElement').and.returnValue(anchor);

    component.downloadJar();

    expect(service.downloadRunnerMacosPackage).toHaveBeenCalledWith('aarch64');
    expect(anchor.download).toBe('claude-runner-macos-aarch64.tar.gz');

    component.format.set('macos-x64');
    component.downloadJar();

    expect(service.downloadRunnerMacosPackage).toHaveBeenCalledWith('x64');
    expect(anchor.download).toBe('claude-runner-macos-x64.tar.gz');
    expect(service.downloadRunnerJar).not.toHaveBeenCalled();
  });

  it('retombe sur le jar sur un poste qui n\'est ni Windows ni Mac', () => {
    // Linux reste hors périmètre de F-44 : ces postes ont un JDK, et 39 Mo pour en utiliser 2,5
    // serait absurde. Les paquets restent proposés — c'est la présélection qui change.
    setup(EVERY_FORMAT, 'other');

    expect(component.format()).toBe('jar');
    expect(component.anyPackageAvailable()).toBeTrue();
    expect(component.runCommand()).toContain('java -jar claude-runner.jar');
  });

  it('ne propose jamais un format que la gateway ne sert pas', () => {
    // Le format retenu ET servi : les deux conditions, sans quoi le bouton mènerait à un 404.
    setup({ ...EVERY_FORMAT, macosX64Package: false }, 'macos');
    component.format.set('macos-x64');

    expect(component.selectedPackage()).toBeNull();
    expect(component.usesMacosPackage()).toBeFalse();
    expect(component.runCommand()).toContain('java -jar claude-runner.jar');
  });

  // ---------------------------------------------------------------------------------------------
  // F-46 / SF-46-02 — la commande de reprise
  // ---------------------------------------------------------------------------------------------

  it('donne une commande de reprise sans passerelle, sans racine et sans code', () => {
    // Le geste quotidien n'est pas l'appairage : c'est la reprise. Depuis SF-46-01, le runner a
    // mémorisé la passerelle et la racine — la commande des fois suivantes n'a plus à les porter.
    setup(EVERY_FORMAT, 'other');
    component.format.set('jar');
    component.workspacePath.set('  /home/moi/projet  ');

    const resume = component.resumeCommand();

    expect(resume).not.toContain('--gateway');
    expect(resume).not.toContain('--workspace');
    expect(resume).not.toContain('--code');
    // Guillemets, comme la commande d'installation : le piège des antislashs est le même (SF-38-23).
    expect(resume).toBe('cd "/home/moi/projet" && java -jar claude-runner.jar');
  });

  it('reprend le chemin d\'exemple tant que rien n\'est saisi', () => {
    setup();

    expect(component.resumeCommand()).toContain(`cd "${WINDOWS_WORKSPACE_PATH}"`);
  });

  it('reprend avec le lanceur du paquet Windows, jamais avec « java »', () => {
    setup();

    expect(component.resumeCommand()).toContain('&& claude-runner.cmd');
    expect(component.resumeCommand()).not.toContain('java -jar');
  });

  it('reprend avec le lanceur du paquet macOS', () => {
    setup(EVERY_FORMAT, 'macos');

    expect(component.resumeCommand()).toContain('&& ./claude-runner.command');
    expect(component.resumeCommand()).toContain(`cd "${MACOS_WORKSPACE_PATH}"`);
  });

  it('laisse la commande de reprise intacte quand le code a expiré', () => {
    // Un code expiré abîme la commande d'installation — c'est voulu. Il n'a aucune raison
    // d'abîmer celle qui ne s'en sert pas.
    setup();
    service.createRunnerPairingCode.and.returnValue(of(codeExpiringIn(-1)));
    component.generateCode();
    component.workspacePath.set('C:\\Users\\moi\\projet');

    expect(component.runCommand()).toContain('--code <code-appairage>');
    expect(component.resumeCommand()).toBe('cd "C:\\Users\\moi\\projet" && claude-runner.cmd');
  });

  it('annonce le double-clic sur un paquet, et nomme son lanceur', () => {
    setup();

    expect(component.doubleClickAvailable()).toBeTrue();
    expect(component.launcherFilename()).toBe('claude-runner.cmd');

    // Le jar seul ne se double-clique pas utilement : il lui faudrait la JVM du système, celle-là
    // même que le paquet existe pour remplacer.
    component.format.set('jar');

    expect(component.doubleClickAvailable()).toBeFalse();
  });

  it('ne promet aucun double-clic sur un format que la gateway ne sert pas', () => {
    setup({ ...EVERY_FORMAT, macosX64Package: false }, 'macos');
    component.format.set('macos-x64');

    expect(component.selectedPackage()).toBeNull();
    expect(component.doubleClickAvailable()).toBeFalse();
    expect(component.resumeCommand()).toContain('java -jar claude-runner.jar');
  });

  it('lit le système depuis l\'User-Agent, tablettes exclues', () => {
    expect(detectHostPlatform('Mozilla/5.0 (Windows NT 10.0; Win64; x64)')).toBe('windows');
    expect(detectHostPlatform('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)')).toBe('macos');
    expect(detectHostPlatform('Mozilla/5.0 (X11; Linux x86_64)')).toBe('other');
    // Safari sur iPad annonce « Macintosh » ; on ne fait pas tourner un runner sur une tablette.
    expect(detectHostPlatform('Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X)')).toBe('other');
    expect(detectHostPlatform('')).toBe('other');
  });

  it("préfixe l'URL de la gateway par /api, faute de quoi l'appairage échoue en 405", () => {
    setup();

    // Le runner compose `{gateway}/runner/pair` : c'est ce préfixe, et lui seul, qui aiguille la
    // requête vers le backend plutôt que vers le serveur de l'application.
    expect(component.gatewayUrl).toBe(`${window.location.origin}/api`);
    expect(component.gatewayUrl.endsWith('/api')).toBeTrue();
  });
  // ---------------------------------------------------------------------------------------------
  // F-45 / SF-45-01 — étape « Vérifier l'accès réseau », avant le code et avant le téléchargement.
  // ---------------------------------------------------------------------------------------------

  it('propose curl.exe sur Windows — dans PowerShell, `curl` est un alias d\'Invoke-WebRequest', () => {
    setup(EVERY_FORMAT, 'windows');

    const command = component.networkCheckCommand;

    expect(command).toContain('curl.exe ');
    // Invoke-WebRequest emprunterait le proxy SYSTÈME et réussirait là où le runner échoue (D4).
    expect(command).not.toContain('Invoke-WebRequest');
    expect(command).toContain('-o NUL');
    expect(command).not.toContain('/dev/null');
  });

  it('propose curl sur macOS', () => {
    setup(EVERY_FORMAT, 'macos');

    expect(component.networkCheckCommand).toContain('curl -sS');
    expect(component.networkCheckCommand).not.toContain('curl.exe');
    expect(component.networkCheckCommand).toContain('-o /dev/null');
  });

  it('propose la commande générique sur un poste ni Windows ni Mac', () => {
    setup(EVERY_FORMAT, 'other');

    expect(component.networkCheckCommand).toContain('curl -sS');
    expect(component.networkCheckCommand).toContain('-o /dev/null');
  });

  it('vise exactement l\'adresse que le contrôle de vol du runner interroge', () => {
    setup();

    // D5 : deux adresses différentes autoriseraient un verdict vert à l'écran et rouge au lancement.
    expect(component.networkCheckCommand).toContain(
      `${window.location.origin}/api${NETWORK_CHECK_PATH}`);
    expect(NETWORK_CHECK_PATH).toBe('/runner/download/formats');
  });

  it('donne à chaque système sa commande de découverte du proxy, jamais rien', () => {
    expect(proxyDiscoveryCommand('windows')).toBe('netsh winhttp show proxy');
    expect(proxyDiscoveryCommand('macos')).toBe('scutil --proxy');
    // Un système non reconnu reçoit le geste générique : les variables sont ce que le runner lit.
    expect(proxyDiscoveryCommand('other')).toContain('proxy');
    expect(proxyDiscoveryCommand('other').length).toBeGreaterThan(0);
  });

  it('déclare le proxy avec la syntaxe du shell du poste', () => {
    expect(proxyExportCommand('windows', 'http://hote:port')).toBe(
      '$env:HTTPS_PROXY="http://hote:port"');
    expect(proxyExportCommand('macos', 'http://hote:port')).toBe(
      'export HTTPS_PROXY=http://hote:port');
    expect(proxyExportCommand('other', 'http://hote:port')).toBe(
      'export HTTPS_PROXY=http://hote:port');
  });

  it('nomme le relais local comme remède au 407, sur le shell du poste', () => {
    setup(EVERY_FORMAT, 'windows');

    expect(LOCAL_RELAY_PROXY_URL).toBe('http://127.0.0.1:3128');
    expect(component.relayExportCommand).toContain('127.0.0.1:3128');
    expect(component.relayExportCommand).toContain('$env:HTTPS_PROXY');
  });

  it('propose les trois issues du diagnostic, et le remède du 407 une fois déclaré', () => {
    setup(EVERY_FORMAT, 'windows');
    const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

    // Avant déclaration : la question et ses trois issues, pas les branches (SF-45-05, D2).
    expect(text()).toContain('Vérifier l\'accès réseau');
    expect(text()).toContain('Qu\'affiche votre terminal ?');
    expect(text()).toContain('200');
    expect(text()).toContain('407');
    expect(text()).not.toContain('relais local');
    // La limite est assumée à l'écran plutôt que cachée (SF-45-01, D7).
    expect(text()).toContain('ne lit pas la configuration proxy du poste');

    component.declareNetworkResult('proxy-auth');
    fixture.detectChanges();

    expect(text()).toContain('relais local');
    expect(text()).toContain('NTLM');
    expect(text()).toContain('Kerberos');
  });

  it('place la vérification réseau avant le téléchargement du runner', () => {
    setup();
    const titles = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.pairing-step-title'),
    ).map((el) => el.textContent?.replace(/\s+/g, ' ').trim() ?? '');

    expect(titles[0]).toContain('Vérifier l\'accès réseau');
    // Avant le code, et pas seulement avant le téléchargement : le code expire en 5 min (D1).
    expect(titles[1]).toContain('Générer un code');
    expect(titles[2]).toContain('Récupérer le runner');
    expect(titles[3]).toContain('Lancer le runner');
  });

  it('copie la commande de vérification comme les autres commandes', async () => {
    setup();
    const descriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    component.copy(component.networkCheckCommand, 'Commande');
    await Promise.resolve();

    expect(writeText).toHaveBeenCalledWith(component.networkCheckCommand);
    if (descriptor) {
      Object.defineProperty(navigator, 'clipboard', descriptor);
    } else {
      delete (navigator as unknown as { clipboard?: unknown }).clipboard;
    }
  });

  it('ne déclenche aucun appel réseau supplémentaire au chargement', () => {
    setup();

    // L'étape est du TEXTE : le poste exécute la commande, la page ne la joue pas à sa place (D2).
    expect(service.runnerDownloadFormats).toHaveBeenCalledTimes(1);
    expect(networkCheckCommand('windows', 'https://x/api')).toContain('https://x/api');
  });
  // ---------------------------------------------------------------------------------------------
  // F-45 / SF-45-02 — le format pilote le chemin, et l'écran dit si la machine s'est connectée.
  // ---------------------------------------------------------------------------------------------

  it('affiche un chemin Windows sous le paquet Windows', () => {
    setup(EVERY_FORMAT, 'windows');

    expect(component.examplePath()).toBe(WINDOWS_WORKSPACE_PATH);
    expect(component.examplePath()).toContain('C:\\');
    // Guillemets conservés : sans eux, Git Bash mange les antislashs (SF-38-23).
    expect(component.runCommand()).toContain(`--workspace "${WINDOWS_WORKSPACE_PATH}"`);
  });

  it('affiche un chemin macOS sous un paquet macOS', () => {
    setup(EVERY_FORMAT, 'macos');

    expect(component.examplePath()).toBe(MACOS_WORKSPACE_PATH);
    expect(component.examplePath()).toContain('/Users/');
  });

  it('fait suivre le poste consulté quand le format est le jar, qui ne dit rien du système', () => {
    // D1 : un paquet Windows ne s'exécute que sur Windows — information certaine. Le jar, lui, ne
    // porte aucun système ; c'est alors le poste d'où la page est consultée qui décide.
    setup(EVERY_FORMAT, 'other');
    expect(component.format()).toBe('jar');
    expect(component.examplePath()).toBe(DEFAULT_WORKSPACE_PATH);
  });

  it('donne un chemin Windows au jar consulté depuis Windows', () => {
    setup({ ...EVERY_FORMAT, windowsPackage: false }, 'windows');

    expect(component.selectedPackage()).toBeNull();
    expect(component.examplePath()).toBe(WINDOWS_WORKSPACE_PATH);
  });

  it('met le chemin d\'exemple à jour quand le format change', () => {
    setup(EVERY_FORMAT, 'windows');
    expect(component.examplePath()).toBe(WINDOWS_WORKSPACE_PATH);

    component.format.set('macos-aarch64');

    expect(component.examplePath()).toBe(MACOS_WORKSPACE_PATH);
  });

  it('ne remplace jamais un chemin saisi par l\'utilisateur', () => {
    setup(EVERY_FORMAT, 'windows');
    component.workspacePath.set('/home/moi/projet');

    component.format.set('macos-x64');

    expect(component.workspacePath()).toBe('/home/moi/projet');
    expect(component.runCommand()).toContain('--workspace "/home/moi/projet"');
  });

  it('relève l\'état de la machine dès l\'ouverture et reste en attente', () => {
    setup();

    expect(service.getRunnerStatus).toHaveBeenCalledWith('w1');
    expect(component.runnerConnected()).toBeFalse();
    expect(component.machineLabel()).toContain('En attente de la machine');
  });

  it('bascule sur « machine connectée » et cesse alors d\'interroger', () => {
    jasmine.clock().install();
    try {
      setup();
      const seenAt = new Date('2026-09-08T09:41:00Z').toISOString();
      service.getRunnerStatus.and.returnValue(of({ connected: true, lastSeenAt: seenAt }));

      jasmine.clock().tick(RUNNER_STATUS_POLL_MS);

      expect(component.runnerConnected()).toBeTrue();
      expect(component.machineLabel()).toContain('Machine connectée');
      expect(component.lastSeenLabel()).not.toBeNull();

      // D3 : la question posée par ce dialogue a sa réponse ; continuer serait de la surveillance.
      const callsSoFar = service.getRunnerStatus.calls.count();
      jasmine.clock().tick(RUNNER_STATUS_POLL_MS * 3);
      expect(service.getRunnerStatus.calls.count()).toBe(callsSoFar);
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('reste silencieux sur un relevé en échec, et retente', () => {
    jasmine.clock().install();
    try {
      setup();
      service.getRunnerStatus.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 500 })));

      jasmine.clock().tick(RUNNER_STATUS_POLL_MS);

      // D4 : ce dialogue est déjà celui où quelque chose ne marche pas ; un rouge de plus ferait
      // chercher au mauvais endroit.
      expect(snackBar.open).not.toHaveBeenCalled();
      expect(component.runnerConnected()).toBeFalse();

      const callsSoFar = service.getRunnerStatus.calls.count();
      jasmine.clock().tick(RUNNER_STATUS_POLL_MS);
      expect(service.getRunnerStatus.calls.count()).toBeGreaterThan(callsSoFar);
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('arrête le relevé sur un refus, qui ne se réparera pas tout seul', () => {
    jasmine.clock().install();
    try {
      setup();
      service.getRunnerStatus.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 403 })));

      jasmine.clock().tick(RUNNER_STATUS_POLL_MS);
      const callsSoFar = service.getRunnerStatus.calls.count();
      jasmine.clock().tick(RUNNER_STATUS_POLL_MS * 3);

      expect(service.getRunnerStatus.calls.count()).toBe(callsSoFar);
      expect(snackBar.open).not.toHaveBeenCalled();
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('arrête les deux minuteurs à la destruction du dialogue', () => {
    jasmine.clock().install();
    try {
      setup();
      service.createRunnerPairingCode.and.returnValue(of(codeExpiringIn(300)));
      component.generateCode();

      fixture.destroy();
      const callsSoFar = service.getRunnerStatus.calls.count();
      const secondsLeft = component.secondsLeft();
      jasmine.clock().tick(RUNNER_STATUS_POLL_MS * 3);

      expect(service.getRunnerStatus.calls.count()).toBe(callsSoFar);
      expect(component.secondsLeft()).toBe(secondsLeft);
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('affiche la tolérance de 90 s plutôt que de promettre du temps réel', () => {
    setup();
    component.toggleStep('launch');
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('En attente de la machine');
    expect(text).toContain('90 secondes');
  });
  // ---------------------------------------------------------------------------------------------
  // F-45 / SF-45-03 — la fiche « Pour votre DSI », generee par l'ecran.
  // ---------------------------------------------------------------------------------------------

  /** Date fixe : la fiche porte sa date de generation, qui ne doit pas rendre les tests instables. */
  const GENERATED_AT = new Date('2026-09-08T10:00:00Z');

  it('nomme le domaine et le port de la page consultée', () => {
    const sheet = itDepartmentSheet('https://portal.exemple.fr', GENERATED_AT);

    expect(sheet).toContain('portal.exemple.fr');
    // D3 : 443 vient du protocole de l'origine, jamais d'une constante ecrite en dur.
    expect(sheet).toContain('443');
  });

  it('reprend un port explicite plutôt que de supposer 443', () => {
    expect(itDepartmentSheet('https://portal.exemple.fr:8443', GENERATED_AT)).toContain('8443');
  });

  it('dit HTTP et WS sur une origine non chiffrée, jamais HTTPS ni WSS', () => {
    const sheet = itDepartmentSheet('http://localhost:4200', GENERATED_AT);

    expect(sheet).toContain('4200');
    // La ligne des protocoles, et elle seule : HTTPS_PROXY reste cité plus bas, c'est le nom d'une
    // variable d'environnement et non le protocole à ouvrir.
    expect(sheet).toContain('Protocoles : HTTP et WS');
    expect(sheet).not.toContain('Protocoles : HTTPS');
    expect(sheet).not.toContain('WSS');
  });

  it('décrit un flux sortant, sans aucun port entrant', () => {
    const sheet = itDepartmentSheet('https://portal.exemple.fr', GENERATED_AT);

    expect(sheet).toContain('Sortant uniquement');
    expect(sheet).toContain('Aucun port entrant');
  });

  it('nomme la limite de la JVM et les deux issues possibles', () => {
    const sheet = itDepartmentSheet('https://portal.exemple.fr', GENERATED_AT);

    // D5 : une contrainte du produit, pas un bogue — la DSI est la seule a pouvoir la lever.
    expect(sheet).toContain('NTLM');
    expect(sheet).toContain('Kerberos');
    expect(sheet).toContain('SSPI');
    expect(sheet).toContain('8u111');
    expect(sheet).toContain('exclure portal.exemple.fr');
    expect(sheet).toContain('relais local');
  });

  it('prévient du repli long-polling et de l\'interception TLS', () => {
    const sheet = itDepartmentSheet('https://portal.exemple.fr', GENERATED_AT);

    // D4 : un pare-feu applicatif peut autoriser HTTPS et refuser l'Upgrade WebSocket.
    expect(sheet).toContain('Upgrade');
    expect(sheet).toContain('long-polling');
    expect(sheet).toContain('trustStore');
  });

  it('dit qu\'aucun droit administrateur n\'est requis', () => {
    const sheet = itDepartmentSheet('https://portal.exemple.fr', GENERATED_AT);

    expect(sheet).toContain('Aucun droit administrateur');
    expect(sheet).toContain('Ctrl-C');
  });

  it('reste une fiche utilisable même sur une origine illisible', () => {
    const sheet = itDepartmentSheet('pas-une-url', GENERATED_AT);

    // Une fiche imparfaite vaut mieux qu'un bouton mort.
    expect(sheet.length).toBeGreaterThan(0);
    expect(sheet).toContain('NTLM');
  });

  it('ne divulgue rien du projet : ni code, ni nom, ni chemin', () => {
    // D6 : la fiche est faite pour sortir de l'ecran, collee dans un ticket. C'est le genre de
    // fuite qui s'ajoute par inadvertance a la premiere evolution — d'ou ce test.
    setup();
    service.createRunnerPairingCode.and.returnValue(of(codeExpiringIn(300, 'ZZ99YY')));
    component.generateCode();
    component.workspacePath.set('/home/moi/dossier-confidentiel');

    expect(component.itSheet).not.toContain('ZZ99YY');
    expect(component.itSheet).not.toContain('projet');
    expect(component.itSheet).not.toContain('dossier-confidentiel');
    expect(component.itSheet).not.toContain('w1');
  });

  it('enregistre la fiche sous un nom de fichier explicite', () => {
    setup();
    const anchor = document.createElement('a');
    spyOn(anchor, 'click');
    spyOn(document, 'createElement').and.returnValue(anchor);

    component.downloadItSheet();

    expect(anchor.download).toBe(IT_SHEET_FILENAME);
    expect(IT_SHEET_FILENAME).toBe('runner-acces-reseau-dsi.txt');
  });

  it('affiche le bloc « Pour votre DSI » sous une branche en échec, et seulement là', () => {
    setup();
    const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

    // D4 : la fiche n'a d'objet que s'il y a quelque chose à demander à la DSI.
    expect(text()).not.toContain('Pour votre DSI');

    component.declareNetworkResult('proxy-auth');
    fixture.detectChanges();

    expect(text()).toContain('Pour votre DSI');
    expect(text()).toContain('Copier la fiche');
    expect(text()).toContain('Télécharger');

    // Déclarer 200 ouvre l'étape 2 ; on rouvre l'étape 1 pour vérifier que la fiche a disparu.
    component.declareNetworkResult('reachable');
    component.toggleStep('network');
    fixture.detectChanges();

    expect(text()).not.toContain('Pour votre DSI');
  });
  // ---------------------------------------------------------------------------------------------
  // F-45 / SF-45-05 — le parcours guidé : une étape à la fois, et une conclusion visible.
  // ---------------------------------------------------------------------------------------------

  /** Texte rendu, espaces normalisés — les gabarits Angular en insèrent beaucoup. */
  function renderedText(): string {
    return ((fixture.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
  }

  it('ouvre la première étape et replie les autres', () => {
    setup();

    expect(component.step()).toBe('network');
    expect(component.isOpen('network')).toBeTrue();
    expect(component.isOpen('code')).toBeFalse();
    // Les en-têtes des quatre étapes restent rendus : on voit où l'on en est.
    const titles = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.pairing-step-title'),
    );
    expect(titles.length).toBe(4);
    // Le corps de l'étape 2 n'est pas rendu tant qu'elle est repliée.
    expect(renderedText()).not.toContain('Générer un nouveau code');
  });

  it('déplie l\'étape cliquée et replie celle qui l\'était', () => {
    setup();

    component.toggleStep('download');

    expect(component.isOpen('download')).toBeTrue();
    expect(component.isOpen('network')).toBeFalse();
  });

  it('replie l\'étape déjà ouverte, sans rien perdre du parcours', () => {
    setup();
    component.declareNetworkResult('proxy-auth');

    component.toggleStep('network');

    expect(component.step()).toBeNull();
    // Replier n'efface pas la déclaration : le parcours vit dans les signaux, pas dans le gabarit.
    expect(component.networkVerdict()).toBe('proxy-auth');
  });

  it('ne déplie aucune branche tant que l\'utilisateur n\'a rien déclaré', () => {
    setup();

    expect(component.networkVerdict()).toBe('unknown');
    expect(component.stepDone('network')).toBeFalse();
    expect(component.stepSummary('network')).toBe('');
    expect(renderedText()).not.toContain('could not resolve host');
  });

  it('fait avancer sur un 200, et sur lui seul', () => {
    setup();

    component.declareNetworkResult('reachable');

    expect(component.stepDone('network')).toBeTrue();
    expect(component.step()).toBe('code');
    expect(component.stepSummary('network')).toContain('atteint la passerelle');
  });

  it('reste sur l\'étape réseau quand le diagnostic échoue, sans rien verrouiller', () => {
    setup();

    component.declareNetworkResult('proxy-auth');

    // D3 : le 407 se lève côté DSI ; bloquer le parcours ferait de ce dialogue un piège.
    expect(component.step()).toBe('network');
    expect(component.stepDone('network')).toBeFalse();
    expect(component.stepSummary('network')).toContain('proxy');
    component.toggleStep('launch');
    expect(component.isOpen('launch')).toBeTrue();
  });

  it('déplie les commandes de proxy quand aucun code n\'est revenu', () => {
    setup();

    component.declareNetworkResult('no-answer');
    fixture.detectChanges();

    const text = renderedText();
    expect(text).toContain('could not resolve host');
    expect(text).toContain(component.proxyDiscoveryCommand);
    expect(text).toContain(component.proxyExportCommand);
    expect(component.stepSummary('network')).toContain('Aucune réponse');
  });

  it('permet de revenir au diagnostic : une déclaration se révise', () => {
    setup();
    component.declareNetworkResult('no-answer');

    component.resetNetworkResult();
    fixture.detectChanges();

    expect(component.networkVerdict()).toBe('unknown');
    expect(renderedText()).toContain('Qu\'affiche votre terminal ?');
  });

  it('marque le code comme fait et ouvre l\'étape du runner', () => {
    setup();
    service.createRunnerPairingCode.and.returnValue(of(codeExpiringIn(300)));

    component.generateCode();

    expect(component.stepDone('code')).toBeTrue();
    expect(component.stepSummary('code')).toContain('Code généré');
    expect(component.step()).toBe('download');
  });

  it('marque le runner comme récupéré après un téléchargement réussi', () => {
    setup();
    component.format.set('jar');
    service.downloadRunnerJar.and.returnValue(of(new Blob(['x'])));

    component.downloadJar();

    expect(component.stepDone('download')).toBeTrue();
    expect(component.stepSummary('download')).toContain('Runner récupéré');
    expect(component.step()).toBe('launch');
  });

  it('accepte « j\'ai déjà le runner » sans rien télécharger', () => {
    setup();

    component.markRunnerObtained();

    expect(component.stepDone('download')).toBeTrue();
    expect(service.downloadRunnerJar).not.toHaveBeenCalled();
    expect(service.downloadRunnerWindowsPackage).not.toHaveBeenCalled();
    expect(component.step()).toBe('launch');
  });

  it('replie tout et conclut dès que la machine est vue', () => {
    jasmine.clock().install();
    try {
      setup();
      const seenAt = new Date('2026-09-08T09:41:00Z').toISOString();
      service.getRunnerStatus.and.returnValue(
        of({ connected: true, lastSeenAt: seenAt, shell: 'posix' }));

      jasmine.clock().tick(RUNNER_STATUS_POLL_MS);
      fixture.detectChanges();

      // D6 : la conclusion REMPLACE le parcours — elle ne s'ajoute pas en bas de l'étape 4.
      expect(component.step()).toBeNull();
      expect(component.stepDone('launch')).toBeTrue();
      const text = renderedText();
      expect(text).toContain('Machine connectée');
      expect(text).toContain('projet');
      expect(text).toContain('bash');
      expect(text).toContain(component.lastSeenLabel()!);
      // Rien n'est verrouillé : chaque étape reste rouvrable.
      component.toggleStep('code');
      expect(component.isOpen('code')).toBeTrue();
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('nomme chaque interpréteur par un libellé écrit en dur, jamais par la valeur reçue', () => {
    // D8 : la valeur vient à l'origine d'une trame du runner ; elle n'est jamais rendue telle quelle.
    expect(shellLabel('posix')).toBe('bash');
    expect(shellLabel('powershell')).toBe('PowerShell');
    expect(shellLabel('cmd')).toBe('cmd.exe');
    expect(shellLabel('zsh-maison')).toBeNull();
    expect(shellLabel(null)).toBeNull();
    expect(shellLabel(undefined)).toBeNull();
  });

  it('omet la ligne d\'interpréteur quand aucun runner ne l\'a déclaré', () => {
    jasmine.clock().install();
    try {
      setup();
      service.getRunnerStatus.and.returnValue(
        of({ connected: true, lastSeenAt: new Date().toISOString() }));

      jasmine.clock().tick(RUNNER_STATUS_POLL_MS);
      fixture.detectChanges();

      // Jamais « inconnu » : un runner antérieur à SF-38-27 n'a simplement rien déclaré.
      expect(component.shellLabel()).toBeNull();
      expect(renderedText()).not.toContain('Interpréteur');
      expect(renderedText()).toContain('Machine connectée');
    } finally {
      jasmine.clock().uninstall();
    }
  });
});
