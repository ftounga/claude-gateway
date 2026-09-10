import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import type { RunnerHostPlatform } from './runner-pairing-dialog.component';
import { LOCAL_RELAY_PROXY_URL } from './runner-pairing-dialog.component';
import {
  DOMAIN_PLACEHOLDER,
  LOCAL_RELAY_URL,
  PROXY_ADDRESS_PLACEHOLDER,
  ProxyAssistantDialogComponent,
  ProxyAssistantDialogData,
  USER_PLACEHOLDER,
  cntlmConfig,
  cntlmHashCommand,
  integratedAuthCommands,
  mayUsePacFile,
  normalizeProxyAddress,
  proxyDeclareCommands,
  proxyLookupCommands,
  relayCheckCommand,
  relayDomainOrPlaceholder,
  relayOptions,
  relayUserOrPlaceholder,
  runnerRedirectCommands,
} from './proxy-assistant-dialog.component';

const CHECK_URL = 'https://portal.example.com/api/runner/download/formats';

describe('proxyLookupCommands (F-55 SF-55-01)', () => {
  it('donne les TROIS lectures Windows : WinHTTP, le navigateur, et le fichier PAC', () => {
    const commands = proxyLookupCommands('windows').map((c) => c.command);

    expect(commands.length).toBe(3);
    expect(commands[0]).toBe('netsh winhttp show proxy');
    expect(commands[1]).toContain('/v ProxyServer');
    expect(commands[2]).toContain('/v AutoConfigURL');
    expect(commands[1]).toContain('HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings');
  });

  it('dit pourquoi `netsh` peut mentir : il ne lit pas la configuration du navigateur', () => {
    const purposes = proxyLookupCommands('windows').map((c) => c.purpose).join(' ');

    expect(purposes).toContain('WinHTTP');
    expect(purposes).toContain('navigateur');
  });

  it('donne `scutil --proxy` sous macOS', () => {
    expect(proxyLookupCommands('macos').map((c) => c.command)).toEqual(['scutil --proxy']);
  });

  it('n\'est jamais vide sur un système non reconnu', () => {
    const commands = proxyLookupCommands('other');

    expect(commands.length).toBe(1);
    expect(commands[0].command).toBe('env | grep -i proxy');
  });

  it('ne propose le fichier PAC que là où il existe', () => {
    expect(mayUsePacFile('windows')).toBeTrue();
    expect(mayUsePacFile('macos')).toBeTrue();
    expect(mayUsePacFile('other')).toBeFalse();
  });
});

describe('normalizeProxyAddress (F-55 SF-55-01)', () => {
  it('laisse une adresse déjà propre', () => {
    expect(normalizeProxyAddress('px.corp:8080')).toBe('px.corp:8080');
  });

  it('retire le schéma, le chemin et la barre finale', () => {
    expect(normalizeProxyAddress('http://px.corp:8080/')).toBe('px.corp:8080');
    expect(normalizeProxyAddress('https://px.corp:3128/pac/proxy')).toBe('px.corp:3128');
  });

  it('retire les identifiants inline — un mot de passe collé ne ressort nulle part (D7)', () => {
    const normalized = normalizeProxyAddress('http://moi:secret@px.corp:8080');

    expect(normalized).toBe('px.corp:8080');
    expect(normalized).not.toContain('moi');
    expect(normalized).not.toContain('secret');
  });

  it('accepte un hôte sans port, et le laisse sans port', () => {
    expect(normalizeProxyAddress('px.corp')).toBe('px.corp');
  });

  it('accepte une adresse IP', () => {
    expect(normalizeProxyAddress('10.0.0.8:3128')).toBe('10.0.0.8:3128');
  });

  it('refuse ce qui n\'est pas exploitable', () => {
    expect(normalizeProxyAddress('   ')).toBeNull();
    expect(normalizeProxyAddress('http://')).toBeNull();
    expect(normalizeProxyAddress('px corp:8080')).toBeNull();
    expect(normalizeProxyAddress('px.corp:port')).toBeNull();
    expect(normalizeProxyAddress('px.corp:0')).toBeNull();
    expect(normalizeProxyAddress('px.corp:99999')).toBeNull();
    expect(normalizeProxyAddress(`px${'a'.repeat(300)}.corp:8080`)).toBeNull();
  });
});

describe('integratedAuthCommands (F-55 SF-55-01)', () => {
  it('teste Kerberos PUIS NTLM, et jamais l\'inverse', () => {
    const commands = integratedAuthCommands('macos', 'px.corp:8080', CHECK_URL);

    expect(commands.length).toBe(2);
    expect(commands[0].command).toContain('--proxy-negotiate');
    expect(commands[1].command).toContain('--proxy-ntlm');
  });

  it('n\'emporte AUCUN identifiant : `--proxy-user :` désigne la session ouverte (D4)', () => {
    for (const command of integratedAuthCommands('windows', 'px.corp:8080', CHECK_URL)) {
      expect(command.command).toContain('--proxy-user :');
    }
  });

  it('force le proxy avec `-x`, sans dépendre de l\'environnement du terminal (D3)', () => {
    const commands = integratedAuthCommands('other', 'px.corp:8080', CHECK_URL);

    expect(commands[0].command).toContain('-x http://px.corp:8080');
    expect(commands[0].command).toContain(CHECK_URL);
  });

  it('emploie `curl.exe` sous Windows, `curl` ailleurs', () => {
    expect(integratedAuthCommands('windows', 'px:8080', CHECK_URL)[0].command)
      .toMatch(/^curl\.exe /);
    expect(integratedAuthCommands('macos', 'px:8080', CHECK_URL)[0].command).toMatch(/^curl /);
  });
});

describe('proxyDeclareCommands (F-55 SF-55-01)', () => {
  it('donne les deux syntaxes Windows : PowerShell et l\'invite de commandes', () => {
    const commands = proxyDeclareCommands('windows', 'px.corp:8080');

    expect(commands.length).toBe(2);
    expect(commands[0].command).toContain('$env:HTTPS_PROXY="http://px.corp:8080"');
    expect(commands[1].command).toContain('set HTTPS_PROXY=http://px.corp:8080');
  });

  it('donne `export` ailleurs, pour HTTPS et HTTP', () => {
    const command = proxyDeclareCommands('macos', 'px.corp:8080')[0].command;

    expect(command).toContain('export HTTPS_PROXY=http://px.corp:8080');
    expect(command).toContain('export HTTP_PROXY=http://px.corp:8080');
  });
});

describe('relayOptions (F-55 SF-55-02)', () => {
  it('propose px sous Windows : winget d\'abord, puis le binaire autonome', () => {
    const options = relayOptions('windows', 'ntlm', 'px.corp:8080');

    expect(options.length).toBe(1);
    expect(options[0].kind).toBe('px');
    const commands = options[0].commands;
    expect(commands[0].command).toBe('winget install genotrance.px');
    expect(commands[0].purpose).toContain('WinHTTP');
    expect(commands[commands.length - 1].command).toContain('--proxy=px.corp:8080 --port=3128');
  });

  it('télécharge le relais À TRAVERS le proxy, avec l\'option du verdict', () => {
    const ntlm = relayOptions('windows', 'ntlm', 'px.corp:8080')[0].commands[1].command;
    const kerberos = relayOptions('windows', 'negotiate', 'px.corp:8080')[0].commands[1].command;

    expect(ntlm).toContain('--proxy-ntlm');
    expect(kerberos).toContain('--proxy-negotiate');
    expect(ntlm).toContain('-x http://px.corp:8080');
  });

  it('montre les DEUX relais sous macOS en NTLM — le navigateur ne sait pas trancher (D2)', () => {
    const options = relayOptions('macos', 'ntlm', 'px.corp:8080');

    expect(options.map((option) => option.kind)).toEqual(['px', 'cntlm']);
  });

  it('n\'offre JAMAIS cntlm en Kerberos : il ne sait pas le porter (D1)', () => {
    for (const platform of ['windows', 'macos', 'other'] as RunnerHostPlatform[]) {
      const kinds = relayOptions(platform, 'negotiate', 'px.corp:8080').map((o) => o.kind);
      expect(kinds).not.toContain('cntlm');
      expect(kinds).toContain('px');
    }
  });

  it('propose cntlm en premier sur un système non reconnu, en NTLM', () => {
    expect(relayOptions('other', 'ntlm', 'px.corp:8080').map((o) => o.kind))
      .toEqual(['cntlm', 'px']);
  });

  it('ne propose RIEN quand le proxy exige des identifiants applicatifs, ni avant le test', () => {
    expect(relayOptions('windows', 'refused', 'px.corp:8080')).toEqual([]);
    expect(relayOptions('windows', 'unknown', 'px.corp:8080')).toEqual([]);
  });
});

describe('cntlm — la configuration et le haché (F-55 SF-55-02)', () => {
  it('n\'écrit AUCUN mot de passe en clair (D4)', () => {
    const config = cntlmConfig('px.corp:8080', 'CORP', 'moi');

    expect(config).toContain('Username    moi');
    expect(config).toContain('Domain      CORP');
    expect(config).toContain('Proxy       px.corp:8080');
    expect(config).toContain('Listen      3128');
    expect(config).toContain('PassNTLMv2');
    expect(config).not.toMatch(/^Password/m);
  });

  it('produit le haché sur le poste, avec le domaine et l\'identifiant retenus', () => {
    expect(cntlmHashCommand('CORP', 'moi')).toBe('cntlm -H -d CORP -u moi');
  });

  it('retombe sur les marqueurs quand rien d\'exploitable n\'est saisi', () => {
    expect(relayDomainOrPlaceholder('')).toBe(DOMAIN_PLACEHOLDER);
    expect(relayDomainOrPlaceholder('   ')).toBe(DOMAIN_PLACEHOLDER);
    expect(relayDomainOrPlaceholder('CORP ; rm -rf')).toBe(DOMAIN_PLACEHOLDER);
    expect(relayUserOrPlaceholder('a'.repeat(65))).toBe(USER_PLACEHOLDER);
    expect(relayDomainOrPlaceholder('CORP')).toBe('CORP');
    expect(relayUserOrPlaceholder('CORP\\moi')).toBe('CORP\\moi');
  });
});

describe('vérifier puis rediriger (F-55 SF-55-02)', () => {
  it('vérifie SANS aucune option d\'authentification — c\'est tout l\'objet du test', () => {
    const command = relayCheckCommand('macos', CHECK_URL);

    expect(command).toContain(`-x ${LOCAL_RELAY_URL}`);
    expect(command).not.toContain('--proxy-ntlm');
    expect(command).not.toContain('--proxy-negotiate');
    expect(command).not.toContain('--proxy-user');
    expect(command).toContain(CHECK_URL);
  });

  it('emploie `curl.exe` sous Windows', () => {
    expect(relayCheckCommand('windows', CHECK_URL)).toMatch(/^curl\.exe /);
  });

  it('redirige le runner vers le relais, exclusions séparées par des VIRGULES', () => {
    const unix = runnerRedirectCommands('macos');

    expect(unix[0].command).toContain(`export HTTPS_PROXY=${LOCAL_RELAY_URL}`);
    expect(unix[0].command).toContain(`export HTTP_PROXY=${LOCAL_RELAY_URL}`);
    expect(unix[1].command).toContain('localhost,127.0.0.1');
    expect(unix[1].command).not.toContain(';');
  });

  it('donne la forme PowerShell sous Windows', () => {
    const windows = runnerRedirectCommands('windows');

    expect(windows[0].command).toContain(`$env:HTTPS_PROXY="${LOCAL_RELAY_URL}"`);
    expect(windows[1].command).toContain('$env:NO_PROXY=');
  });

  it('emploie la MÊME adresse de relais que le parcours de mise en service', () => {
    // Deux adresses différentes feraient vérifier un relais et en déclarer un autre.
    expect(LOCAL_RELAY_URL).toBe(LOCAL_RELAY_PROXY_URL);
  });
});

describe('ProxyAssistantDialogComponent (F-55 SF-55-01)', () => {
  let fixture: ComponentFixture<ProxyAssistantDialogComponent>;
  let component: ProxyAssistantDialogComponent;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  function setup(
    verdict: ProxyAssistantDialogData['verdict'] = null,
    platform: RunnerHostPlatform = 'windows',
  ): void {
    TestBed.resetTestingModule();
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [ProxyAssistantDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MatSnackBar, useValue: snackBar },
        {
          provide: MatDialogRef,
          useValue: jasmine.createSpyObj<MatDialogRef<ProxyAssistantDialogComponent>>(
            'MatDialogRef', ['close']),
        },
        {
          provide: MAT_DIALOG_DATA,
          useValue: { platform, checkUrl: CHECK_URL, verdict } as ProxyAssistantDialogData,
        },
      ],
    });

    fixture = TestBed.createComponent(ProxyAssistantDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  afterEach(() => fixture?.destroy());

  it('s\'ouvre sur la qualification quand le 407 est déjà constaté', () => {
    setup('proxy-auth');

    expect(component.step()).toBe('qualify');
    expect(text()).toContain('Qualifier ce qui refuse');
  });

  it('s\'ouvre sur la recherche d\'adresse quand rien n\'est su', () => {
    setup(null);

    expect(component.step()).toBe('address');
  });

  it('s\'ouvre sur la recherche d\'adresse quand aucune route n\'a répondu', () => {
    setup('no-answer');

    expect(component.step()).toBe('address');
  });

  it('laisse rouvrir n\'importe quelle étape à tout moment', () => {
    setup('proxy-auth');

    component.openStep('address');
    fixture.detectChanges();

    expect(component.step()).toBe('address');
    expect(component.isOpen('qualify')).toBeFalse();
  });

  it('affiche les trois lectures Windows et le geste du fichier PAC', () => {
    setup(null, 'windows');

    expect(text()).toContain('netsh winhttp show proxy');
    expect(text()).toContain('AutoConfigURL');
    expect(text()).toContain('PROXY hote:port');
  });

  it('ne parle pas de fichier PAC sur un système qui n\'en a pas', () => {
    setup(null, 'other');

    expect(text()).toContain('env | grep -i proxy');
    expect(text()).not.toContain('PROXY hote:port');
  });

  it('compose les commandes avec l\'adresse saisie', () => {
    setup('proxy-auth');

    component.proxyAddress.set('http://px.corp:8080/');
    fixture.detectChanges();

    expect(component.resolvedAddress()).toBe('px.corp:8080');
    expect(component.authCommands()[0].command).toContain('-x http://px.corp:8080');
    expect(component.declareCommands()[0].command).toContain('http://px.corp:8080');
    expect(component.addressInvalid()).toBeFalse();
  });

  it('garde le marqueur et le dit quand l\'adresse est inexploitable', () => {
    setup('proxy-auth');
    component.openStep('address');

    component.proxyAddress.set('pas une adresse');
    fixture.detectChanges();

    expect(component.addressInvalid()).toBeTrue();
    expect(component.commandAddress()).toBe(PROXY_ADDRESS_PLACEHOLDER);
    expect(component.authCommands()[0].command).toContain(PROXY_ADDRESS_PLACEHOLDER);
    expect(text()).toContain('n\'est pas exploitable');
  });

  it('ne conclut rien tant que rien n\'est déclaré', () => {
    setup('proxy-auth');

    expect(component.authVerdict()).toBe('unknown');
    expect(text()).not.toContain('SSPI');
    expect(text()).not.toContain('identifiants applicatifs');
  });

  it('conclut sur le relais local, et donne le motif, quand Kerberos passe', () => {
    setup('proxy-auth');

    component.declareAuthResult('negotiate');
    fixture.detectChanges();

    expect(text()).toContain('relais local');
    expect(text()).toContain('SSPI');
    expect(text()).toContain('8u111');
    // Kerberos : `cntlm` ne porte que NTLM — le conseiller ferait tout réinstaller.
    expect(text()).toContain('cntlm');
    expect(text()).toContain('ne porte');
  });

  it('accepte NTLM comme les deux relais', () => {
    setup('proxy-auth');

    component.declareAuthResult('ntlm');
    fixture.detectChanges();

    expect(text()).toContain('relais local');
    expect(text()).toContain('NTLM');
  });

  it('renvoie à la DSI, sans rien à installer, quand les deux tests échouent', () => {
    setup('proxy-auth');

    component.declareAuthResult('refused');
    fixture.detectChanges();

    expect(text()).toContain('identifiants applicatifs');
    expect(text()).toContain('DSI');
    // Rien à installer : un relais s'authentifierait de la même façon, et échouerait pareil.
    expect(text()).toContain('rien à installer ici');
    expect(text()).not.toContain('127.0.0.1');
  });

  it('révise la déclaration', () => {
    setup('proxy-auth');
    component.declareAuthResult('refused');

    component.resetAuthResult();
    fixture.detectChanges();

    expect(component.authVerdict()).toBe('unknown');
    expect(text()).toContain('Laquelle a répondu');
  });

  it('sépare les deux pannes et leurs deux remèdes (D5)', () => {
    setup('proxy-auth');

    expect(text()).toContain('407');
    expect(text()).toContain('Aucun proxy n\'est déclaré dans ce terminal');
    expect(text()).toContain('Could not resolve host');
  });

  it('prévient quand le presse-papiers est indisponible', () => {
    setup('proxy-auth');
    const descriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
    try {
      component.copy('curl', 'Commande');
    } finally {
      if (descriptor) {
        Object.defineProperty(navigator, 'clipboard', descriptor);
      } else {
        delete (navigator as unknown as { clipboard?: unknown }).clipboard;
      }
    }

    expect(snackBar.open).toHaveBeenCalledWith(
      'Copie impossible : sélectionnez le texte manuellement.', 'Fermer', { duration: 4000 });
  });

  // -----------------------------------------------------------------------------------------
  // SF-55-02 — le relais local : installer, vérifier, PUIS rediriger.
  // -----------------------------------------------------------------------------------------

  it('porte quatre étapes, une seule ouverte à la fois', () => {
    setup('proxy-auth');

    component.openStep('relay');
    expect(component.isOpen('relay')).toBeTrue();
    expect(component.isOpen('qualify')).toBeFalse();

    component.openStep('verify');
    expect(component.isOpen('verify')).toBeTrue();
    expect(component.isOpen('relay')).toBeFalse();
  });

  it('renvoie au test tant que rien n\'est déclaré, plutôt que de faire installer', () => {
    setup('proxy-auth');

    component.openStep('relay');
    fixture.detectChanges();

    expect(component.relays()).toEqual([]);
    expect(text()).toContain('Faites d\'abord le test de l\'étape 2');
  });

  it('ne propose aucun relais quand le proxy exige des identifiants applicatifs', () => {
    setup('proxy-auth');
    component.declareAuthResult('refused');

    component.openStep('relay');
    fixture.detectChanges();

    expect(component.relays()).toEqual([]);
    expect(text()).toContain('Rien à installer ici');
    expect(text()).not.toContain('winget');
  });

  it('compose la configuration cntlm avec le domaine et l\'identifiant saisis', () => {
    setup('proxy-auth', 'other');
    component.declareAuthResult('ntlm');
    component.proxyAddress.set('px.corp:8080');
    component.relayDomain.set('CORP');
    component.relayUser.set('moi');

    component.openStep('relay');
    fixture.detectChanges();

    expect(component.cntlmProposed()).toBeTrue();
    expect(component.cntlmHashCommand()).toBe('cntlm -H -d CORP -u moi');
    expect(component.cntlmConfig()).toContain('Proxy       px.corp:8080');
    expect(text()).toContain('haché');
    expect(text()).toContain('chmod 600 cntlm.conf');
  });

  it('n\'expose AUCUN champ de mot de passe', () => {
    setup('proxy-auth', 'other');
    component.declareAuthResult('ntlm');
    component.openStep('relay');
    fixture.detectChanges();

    const passwords = (fixture.nativeElement as HTMLElement).querySelectorAll('input[type="password"]');
    expect(passwords.length).toBe(0);
    expect(text().toLowerCase()).not.toContain('mot de passe du proxy');
  });

  it('n\'affiche AUCUNE redirection tant que le relais n\'est pas vérifié (D3)', () => {
    setup('proxy-auth');
    component.declareAuthResult('ntlm');

    component.openStep('verify');
    fixture.detectChanges();

    expect(component.relayVerified()).toBe('unknown');
    expect(text()).not.toContain('HTTPS_PROXY');
    expect(text()).toContain('second');
  });

  it('affiche la redirection et le piège du séparateur une fois le 200 déclaré', () => {
    setup('proxy-auth');
    component.declareAuthResult('ntlm');
    component.openStep('verify');

    component.declareRelayResult('carried');
    fixture.detectChanges();

    expect(text()).toContain('HTTPS_PROXY');
    expect(text()).toContain('127.0.0.1:3128');
    expect(text()).toContain('virgules');
    expect(text()).toContain('rester ouvert');
  });

  it('dit quoi revoir quand la vérification échoue, et ne redirige pas', () => {
    setup('proxy-auth');
    component.declareAuthResult('ntlm');
    component.openStep('verify');

    component.declareRelayResult('failed');
    fixture.detectChanges();

    expect(text()).toContain('ne porte pas encore');
    expect(text()).not.toContain('HTTPS_PROXY');
  });

  it('révise la déclaration de vérification', () => {
    setup('proxy-auth');
    component.openStep('verify');
    component.declareRelayResult('carried');

    component.resetRelayResult();
    fixture.detectChanges();

    expect(component.relayVerified()).toBe('unknown');
    expect(text()).toContain('Qu\'affiche ce second terminal ?');
  });
});
