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
  LOCAL_RELAY_PROXY_URL,
  NETWORK_CHECK_PATH,
  RUNNER_BUILD_COMMAND,
  RUNNER_HOST_PLATFORM,
  RunnerHostPlatform,
  RunnerPairingDialogComponent,
  detectHostPlatform,
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
    ]);
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
    setup();
    expect(component.runCommand()).toContain(`--workspace "${DEFAULT_WORKSPACE_PATH}"`);
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

  it('affiche les trois branches de lecture et le remède du 407', () => {
    setup(EVERY_FORMAT, 'windows');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('Vérifier l\'accès réseau');
    expect(text).toContain('200');
    expect(text).toContain('407');
    expect(text).toContain('relais local');
    expect(text).toContain('NTLM');
    expect(text).toContain('Kerberos');
    // La limite est assumée à l'écran plutôt que cachée (D7).
    expect(text).toContain('ne lit pas la configuration proxy du poste');
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
});
