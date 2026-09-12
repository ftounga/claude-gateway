import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { AtelierService } from '../../core/services/atelier.service';
import {
  RUNNER_HOST_PLATFORM,
  RunnerHostPlatform,
  RunnerPairingDialogComponent,
} from './runner-pairing-dialog.component';

/**
 * **Mode poste** (F-72 / SF-72-02) : le parcours de mise en service part de la **machine**, et non
 * d'un projet.
 *
 * <p>C'est l'inversion que F-72 apporte. Le PO créait « EDENRED » en croyant déclarer un client,
 * obtenait un <b>projet</b>, puis on lui redemandait un <b>poste</b> — et il retapait le même nom.
 * Ici, une seule question : le nom. Et <b>aucun projet n'est créé</b>, ce que l'écran dit.</p>
 *
 * <p>Fichier à part du parcours « projet » : les deux modes ont des données d'ouverture différentes,
 * et mêler les deux montages dans un seul <code>setup</code> rendrait chaque test moins lisible que
 * ce qu'il affirme.</p>
 */
describe('RunnerPairingDialogComponent — mode poste (F-72 SF-72-02)', () => {
  let fixture: ComponentFixture<RunnerPairingDialogComponent>;
  let component: RunnerPairingDialogComponent;
  let service: jasmine.SpyObj<AtelierService>;

  function setupHostMode(): void {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'createHostPairingCode',
      'listRunnerHosts',
      'createRunnerHost',
      'attachWorkspaceToHost',
      'runnerHostFolders',
      'downloadRunnerJar',
      'downloadRunnerWindowsPackage',
      'downloadRunnerMacosPackage',
      'runnerDownloadFormats',
      'getRunnerStatus',
      'getHostRunnerStatus',
    ]);
    service.runnerDownloadFormats.and.returnValue(of({
      jar: true, windowsPackage: true, macosAarch64Package: true, macosX64Package: true,
    }));
    service.getHostRunnerStatus.and.returnValue(of({ connected: false, lastSeenAt: null }));
    service.getRunnerStatus.and.returnValue(of({ connected: false, lastSeenAt: null }));
    service.listRunnerHosts.and.returnValue(of([]));
    service.createRunnerHost.and.returnValue(of({
      id: 'h9', name: 'EDENRED', connected: false, createdAt: '2026-09-12T08:00:00Z',
    }));

    TestBed.configureTestingModule({
      imports: [RunnerPairingDialogComponent],
      providers: [
        provideNoopAnimations(),
        // Le refus d'accès CONDUIT (F-85 / SF-85-04) : la fenêtre a désormais un routeur.
        provideRouter([]),
        { provide: AtelierService, useValue: service },
        {
          provide: MatSnackBar,
          useValue: jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']),
        },
        {
          provide: MatDialogRef,
          useValue: jasmine.createSpyObj<MatDialogRef<RunnerPairingDialogComponent>>(
            'MatDialogRef', ['close']),
        },
        // L'ABSENCE de projet EST le mode : un drapeau pourrait contredire les données, l'absence
        // non.
        { provide: MAT_DIALOG_DATA, useValue: {} },
        { provide: RUNNER_HOST_PLATFORM, useValue: 'windows' as RunnerHostPlatform },
      ],
    });

    fixture = TestBed.createComponent(RunnerPairingDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  afterEach(() => {
    TestBed.resetTestingModule();
  });

  it("se reconnaît au seul fait qu'aucun projet ne lui est passé", () => {
    setupHostMode();

    expect(component.hostMode).toBeTrue();
  });

  it('ne propose ni liste de postes, ni explorateur, ni rattachement', () => {
    setupHostMode();
    component.toggleStep('host');
    fixture.detectChanges();

    const dom = fixture.nativeElement as HTMLElement;
    // Aucun sélecteur de poste : on en CRÉE un, on n'en choisit pas.
    expect(dom.querySelector('mat-select')).toBeNull();
    // Aucun explorateur : la machine n'est pas encore là pour lister.
    expect(dom.querySelector('.pairing-browser')).toBeNull();
    expect(text()).not.toContain('Rattacher ce projet');
    expect(service.runnerHostFolders).not.toHaveBeenCalled();
    // Et aucune liste de postes n'est relevée : elle n'aurait rien à en faire.
    expect(service.listRunnerHosts).not.toHaveBeenCalled();
  });

  it("ne demande que le nom, et dit qu'aucun projet ne sera créé", () => {
    setupHostMode();
    component.toggleStep('host');
    fixture.detectChanges();

    expect(text()).toContain('Nom du client ou de la machine');
    expect(text()).toContain('Aucun projet ne sera créé ici');
  });

  it("garde « Créer le poste » inerte tant qu'aucun nom n'est saisi", () => {
    setupHostMode();

    expect(component.canCreateHost()).toBeFalse();

    component.createHost();

    expect(service.createRunnerHost).not.toHaveBeenCalled();
  });

  it("crée le poste, puis enchaîne sur le code d'appairage", () => {
    setupHostMode();
    component.newHostName.set('  EDENRED  ');

    component.createHost();

    expect(service.createRunnerHost).toHaveBeenCalledOnceWith('EDENRED');
    expect(component.hostId()).toBe('h9');
    // Un code d'appairage appartient à une machine : il ne pouvait pas être demandé avant.
    expect(component.step()).toBe('code');
  });

  it("relève l'état sur LE POSTE, jamais sur un projet qui n'existe pas", () => {
    setupHostMode();
    component.newHostName.set('EDENRED');

    component.createHost();

    expect(service.getHostRunnerStatus).toHaveBeenCalledWith('h9');
    expect(service.getRunnerStatus).not.toHaveBeenCalled();
  });

  it("ne relève rien tant que le poste n'existe pas", () => {
    setupHostMode();

    expect(service.getHostRunnerStatus).not.toHaveBeenCalled();
  });

  it('conserve le nom saisi quand la création échoue', () => {
    setupHostMode();
    service.createRunnerHost.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })),
    );
    component.toggleStep('host');
    component.newHostName.set('EDENRED');

    component.createHost();
    fixture.detectChanges();

    // Retaper le nom serait, très exactement, la friction que F-72 supprime.
    expect(component.newHostName()).toBe('EDENRED');
    expect(component.hostId()).toBeNull();
    expect(text()).toContain("Le poste n'a pas pu être créé");
  });

  it('reprend le refus de la gateway sur un nom invalide', () => {
    setupHostMode();
    service.createRunnerHost.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { error: 'invalid_host_name', message: 'Nom de poste trop long.' },
    })));
    component.toggleStep('host');
    component.newHostName.set('x');

    component.createHost();
    fixture.detectChanges();

    expect(text()).toContain('Nom de poste trop long.');
  });

  it("dit, une fois la machine vue, qu'aucun projet n'existe encore — et que c'est normal", () => {
    setupHostMode();
    service.getHostRunnerStatus.and.returnValue(of({
      connected: true, lastSeenAt: '2026-09-12T08:00:00Z',
    }));
    component.newHostName.set('EDENRED');

    component.createHost();
    fixture.detectChanges();

    expect(component.runnerConnected()).toBeTrue();
    expect(text()).toContain("Aucun projet n'existe encore");
    expect(text()).toContain('Ajouter un projet');
  });

  it("résume l'étape par le poste créé, sans parler de projet", () => {
    setupHostMode();
    component.newHostName.set('EDENRED');

    component.createHost();

    expect(component.stepSummary('host')).toBe('Poste « EDENRED » créé.');
  });
  // ------------------------------------ refus d'accès (F-85 / SF-85-04)

  /**
   * **Le geste exact de l'incident du 12/09** : le prospect clique « Connecter un poste ». La garde
   * refuse — et l'écran lui répondait « Veuillez réessayer », c'est-à-dire l'envoyait refaire ce
   * qui échouera toujours.
   */
  describe("un accès refusé dit pourquoi, et où aller", () => {
    it("ne dit plus « réessayer » quand le code d'appairage est REFUSÉ", () => {
      setupHostMode();
      component.hostId.set('h9');
      service.createHostPairingCode.and.returnValue(
        throwError(() => new HttpErrorResponse({
          status: 403,
          error: { error: 'atelier_forbidden', message: "La Forge demande l'offre Gold." },
        })),
      );

      component.generateCode();

      const message = component.generationError() ?? '';
      expect(message).toContain("n'est pas ouvert");
      expect(message).toContain('souscrire');
      expect(message).toContain("code d'accès");
      expect(message).not.toContain('réessayer');
      expect(component.accessRefused()).toBeTrue();
    });

    it("garde son message quand le poste n'existe plus — ce n'est pas un refus d'accès", () => {
      setupHostMode();
      component.hostId.set('h9');
      service.createHostPairingCode.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 404 })),
      );

      component.generateCode();

      expect(component.generationError()).toBe("Ce poste n'existe plus.");
      expect(component.accessRefused()).toBeFalse();
    });

    it("nomme les deux sorties quand la CRÉATION du poste est refusée, sans perdre le nom", () => {
      setupHostMode();
      component.toggleStep('host');
      component.newHostName.set('EDENRED');
      service.createRunnerHost.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 403 })),
      );

      component.createHost();
      fixture.detectChanges();

      expect(component.attachError() ?? '').toContain("code d'accès");
      // Non-régression F-72 : retaper le nom serait la friction que la feature supprime.
      expect(component.newHostName()).toBe('EDENRED');
      expect(text()).toContain('Où saisir mon code');
    });

    it("conduit à la section du code, la fenêtre refermée d'abord", () => {
      setupHostMode();
      const router = TestBed.inject(Router);
      const navigate = spyOn(router, 'navigate');
      const dialogRef = TestBed.inject(MatDialogRef);

      component.goToAccessCode();

      // Fermée d'abord : naviguer derrière un dialogue ouvert laisserait l'utilisateur devant un
      // parcours qui ne peut plus aboutir.
      expect(dialogRef.close).toHaveBeenCalled();
      expect(navigate).toHaveBeenCalledWith(['/billing'], { fragment: 'code-acces' });
    });
  });
});
