import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import type { RunnerHostPlatform } from './runner-pairing-dialog.component';
import {
  PROXY_ADDRESS_PLACEHOLDER,
  ProxyAssistantDialogComponent,
  ProxyAssistantDialogData,
  integratedAuthCommands,
  mayUsePacFile,
  normalizeProxyAddress,
  proxyDeclareCommands,
  proxyLookupCommands,
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
});
