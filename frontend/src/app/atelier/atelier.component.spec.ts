import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, ParamMap, Router, provideRouter } from '@angular/router';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { MAX_UPLOAD_BYTES } from '../shared/http-error.util';

import { AtelierComponent, toThreadItem } from './atelier.component';
import { AtelierService } from '../core/services/atelier.service';
import { ApiKeyService } from '../core/services/api-key.service';
import { ApiKeyStatus } from '../core/models/api-key.models';
import {
  AtelierMessage,
  FileContent,
  WorkspaceDetail,
  WorkspaceSummary,
} from '../core/models/atelier.models';

describe('AtelierComponent', () => {
  let fixture: ComponentFixture<AtelierComponent>;
  let component: AtelierComponent;
  let service: jasmine.SpyObj<AtelierService>;
  let apiKeyService: jasmine.SpyObj<ApiKeyService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let dialog: jasmine.SpyObj<MatDialog>;

  const hostedStatus: ApiKeyStatus = {
    present: false,
    maskedKey: null,
    last4: null,
    provider: null,
    mode: 'HOSTED',
    validatedAt: null,
    createdAt: null,
  };
  const byokStatus: ApiKeyStatus = {
    present: true,
    maskedKey: 'sk-…a1b2',
    last4: 'a1b2',
    provider: 'anthropic',
    mode: 'BYOK',
    validatedAt: '2026-07-11T00:00:00Z',
    createdAt: '2026-07-11T00:00:00Z',
  };

  const summary: WorkspaceSummary = {
    id: 'w1',
    name: 'projet',
    createdAt: '2026-07-11T00:00:00Z',
    source: 'ARCHIVE',
    gitRepo: null,
  };
  const detail: WorkspaceDetail = {
    id: 'w1',
    name: 'projet',
    fileCount: 1,
    files: ['src/main.ts'],
    createdAt: '2026-07-11T00:00:00Z',
    source: 'ARCHIVE',
    gitRepoUrl: null,
    gitRepo: null,
    gitBranch: null,
    truncated: false,
  };

  /** Projet adossé à un dépôt (F-31 / SF-31-02). */
  const gitDetail: WorkspaceDetail = {
    id: 'w2',
    name: 'hello',
    fileCount: 0,
    files: [],
    createdAt: '2026-08-25T00:00:00Z',
    source: 'GIT',
    gitRepoUrl: 'https://github.com/octocat/hello',
    gitRepo: 'octocat/hello',
    gitBranch: 'main',
    truncated: false,
  };

  function setup(): void {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'createWorkspace',
      'createGitWorkspace',
      'createLocalWorkspace',
      'pushBranch',
      'createPullRequest',
      'listWorkspaces',
      'getWorkspace',
      'getFile',
      'writeFile',
      'importLibrary',
      'chat',
      'streamChat',
      'streamAgent',
      'resetAgentSession',
      'renameWorkspace',
      'interruptAgentSession',
      'interruptChat',
      'setAskBeforeBash',
      'confirmToolUse',
      'confirmChatToolUse',
      'getHistory',
      'getResume',
      'restartThread',
      'setExecutionTarget',
      'getRunnerStatus',
      'getEngine',
      'createRunnerPairingCode',
      'downloadRunnerJar',
    ]);
    apiKeyService = jasmine.createSpyObj<ApiKeyService>('ApiKeyService', ['getStatus']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    // Valeurs par défaut : la liste est chargée à l'init.
    service.listWorkspaces.and.returnValue(of([summary]));
    // Aucun runner par défaut : le sondage d'état ne doit jamais faire tomber un test d'écran.
    service.getRunnerStatus.and.returnValue(of({ connected: false, lastSeenAt: null }));
    service.getWorkspace.and.returnValue(of(detail));
    service.getHistory.and.returnValue(of([]));
    // Projet d'archive en bac à sable : la gateway rend donc le moteur hébergé (SF-39-07).
    service.getEngine.and.returnValue(of({
      engine: 'HOSTED_SANDBOX' as const, runnerConnected: false, runnerLastSeenAt: null,
      recommendRunner: false, recommendReason: null,
    }));
    // SF-39-04 : par défaut, la reprise du fil ne demande rien.
    service.getResume.and.returnValue(
      of({ turns: 0, lastMessageAt: null, threadStartedAt: null, prompt: 'NONE' as const }),
    );
    apiKeyService.getStatus.and.returnValue(of(hostedStatus));

    TestBed.configureTestingModule({
      imports: [AtelierComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AtelierService, useValue: service },
        { provide: ApiKeyService, useValue: apiKeyService },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialog, useValue: dialog },
      ],
    });

    fixture = TestBed.createComponent(AtelierComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('loads the workspace list on init', () => {
    setup();
    expect(service.listWorkspaces).toHaveBeenCalled();
    expect(component.workspaces()).toEqual([summary]);
  });

  it('affiche le panneau d\'upsell (sans snackbar) sur un 403 atelier_forbidden', () => {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'createWorkspace',
      'createGitWorkspace',
      'pushBranch',
      'createPullRequest',
      'listWorkspaces',
      'getWorkspace',
      'getEngine',
      'getFile',
      'writeFile',
      'importLibrary',
      'chat',
      'streamChat',
      'getHistory',
      'getResume',
      'restartThread',
    ]);
    apiKeyService = jasmine.createSpyObj<ApiKeyService>('ApiKeyService', ['getStatus']);
    apiKeyService.getStatus.and.returnValue(of(hostedStatus));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    service.listWorkspaces.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 403,
            error: { error: 'atelier_forbidden', message: 'Réservé à l\'offre Gold.' },
          }),
      ),
    );

    TestBed.configureTestingModule({
      imports: [AtelierComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AtelierService, useValue: service },
        { provide: ApiKeyService, useValue: apiKeyService },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(AtelierComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    expect(component.accessDenied()).toBeTrue();
    expect(snackBar.open).not.toHaveBeenCalled();
    // Le statut de clé n'est pas interrogé quand l'accès est refusé.
    expect(apiKeyService.getStatus).not.toHaveBeenCalled();
  });

  it('charge le mode d\'exécution après un accès accordé et détecte BYOK', () => {
    setup();
    apiKeyService.getStatus.and.returnValue(of(byokStatus));
    // Recharge explicite pour appliquer le statut BYOK.
    (component as unknown as { loadProviderMode: () => void }).loadProviderMode();

    expect(component.accessDenied()).toBeFalse();
    expect(apiKeyService.getStatus).toHaveBeenCalled();
    expect(component.providerMode()).toBe('BYOK');
    expect(component.maskedKey()).toBe('sk-…a1b2');
  });

  it('reste en mode Hosted par défaut quand aucune clé n\'est présente', () => {
    setup();
    expect(apiKeyService.getStatus).toHaveBeenCalled();
    expect(component.providerMode()).toBe('HOSTED');
    expect(component.maskedKey()).toBeNull();
  });

  it('sur une 500, montre le message d\'erreur sans passer en upsell', () => {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'createWorkspace',
      'createGitWorkspace',
      'pushBranch',
      'createPullRequest',
      'listWorkspaces',
      'getWorkspace',
      'getEngine',
      'getFile',
      'writeFile',
      'importLibrary',
      'chat',
      'streamChat',
      'getHistory',
      'getResume',
      'restartThread',
    ]);
    apiKeyService = jasmine.createSpyObj<ApiKeyService>('ApiKeyService', ['getStatus']);
    apiKeyService.getStatus.and.returnValue(of(hostedStatus));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    service.listWorkspaces.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })),
    );

    TestBed.configureTestingModule({
      imports: [AtelierComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AtelierService, useValue: service },
        { provide: ApiKeyService, useValue: apiKeyService },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(AtelierComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    expect(component.accessDenied()).toBeFalse();
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('notifies when the workspace list fails to load', () => {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'createWorkspace',
      'createGitWorkspace',
      'pushBranch',
      'createPullRequest',
      'listWorkspaces',
      'getWorkspace',
      'getEngine',
      'getFile',
      'writeFile',
      'importLibrary',
      'chat',
      'streamChat',
      'getHistory',
      'getResume',
      'restartThread',
    ]);
    apiKeyService = jasmine.createSpyObj<ApiKeyService>('ApiKeyService', ['getStatus']);
    apiKeyService.getStatus.and.returnValue(of(hostedStatus));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    service.listWorkspaces.and.returnValue(throwError(() => new Error('boom')));

    TestBed.configureTestingModule({
      imports: [AtelierComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AtelierService, useValue: service },
        { provide: ApiKeyService, useValue: apiKeyService },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(AtelierComponent);
    fixture.detectChanges();

    expect(snackBar.open).toHaveBeenCalled();
  });

  it('creates a workspace when a zip is picked and opens it', () => {
    setup();
    service.createWorkspace.and.returnValue(of(detail));
    const file = new File(['zip'], 'projet.zip', { type: 'application/zip' });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;

    // Le nommage à la création (F-28 SF-28-16) passe par le dialogue de saisie.
    dialog.open.and.returnValue({
      afterClosed: () => of('projet'),
    } as MatDialogRef<unknown, unknown>);
    component.onZipPicked(event);

    // Le nom saisi accompagne désormais l'archive (F-28 SF-28-16).
    expect(service.createWorkspace).toHaveBeenCalledWith(file, 'projet');
    expect(component.activeWorkspaceId()).toBe('w1');
    expect(component.tree()).toEqual(['src/main.ts']);
  });

  it('notifies when workspace creation fails', () => {
    setup();
    service.createWorkspace.and.returnValue(throwError(() => new Error('bad zip')));
    const file = new File(['zip'], 'bad.zip', { type: 'application/zip' });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;

    // Le nommage à la création (F-28 SF-28-16) passe par le dialogue de saisie.
    dialog.open.and.returnValue({
      afterClosed: () => of('projet'),
    } as MatDialogRef<unknown, unknown>);
    component.onZipPicked(event);

    expect(snackBar.open).toHaveBeenCalled();
    expect(component.activeWorkspaceId()).toBeNull();
  });

  it('rejette côté client une archive trop volumineuse sans appeler le backend', () => {
    setup();
    const file = new File(['x'], 'gros.zip', { type: 'application/zip' });
    Object.defineProperty(file, 'size', { value: MAX_UPLOAD_BYTES + 1 });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;

    // Le nommage à la création (F-28 SF-28-16) passe par le dialogue de saisie.
    dialog.open.and.returnValue({
      afterClosed: () => of('projet'),
    } as MatDialogRef<unknown, unknown>);
    component.onZipPicked(event);

    expect(service.createWorkspace).not.toHaveBeenCalled();
    expect(component.creating()).toBeFalse();
    const message = snackBar.open.calls.mostRecent().args[0] as string;
    expect(message).toContain('trop volumineuse');
    expect(message).toContain('node_modules');
  });

  it('affiche le message backend lorsque la création échoue avec un corps structuré', () => {
    setup();
    const error = new HttpErrorResponse({
      status: 400,
      error: { error: 'invalid_archive', message: "Un fichier de l'archive est trop volumineux." },
    });
    service.createWorkspace.and.returnValue(throwError(() => error));
    const file = new File(['zip'], 'projet.zip', { type: 'application/zip' });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;

    // Le nommage à la création (F-28 SF-28-16) passe par le dialogue de saisie.
    dialog.open.and.returnValue({
      afterClosed: () => of('projet'),
    } as MatDialogRef<unknown, unknown>);
    component.onZipPicked(event);

    expect(snackBar.open.calls.mostRecent().args[0]).toBe(
      "Un fichier de l'archive est trop volumineux.",
    );
  });

  it('traduit un 413 ingress en message « trop volumineuse » à l\'import', () => {
    setup();
    const error = new HttpErrorResponse({ status: 413, error: '<html>413</html>' });
    service.createWorkspace.and.returnValue(throwError(() => error));
    const file = new File(['zip'], 'projet.zip', { type: 'application/zip' });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;

    // Le nommage à la création (F-28 SF-28-16) passe par le dialogue de saisie.
    dialog.open.and.returnValue({
      afterClosed: () => of('projet'),
    } as MatDialogRef<unknown, unknown>);
    component.onZipPicked(event);

    expect(snackBar.open.calls.mostRecent().args[0]).toContain('trop volumineuse');
  });

  it('loads history and tree when a workspace is selected', () => {
    setup();
    const history: AtelierMessage[] = [
      { id: 'm1', role: 'USER', content: 'Salut', createdAt: '2026-07-11T00:00:00Z' },
    ];
    service.getHistory.and.returnValue(of(history));
    service.getResume.and.returnValue(
      of({ turns: 0, lastMessageAt: null, threadStartedAt: null, prompt: 'NONE' as const }),
    );

    component.selectWorkspace(summary);

    expect(service.getHistory).toHaveBeenCalledWith('w1');
    expect(service.getWorkspace).toHaveBeenCalledWith('w1');
    expect(component.messages().length).toBe(1);
    expect(component.tree()).toEqual(['src/main.ts']);
  });

  it('streams a message: relays a step then replaces it with the final reply and refreshes the tree', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    // Boucle maison : c'est le moteur « ma machine » qui emprunte `chat/stream`
    // (F-39 / SF-39-08). Le moteur n'est plus un mode, il conditionne le chemin d'envoi.
    component.engine.set('LOCAL_MACHINE');
    // Le flux relaie une étape « read », un commentaire, puis la réponse finale.
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onAction({ type: 'read', path: 'src/main.ts' });
      handlers.onText('Je regarde le fichier…');
      handlers.onDone({
        reply: "C'est fait.",
        actions: [{ type: 'write', path: 'src/main.ts' }],
        messageId: 'm-assistant',
      });
      return Promise.resolve();
    });
    service.getWorkspace.calls.reset();

    component.draft.set('Modifie main.ts');
    component.send();

    expect(service.streamChat).toHaveBeenCalledWith('w1', 'Modifie main.ts', jasmine.anything());
    const messages = component.messages();
    expect(messages.length).toBe(2);
    expect(messages[0].role).toBe('USER');
    expect(messages[1].role).toBe('ASSISTANT');
    expect(messages[1].content).toBe("C'est fait.");
    expect(messages[1].actions.length).toBe(1);
    // Le tour « en cours » est effacé une fois la réponse finale reçue.
    expect(component.streaming()).toBeNull();
    expect(component.submitting()).toBeFalse();
    expect(component.draft()).toBe('');
    // L'arborescence est rafraîchie car un tour a pu écrire des fichiers.
    expect(service.getWorkspace).toHaveBeenCalledWith('w1');
  });

  it('accumulates streamed steps and partial text on the in-progress turn', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    // Boucle maison : c'est le moteur « ma machine » qui emprunte `chat/stream`
    // (F-39 / SF-39-08). Le moteur n'est plus un mode, il conditionne le chemin d'envoi.
    component.engine.set('LOCAL_MACHINE');
    // Ne termine pas le flux : on inspecte l'état « en cours ».
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onAction({ type: 'read', path: 'a.txt' });
      handlers.onAction({ type: 'write', path: 'b.txt' });
      handlers.onText('Voilà ');
      handlers.onText('ce que je fais.');
      return Promise.resolve();
    });

    component.draft.set('Fais un truc');
    component.send();

    const live = component.streaming();
    expect(live).not.toBeNull();
    expect(live!.steps.map((s) => s.type)).toEqual(['read', 'write']);
    expect(live!.text).toBe('Voilà ce que je fais.');
    expect(component.submitting()).toBeTrue();
  });

  it("attache la sortie d'une commande à l'étape bash en cours (F-38 / SF-38-07)", () => {
    setup();
    component.activeWorkspaceId.set('w1');
    // Boucle maison : c'est le moteur « ma machine » qui emprunte `chat/stream`
    // (F-39 / SF-39-08). Le moteur n'est plus un mode, il conditionne le chemin d'envoi.
    component.engine.set('LOCAL_MACHINE');
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onAction({ type: 'read', path: 'a.txt' });
      handlers.onAction({ type: 'bash', path: 'npm test' });
      handlers.onOutput?.('ok 1\n');
      handlers.onOutput?.('ok 2\n');
      return Promise.resolve();
    });

    component.draft.set('lance les tests');
    component.send();

    const live = component.streaming();
    // La sortie appartient à l'étape qui la produit, pas au tour entier.
    expect(live!.steps[0].output).toBeUndefined();
    expect(live!.steps[1].output).toBe('ok 1\nok 2\n');
  });

  it("demande l'interruption du tour Assistant en cours (F-38 / SF-38-07)", () => {
    setup();
    component.activeWorkspaceId.set('w1');
    // Boucle maison : c'est le moteur « ma machine » qui emprunte `chat/stream`
    // (F-39 / SF-39-08). Le moteur n'est plus un mode, il conditionne le chemin d'envoi.
    component.engine.set('LOCAL_MACHINE');
    service.interruptChat.and.returnValue(of(void 0));
    service.streamChat.and.callFake(() => Promise.resolve()); // tour laissé en cours
    component.draft.set('lance');
    component.send();

    component.interruptLocalRun();

    expect(service.interruptChat).toHaveBeenCalledWith('w1');
    expect(snackBar.open).toHaveBeenCalled();
  });

  it("n'interrompt rien quand aucun tour n'est en cours (F-38 / SF-38-07)", () => {
    setup();
    component.activeWorkspaceId.set('w1');
    // Boucle maison : c'est le moteur « ma machine » qui emprunte `chat/stream`
    // (F-39 / SF-39-08). Le moteur n'est plus un mode, il conditionne le chemin d'envoi.
    component.engine.set('LOCAL_MACHINE');

    component.interruptLocalRun();

    expect(service.interruptChat).not.toHaveBeenCalled();
  });

  it('does not send when the draft is blank', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    // Boucle maison : c'est le moteur « ma machine » qui emprunte `chat/stream`
    // (F-39 / SF-39-08). Le moteur n'est plus un mode, il conditionne le chemin d'envoi.
    component.engine.set('LOCAL_MACHINE');
    component.draft.set('   ');
    component.send();
    expect(service.streamChat).not.toHaveBeenCalled();
  });

  it('removes the optimistic user turn and notifies when the stream fails', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    // Boucle maison : c'est le moteur « ma machine » qui emprunte `chat/stream`
    // (F-39 / SF-39-08). Le moteur n'est plus un mode, il conditionne le chemin d'envoi.
    component.engine.set('LOCAL_MACHINE');
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onError('internal_error');
      return Promise.resolve();
    });

    component.draft.set('Fais un truc');
    component.send();

    expect(component.messages().length).toBe(0);
    expect(component.streaming()).toBeNull();
    expect(component.submitting()).toBeFalse();
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('shows a quota message when the stream reports quota_exceeded', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    // Boucle maison : c'est le moteur « ma machine » qui emprunte `chat/stream`
    // (F-39 / SF-39-08). Le moteur n'est plus un mode, il conditionne le chemin d'envoi.
    component.engine.set('LOCAL_MACHINE');
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onError('quota_exceeded');
      return Promise.resolve();
    });

    component.draft.set('Fais un truc');
    component.send();

    const message = snackBar.open.calls.mostRecent().args[0] as string;
    expect(message).toContain('Quota de consommation atteint');
    expect(component.messages().length).toBe(0);
  });

  it('moteur « ma machine » : send() appelle streamChat, pas streamAgent (F-39 SF-39-08)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    // Boucle maison : c'est le moteur « ma machine » qui emprunte `chat/stream`
    // (F-39 / SF-39-08). Le moteur n'est plus un mode, il conditionne le chemin d'envoi.
    component.engine.set('LOCAL_MACHINE');
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onDone({ reply: 'ok', actions: [], messageId: 'm1' });
      return Promise.resolve();
    });

    component.draft.set('Lis main.ts');
    component.send();

    expect(service.streamChat).toHaveBeenCalled();
    expect(service.streamAgent).not.toHaveBeenCalled();
  });

  it('moteur « bac à sable » : send() appelle streamAgent (pas streamChat) et ajoute la réponse finale', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    // Le flux relaie un état, une commande bash, un commentaire, puis la réponse finale.
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onStatus('running');
      handlers.onAction({ tool: 'bash', detail: 'npm test' });
      handlers.onAgent('Je lance les tests…');
      handlers.onDone({
        reply: 'Tests OK.',
        changedFiles: ['src/main.ts'],
        inputTokens: 0,
        outputTokens: 0,
        activeSeconds: 0,
        interrupted: false,
      });
      return Promise.resolve();
    });
    service.getWorkspace.calls.reset();

    component.draft.set('Lance les tests');
    component.send();

    expect(service.streamAgent).toHaveBeenCalledWith('w1', 'Lance les tests', jasmine.anything());
    expect(service.streamChat).not.toHaveBeenCalled();
    const messages = component.messages();
    expect(messages.length).toBe(2);
    expect(messages[0].role).toBe('USER');
    expect(messages[1].role).toBe('ASSISTANT');
    expect(messages[1].content).toBe('Tests OK.');
    expect(messages[1].changedFiles).toEqual(['src/main.ts']);
    // Le tour « en cours » est effacé et l'arborescence rafraîchie après la session.
    expect(component.execStreaming()).toBeNull();
    expect(component.submitting()).toBeFalse();
    expect(service.getWorkspace).toHaveBeenCalledWith('w1');
  });

  it('mode Terminal : accumule l\'état, les étapes d\'outil et le texte partiel du tour en cours', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    // Ne termine pas le flux : on inspecte l'état « en cours ».
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onStatus('running');
      handlers.onAction({ tool: 'bash', detail: 'npm install' });
      handlers.onAction({ tool: 'bash', detail: 'npm test' });
      handlers.onAgent('Installation ');
      handlers.onAgent('puis tests.');
      return Promise.resolve();
    });

    component.draft.set('Build le projet');
    component.send();

    const live = component.execStreaming();
    expect(live).not.toBeNull();
    expect(live!.status).toBe('running');
    expect(live!.blocks.map((b) => component.blockLabel(b))).toEqual(['npm install', 'npm test']);
    expect(live!.text).toBe('Installation puis tests.');
    expect(component.submitting()).toBeTrue();
  });

  it('mode Terminal : onError(forbidden) affiche le message Gold sans ajouter de message assistant', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onError('forbidden');
      return Promise.resolve();
    });

    component.draft.set('Lance les tests');
    component.send();

    // Le tour utilisateur optimiste est retiré : aucune persistance côté serveur.
    expect(component.messages().length).toBe(0);
    expect(component.execStreaming()).toBeNull();
    expect(component.submitting()).toBeFalse();
    const message = snackBar.open.calls.mostRecent().args[0] as string;
    expect(message).toContain('Gold');
  });

  it('mode Terminal : onError(session_timeout) affiche un message de délai dépassé', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onError('session_timeout');
      return Promise.resolve();
    });

    component.draft.set('Tâche longue');
    component.send();

    const message = snackBar.open.calls.mostRecent().args[0] as string;
    expect(message).toContain('temps imparti');
    expect(component.messages().length).toBe(0);
  });

  it('ne change pas de cible d\'exécution pendant un envoi en cours (F-39 SF-39-08)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    // Flux qui ne se termine pas : submitting reste vrai.
    service.streamAgent.and.callFake(() => Promise.resolve());
    component.draft.set('Tâche');
    component.send();
    expect(component.submitting()).toBeTrue();

    // Il n'y a plus de mode à basculer ; ce qui reste réglable, c'est *où* les outils s'exécutent —
    // et le changer au milieu d'un tour enverrait les écritures ailleurs qu'attendu.
    component.setExecutionTarget('RUNNER');

    expect(service.setExecutionTarget).not.toHaveBeenCalled();
  });

  it('opens a file into the editable preview', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    const content: FileContent = { path: 'src/main.ts', content: 'export const x = 1;' };
    service.getFile.and.returnValue(of(content));

    component.openFile('src/main.ts');

    expect(service.getFile).toHaveBeenCalledWith('w1', 'src/main.ts');
    expect(component.selectedFilePath()).toBe('src/main.ts');
    expect(component.fileContent()).toBe('export const x = 1;');
  });

  it('saves the edited file content', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.selectedFilePath.set('src/main.ts');
    component.fileContent.set('updated');
    service.writeFile.and.returnValue(of(void 0));

    component.saveFile();

    expect(service.writeFile).toHaveBeenCalledWith('w1', 'src/main.ts', 'updated');
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('notifies when saving a file fails', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.selectedFilePath.set('src/main.ts');
    component.fileContent.set('updated');
    service.writeFile.and.returnValue(throwError(() => new Error('boom')));

    component.saveFile();

    expect(snackBar.open).toHaveBeenCalled();
  });

  it('ajoute un fichier texte du PC via writeFile puis rafraîchit l\'arborescence (SF-28-13)', async () => {
    setup();
    component.activeWorkspaceId.set('w1');
    service.writeFile.and.returnValue(of(void 0));
    service.getWorkspace.calls.reset();
    const file = new File(['export const x = 1;'], 'main.ts', { type: 'text/plain' });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;

    await component.onWorkspaceFilePicked(event);

    expect(service.writeFile).toHaveBeenCalledWith('w1', 'main.ts', 'export const x = 1;');
    expect(service.getWorkspace).toHaveBeenCalledWith('w1');
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Fichier ajouté.');
  });

  it('refuse un fichier binaire (image) du PC sans appeler writeFile (SF-28-13)', async () => {
    setup();
    component.activeWorkspaceId.set('w1');
    const file = new File(['\x00\x01'], 'photo.png', { type: 'image/png' });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;

    await component.onWorkspaceFilePicked(event);

    expect(service.writeFile).not.toHaveBeenCalled();
    const message = snackBar.open.calls.mostRecent().args[0] as string;
    expect(message).toContain('bibliothèque');
  });

  it('importe les documents choisis dans la bibliothèque via importLibrary + refresh (SF-28-13)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    const imported: WorkspaceDetail = {
      ...detail,
      files: ['src/main.ts', 'bibliotheque/contrat.pdf.md'],
    };
    service.importLibrary.and.returnValue(of(imported));
    dialog.open.and.returnValue({
      afterClosed: () => of([{ id: 'd1', filename: 'contrat.pdf' }]),
    } as MatDialogRef<unknown, unknown>);

    component.openWorkspaceLibraryPicker();

    expect(service.importLibrary).toHaveBeenCalledWith('w1', ['d1']);
    expect(component.tree()).toEqual(['src/main.ts', 'bibliotheque/contrat.pdf.md']);
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Document importé.');
  });

  it('n\'appelle pas importLibrary si le sélecteur de bibliothèque est fermé sans choix (SF-28-13)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    dialog.open.and.returnValue({
      afterClosed: () => of(undefined),
    } as MatDialogRef<unknown, unknown>);

    component.openWorkspaceLibraryPicker();

    expect(service.importLibrary).not.toHaveBeenCalled();
  });
  // ---- F-30 SF-30-02 : rendu terminal (commande + sortie) ----

  it('mode Terminal : apparie la sortie à sa commande par toolUseId (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onAction({ tool: 'bash', detail: 'npm test', toolUseId: 'tu_1' });
      handlers.onAction({ tool: 'bash', detail: 'npm run build', toolUseId: 'tu_2' });
      // Sorties dans le désordre : seul l'identifiant garantit le bon rattachement.
      handlers.onActionResult({ tool: 'bash', toolUseId: 'tu_2', output: 'build ok', error: false });
      handlers.onActionResult({ tool: 'bash', toolUseId: 'tu_1', output: '12 passing', error: false });
      return Promise.resolve();
    });

    component.draft.set('Lance tout');
    component.send();

    const blocks = component.execStreaming()!.blocks;
    expect(blocks.map((b) => component.blockLabel(b))).toEqual(['npm test', 'npm run build']);
    expect(blocks[0].output).toBe('12 passing');
    expect(blocks[1].output).toBe('build ok');
  });

  it('mode Terminal : sans toolUseId, la sortie va à la dernière commande sans sortie (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onAction({ tool: 'bash', detail: 'npm test', toolUseId: null });
      handlers.onActionResult({ tool: 'bash', toolUseId: null, output: '12 passing', error: false });
      return Promise.resolve();
    });

    component.draft.set('Teste');
    component.send();

    const blocks = component.execStreaming()!.blocks;
    expect(blocks.length).toBe(1);
    expect(blocks[0].output).toBe('12 passing');
    expect(blocks[0].hasOutput).toBeTrue();
  });

  it('mode Terminal : une sortie sans commande connue crée un bloc orphelin (jamais perdue, F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onActionResult({ tool: 'bash', toolUseId: 'inconnu', output: 'orpheline', error: false });
      return Promise.resolve();
    });

    component.draft.set('Teste');
    component.send();

    const blocks = component.execStreaming()!.blocks;
    expect(blocks.length).toBe(1);
    expect(blocks[0].command).toBeUndefined();
    expect(blocks[0].output).toBe('orpheline');
  });

  it('mode Terminal : une sortie en échec marque le bloc en erreur (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onAction({ tool: 'bash', detail: 'npm run build', toolUseId: 'tu_1' });
      handlers.onActionResult({
        tool: 'bash',
        toolUseId: 'tu_1',
        output: 'command not found',
        error: true,
      });
      return Promise.resolve();
    });

    component.draft.set('Build');
    component.send();

    expect(component.execStreaming()!.blocks[0].error).toBeTrue();
  });

  it('mode Terminal : une sortie longue est repliée puis dépliable (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    const long = Array.from({ length: 30 }, (_, i) => `ligne ${i + 1}`).join('\n');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onAction({ tool: 'bash', detail: 'npm install', toolUseId: 'tu_1' });
      handlers.onActionResult({ tool: 'bash', toolUseId: 'tu_1', output: long, error: false });
      return Promise.resolve();
    });

    component.draft.set('Installe');
    component.send();

    const block = component.execStreaming()!.blocks[0];
    expect(component.hiddenLineCount(block)).toBe(10);
    expect(component.visibleOutput(block).split('\n').length).toBe(20);

    component.toggleBlock(block);

    expect(component.visibleOutput(block)).toBe(long);
  });

  it('mode Terminal : la transcription reste dans le fil après la fin du run (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onAction({ tool: 'bash', detail: 'npm test', toolUseId: 'tu_1' });
      handlers.onActionResult({ tool: 'bash', toolUseId: 'tu_1', output: '12 passing', error: false });
      handlers.onDone({
        reply: 'Tests verts.',
        changedFiles: [],
        inputTokens: 0,
        outputTokens: 0,
        activeSeconds: 0,
        interrupted: false,
      });
      return Promise.resolve();
    });

    component.draft.set('Teste');
    component.send();

    expect(component.execStreaming()).toBeNull();
    const last = component.messages()[component.messages().length - 1];
    expect(last.content).toBe('Tests verts.');
    expect(last.terminal!.length).toBe(1);
    expect(last.terminal![0].output).toBe('12 passing');
    expect(component.blockLabel(last.terminal![0])).toBe('npm test');
  });
  // ---- F-30 SF-30-03 : modes « Assistant » et « Terminal » ----

  it('le sélecteur de mode a disparu de l\'écran (F-39 SF-39-08, D1)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    fixture.detectChanges();

    // Les mots « Assistant » et « Terminal » désignaient un moteur : ils n'ont plus de référent.
    expect(fixture.nativeElement.querySelector('.mode-selector')).toBeNull();
    expect(fixture.nativeElement.querySelector('.mode-toggle')).toBeNull();
  });

  it('les messages d\'erreur ne nomment plus un mode (F-39 SF-39-08)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onError('agent_disabled');
      return Promise.resolve();
    });

    component.draft.set('Teste');
    component.send();

    expect(snackBar.open.calls.mostRecent().args[0]).toBe(
      "L'exécution est momentanément indisponible.",
    );
  });

  it('l\'exécution refusée (non-Gold) renvoie vers l\'offre Gold (F-30, reformulé SF-39-08)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onError('forbidden');
      return Promise.resolve();
    });

    component.draft.set('Teste');
    component.send();

    expect(snackBar.open.calls.mostRecent().args[0]).toBe(
      "L'exécution est réservée à l'offre Gold.",
    );
  });

  it('le moteur nomme où le code s\'exécute (F-39 SF-39-08, D-L4-2)', () => {
    setup();

    component.engine.set('LOCAL_MACHINE');
    expect(component.localEngine()).toBeTrue();

    component.engine.set('HOSTED_SANDBOX');
    expect(component.localEngine()).toBeFalse();
  });
  // ---- F-30 SF-30-06 : réinitialiser la sandbox ----

  it('réinitialise la sandbox après confirmation (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as MatDialogRef<unknown, unknown>);
    service.resetAgentSession.and.returnValue(of(void 0));

    component.resetSandbox();

    expect(service.resetAgentSession).toHaveBeenCalledWith('w1');
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Sandbox réinitialisée.');
  });

  it('n\'appelle rien si le dialogue est fermé sans confirmer (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    dialog.open.and.returnValue({ afterClosed: () => of(false) } as MatDialogRef<unknown, unknown>);

    component.resetSandbox();

    expect(service.resetAgentSession).not.toHaveBeenCalled();
  });

  it('affiche un message lisible si la réinitialisation échoue (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as MatDialogRef<unknown, unknown>);
    service.resetAgentSession.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })),
    );

    component.resetSandbox();

    expect(snackBar.open.calls.mostRecent().args[0]).toBe(
      "La sandbox n'a pas pu être réinitialisée. Veuillez réessayer.",
    );
  });

  it('traduit un 404 en « Projet introuvable. » (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as MatDialogRef<unknown, unknown>);
    service.resetAgentSession.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404 })),
    );

    component.resetSandbox();

    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Projet introuvable.');
  });

  it('refuse la réinitialisation pendant un envoi en cours (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    component.submitting.set(true);

    component.resetSandbox();

    expect(dialog.open).not.toHaveBeenCalled();
    expect(service.resetAgentSession).not.toHaveBeenCalled();
  });
  // ---- F-30 SF-30-05 : coût du tour (durée + tokens) ----

  it('affiche durée et tokens sur le tour terminé (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onDone({
        reply: 'Terminé.',
        changedFiles: [],
        inputTokens: 12_000,
        outputTokens: 400,
        activeSeconds: 30,
        interrupted: false,
      });
      return Promise.resolve();
    });

    component.draft.set('Teste');
    component.send();

    const last = component.messages()[component.messages().length - 1];
    expect(last.cost).toBeDefined();
    expect(last.cost!.tokens).toBe(12_400);
    // Les tokens affichés sont la somme entrée + sortie du tour, formatée en fr-FR.
    expect(component.costLabel(last.cost!)).toContain('tokens');
    expect(component.costLabel({ elapsedSeconds: 65, tokens: 12_400 })).toBe(
      `1:05 · ${(12_400).toLocaleString('fr-FR')} tokens`,
    );
  });

  it('n\'affiche aucun coût quand la consommation est inconnue (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      // Relevé best-effort manqué côté backend : zéro = inconnu, pas « 0 token ».
      handlers.onDone({
        reply: 'Terminé.',
        changedFiles: [],
        inputTokens: 0,
        outputTokens: 0,
        activeSeconds: 0,
        interrupted: false,
      });
      return Promise.resolve();
    });

    component.draft.set('Teste');
    component.send();

    const last = component.messages()[component.messages().length - 1];
    expect(last.cost).toBeUndefined();
    expect(last.content).toBe('Terminé.');
  });
  // ---- F-30 SF-30-07 : vue terminal immersive ----

  it('un projet ouvert est un terminal, quel que soit le moteur (F-39 SF-39-08, D1)', () => {
    setup();
    component.activeWorkspaceId.set('w1');

    for (const engine of ['HOSTED_SANDBOX', 'LOCAL_MACHINE'] as const) {
      component.engine.set(engine);
      fixture.detectChanges();

      // La vue terminal occupe l'écran : ni liste de projets, ni fil conversationnel.
      expect(fixture.nativeElement.querySelector('app-atelier-terminal')).not.toBeNull();
      expect(fixture.nativeElement.querySelector('.atelier-layout')).toBeNull();
    }
    // Ouvrir le terminal n'envoie rien : c'est un écran, pas un tour.
    expect(service.streamAgent).not.toHaveBeenCalled();
    expect(service.streamChat).not.toHaveBeenCalled();
  });

  it('quitter le terminal referme le projet et revient à la liste (F-39 SF-39-08)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.messages.set([
      { id: 'm1', role: 'USER', content: 'lance les tests', actions: [] },
    ]);
    fixture.detectChanges();

    component.leaveTerminal();
    fixture.detectChanges();

    // Il n'y a plus d'autre mode vers lequel basculer : quitter, c'est refermer.
    expect(component.activeWorkspaceId()).toBeNull();
    expect(fixture.nativeElement.querySelector('app-atelier-terminal')).toBeNull();
    expect(fixture.nativeElement.querySelector('.atelier-layout')).not.toBeNull();
  });

  it('sans projet sélectionné, la vue terminal ne s\'ouvre pas (F-30)', () => {
    setup();
    component.activeWorkspaceId.set(null);
    component.engine.set('HOSTED_SANDBOX');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-atelier-terminal')).toBeNull();
  });
  it('credit_exhausted affiche un message qui n\'invite pas à réessayer (F-30 SF-30-08)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onError('credit_exhausted');
      return Promise.resolve();
    });

    component.draft.set('Teste');
    component.send();

    const message = snackBar.open.calls.mostRecent().args[0] as string;
    expect(message).toContain('crédit du fournisseur est épuisé');
    expect(message).not.toContain('réessayer');
  });
  // ---- F-30 SF-30-09 : persistance des tours Terminal ----

  it('restitue la transcription d\'un tour Terminal depuis l\'historique (F-30)', () => {
    const item = toThreadItem({
      id: 'm1',
      role: 'ASSISTANT',
      content: 'Tests verts.',
      createdAt: '2026-08-24T00:00:00Z',
      terminal: {
        blocks: [
          {
            tool: 'bash',
            command: 'npm test',
            toolUseId: 'tu_1',
            output: '12 passing',
            hasOutput: true,
            error: false,
          },
        ],
        omittedBlocks: 0,
        inputTokens: 1_000,
        outputTokens: 200,
        activeSeconds: 30,
      },
    });

    expect(item.terminal!.length).toBe(1);
    expect(item.terminal![0].command).toBe('npm test');
    expect(item.terminal![0].output).toBe('12 passing');
    expect(item.terminal![0].expanded).toBeFalse();
    expect(item.cost!.tokens).toBe(1_200);
  });

  it('un tour du mode Assistant reste sans transcription (non-régression F-30)', () => {
    const item = toThreadItem({
      id: 'm1',
      role: 'ASSISTANT',
      content: 'Fichier modifié.',
      createdAt: '2026-08-24T00:00:00Z',
    });

    expect(item.terminal).toBeUndefined();
    expect(item.cost).toBeUndefined();
    expect(item.content).toBe('Fichier modifié.');
  });

  it('une transcription illisible n\'empêche pas d\'afficher le tour (F-30)', () => {
    const item = toThreadItem({
      id: 'm1',
      role: 'ASSISTANT',
      content: 'Terminé.',
      createdAt: '2026-08-24T00:00:00Z',
      terminal: { blocks: null } as never,
    });

    expect(item.terminal).toBeUndefined();
    expect(item.content).toBe('Terminé.');
  });

  it('sans consommation connue, le tour restitué ne porte pas de coût (F-30)', () => {
    const item = toThreadItem({
      id: 'm1',
      role: 'ASSISTANT',
      content: 'Terminé.',
      createdAt: '2026-08-24T00:00:00Z',
      terminal: {
        blocks: [
          { tool: 'bash', command: 'ls', toolUseId: null, output: 'a', hasOutput: true, error: false },
        ],
        omittedBlocks: 0,
        inputTokens: 0,
        outputTokens: 0,
        activeSeconds: 0,
      },
    });

    expect(item.terminal!.length).toBe(1);
    expect(item.cost).toBeUndefined();
  });

  // ---- F-31 SF-31-02 : ouverture d'un projet sur un dépôt Git ----

  it("ouvre un dépôt via le dialogue et adopte le projet créé", () => {
    setup();
    dialog.open.and.returnValue({
      afterClosed: () => of({ repoUrl: 'https://github.com/octocat/hello', branch: 'main' }),
    } as MatDialogRef<unknown, unknown>);
    service.createGitWorkspace.and.returnValue(of(gitDetail));

    component.openGitRepoDialog();

    expect(service.createGitWorkspace).toHaveBeenCalledWith({
      repoUrl: 'https://github.com/octocat/hello',
      branch: 'main',
    });
    expect(component.activeWorkspaceId()).toBe('w2');
    expect(component.activeIsGit()).toBeTrue();
    expect(component.activeDetail()?.gitBranch).toBe('main');
    expect(component.workspaces()[0].source).toBe('GIT');
    expect(component.workspaces()[0].gitRepo).toBe('octocat/hello');
    expect(component.creating()).toBeFalse();
  });

  it("n'appelle rien si le dialogue de dépôt est fermé sans choix", () => {
    setup();
    dialog.open.and.returnValue({ afterClosed: () => of(undefined) } as MatDialogRef<unknown, unknown>);

    component.openGitRepoDialog();

    expect(service.createGitWorkspace).not.toHaveBeenCalled();
    expect(component.creating()).toBeFalse();
  });

  it("oriente vers les réglages quand aucun jeton GitHub n'est enregistré", () => {
    setup();
    dialog.open.and.returnValue({
      afterClosed: () => of({ repoUrl: 'https://github.com/octocat/hello' }),
    } as MatDialogRef<unknown, unknown>);
    service.createGitWorkspace.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({ status: 400, error: { error: 'git_token_missing' } }),
      ),
    );

    component.openGitRepoDialog();

    expect(snackBar.open.calls.mostRecent().args[0]).toContain('réglages');
    expect(component.creating()).toBeFalse();
  });

  it("distingue un dépôt hors de portée d'une panne GitHub", () => {
    setup();
    dialog.open.and.returnValue({
      afterClosed: () => of({ repoUrl: 'https://github.com/octocat/secret' }),
    } as MatDialogRef<unknown, unknown>);
    service.createGitWorkspace.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({ status: 400, error: { error: 'invalid_git_repository' } }),
      ),
    );

    component.openGitRepoDialog();
    expect(snackBar.open.calls.mostRecent().args[0]).toContain('introuvable');

    service.createGitWorkspace.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({ status: 503, error: { error: 'github_unavailable' } }),
      ),
    );

    component.openGitRepoDialog();
    expect(snackBar.open.calls.mostRecent().args[0]).toContain('indisponible');
  });

  it("un projet d'archive n'est jamais présenté comme un dépôt", () => {
    setup();
    component.selectWorkspace(summary);

    expect(component.activeIsGit()).toBeFalse();
    expect(component.activeDetail()?.gitRepo).toBeNull();
  });

  // ---- F-31 SF-31-03 : un projet Git n'a de sens qu'en mode Terminal, en lecture seule ----

  it("aligne le mode sur Terminal à l'ouverture d'un projet Git", () => {
    setup();
    dialog.open.and.returnValue({
      afterClosed: () => of({ repoUrl: 'https://github.com/octocat/hello' }),
    } as MatDialogRef<unknown, unknown>);
    service.createGitWorkspace.and.returnValue(of(gitDetail));

    component.openGitRepoDialog();

    expect(component.engine()).toBe('HOSTED_SANDBOX');
  });

  it("un projet Git en bac à sable tourne sur le bac à sable, sans rien refuser (SF-39-08)", () => {
    setup();
    service.getWorkspace.and.returnValue(of(gitDetail));
    component.selectWorkspace({
      id: 'w2',
      name: 'hello',
      createdAt: '2026-08-25T00:00:00Z',
      source: 'GIT',
      gitRepo: 'octocat/hello',
    });

    // Le moteur est lu, jamais choisi : il n'y a plus de mode indisponible à expliquer.
    expect(service.getEngine).toHaveBeenCalledWith('w2');
    expect(component.engine()).toBe('HOSTED_SANDBOX');
  });

  it("laisse le mode Assistant disponible sur un projet d'archive", () => {
    setup();
    component.selectWorkspace(summary);

    component.engine.set('LOCAL_MACHINE');

    expect(component.engine()).toBe('LOCAL_MACHINE');
  });

  it("expose le troncage d'arborescence d'un dépôt volumineux", () => {
    setup();
    service.getWorkspace.and.returnValue(of({ ...gitDetail, truncated: true }));
    component.selectWorkspace({
      id: 'w2',
      name: 'hello',
      createdAt: '2026-08-25T00:00:00Z',
      source: 'GIT',
      gitRepo: 'octocat/hello',
    });

    expect(component.activeDetail()?.truncated).toBeTrue();
  });

  // ---- F-31 SF-31-04 : publication sur une branche dédiée ----

  /** Ouvre un projet Git : c'est le préalable de toute publication. */
  function openGitProject(): void {
    service.getWorkspace.and.returnValue(of(gitDetail));
    component.selectWorkspace({
      id: 'w2',
      name: 'hello',
      createdAt: '2026-08-25T00:00:00Z',
      source: 'GIT',
      gitRepo: 'octocat/hello',
    });
  }

  it('publie sur la branche choisie et conserve le lien de pull request', () => {
    setup();
    openGitProject();
    dialog.open.and.returnValue({
      afterClosed: () => of({ branch: 'feat/atelier' }),
    } as MatDialogRef<unknown, unknown>);
    service.pushBranch.and.returnValue(
      of({
        branch: 'feat/atelier',
        pushed: true,
        compareUrl: 'https://github.com/octocat/hello/compare/x?expand=1',
        reply: 'Branche poussée.',
      }),
    );

    component.openPushDialog();

    expect(service.pushBranch).toHaveBeenCalledWith('w2', { branch: 'feat/atelier' });
    expect(component.pushResult()?.pushed).toBeTrue();
    expect(component.pushResult()?.compareUrl).toContain('/compare/');
    expect(component.publishing()).toBeFalse();
  });

  it("montre l'échec et sa cause quand rien n'a été publié", () => {
    setup();
    openGitProject();
    dialog.open.and.returnValue({
      afterClosed: () => of({ branch: 'feat/atelier' }),
    } as MatDialogRef<unknown, unknown>);
    service.pushBranch.and.returnValue(
      of({
        branch: 'feat/atelier',
        pushed: false,
        compareUrl: null,
        reply: 'permission denied',
      }),
    );

    component.openPushDialog();

    expect(component.pushResult()?.pushed).toBeFalse();
    expect(component.pushResult()?.compareUrl).toBeNull();
    expect(component.pushResult()?.reply).toContain('permission denied');
  });

  it("oriente vers une commande préalable quand aucune session n'est en cours", () => {
    setup();
    openGitProject();
    dialog.open.and.returnValue({
      afterClosed: () => of({ branch: 'feat/atelier' }),
    } as MatDialogRef<unknown, unknown>);
    service.pushBranch.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { error: 'no_active_session' } })),
    );

    component.openPushDialog();

    expect(snackBar.open.calls.mostRecent().args[0]).toContain('Aucun travail en cours');
    expect(component.publishing()).toBeFalse();
  });

  it("ne publie pas un projet d'archive", () => {
    setup();
    component.selectWorkspace(summary);

    component.openPushDialog();

    expect(dialog.open).not.toHaveBeenCalled();
    expect(service.pushBranch).not.toHaveBeenCalled();
  });

  // ---- F-31 SF-31-05 : ouverture de la pull request ----

  /** Publie une branche : préalable de toute ouverture de pull request. */
  function publishBranch(): void {
    dialog.open.and.returnValue({
      afterClosed: () => of({ branch: 'feat/atelier' }),
    } as MatDialogRef<unknown, unknown>);
    service.pushBranch.and.returnValue(
      of({
        branch: 'feat/atelier',
        pushed: true,
        compareUrl: 'https://github.com/octocat/hello/compare/x?expand=1',
        reply: 'Branche poussée.',
      }),
    );
    component.openPushDialog();
  }

  it('ouvre la pull request de la branche publiée et en conserve l\'URL', () => {
    setup();
    openGitProject();
    publishBranch();
    service.createPullRequest.and.returnValue(
      of({
        branch: 'feat/atelier',
        created: true,
        url: 'https://github.com/octocat/hello/pull/7',
        number: 7,
        reply: 'Pull request créée.',
      }),
    );

    component.openPullRequest();

    // La branche envoyée est celle CONSTATÉE au push, jamais devinée côté client.
    expect(service.createPullRequest).toHaveBeenCalledWith('w2', { branch: 'feat/atelier' });
    expect(component.pullRequest()?.created).toBeTrue();
    expect(component.pullRequest()?.url).toContain('/pull/7');
    expect(component.openingPullRequest()).toBeFalse();
  });

  it("montre la cause quand aucune pull request n'a été ouverte", () => {
    setup();
    openGitProject();
    publishBranch();
    service.createPullRequest.and.returnValue(
      of({
        branch: 'feat/atelier',
        created: false,
        url: null,
        number: null,
        reply: "L'outil create_pull_request n'est pas disponible.",
      }),
    );

    component.openPullRequest();

    expect(component.pullRequest()?.created).toBeFalse();
    expect(component.pullRequest()?.url).toBeNull();
    expect(component.pullRequest()?.reply).toContain('create_pull_request');
  });

  it("n'ouvre aucune pull request tant que rien n'a été publié", () => {
    setup();
    openGitProject();

    component.openPullRequest();

    expect(service.createPullRequest).not.toHaveBeenCalled();
  });

  it("n'ouvre aucune pull request quand la publication a échoué", () => {
    setup();
    openGitProject();
    dialog.open.and.returnValue({
      afterClosed: () => of({ branch: 'feat/atelier' }),
    } as MatDialogRef<unknown, unknown>);
    service.pushBranch.and.returnValue(
      of({ branch: 'feat/atelier', pushed: false, compareUrl: null, reply: 'permission denied' }),
    );
    component.openPushDialog();

    component.openPullRequest();

    expect(service.createPullRequest).not.toHaveBeenCalled();
  });

  it('signale la cause quand le backend refuse la demande', () => {
    setup();
    openGitProject();
    publishBranch();
    service.createPullRequest.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { error: 'git_token_missing' } })),
    );

    component.openPullRequest();

    expect(snackBar.open.calls.mostRecent().args[0]).toContain('Aucun jeton GitHub');
    expect(component.openingPullRequest()).toBeFalse();
  });

  it('une nouvelle publication efface la pull request de la précédente', () => {
    // Sinon le lien pointerait vers un travail qui n'est plus celui qu'on vient de pousser.
    setup();
    openGitProject();
    publishBranch();
    service.createPullRequest.and.returnValue(
      of({
        branch: 'feat/atelier',
        created: true,
        url: 'https://github.com/octocat/hello/pull/7',
        number: 7,
        reply: 'ok',
      }),
    );
    component.openPullRequest();
    expect(component.pullRequest()).not.toBeNull();

    publishBranch();

    expect(component.pullRequest()).toBeNull();
  });

  it("quitter le terminal referme un projet Git et oublie la dernière publication", () => {
    setup();
    openGitProject();

    component.leaveTerminal();

    expect(component.activeWorkspaceId()).toBeNull();
    expect(component.pushResult()).toBeNull();
  });

  it("quitter le terminal d'un projet d'archive le referme aussi (F-39 SF-39-08)", () => {
    setup();
    component.selectWorkspace(summary);

    component.leaveTerminal();

    // Même geste pour les deux sources : il n'existe plus de mode de repli.
    expect(component.activeWorkspaceId()).toBeNull();
  });
  // ---- F-39 / SF-39-08 : écran unique, moteur transparent ----

  it('lit le moteur auprès de la gateway à l\'ouverture d\'un projet (SF-39-08)', () => {
    setup();
    service.getEngine.and.returnValue(of({
      engine: 'LOCAL_MACHINE' as const, runnerConnected: true, runnerLastSeenAt: null,
      recommendRunner: false, recommendReason: null,
    }));

    component.selectWorkspace(summary);

    expect(service.getEngine).toHaveBeenCalledWith('w1');
    expect(component.engine()).toBe('LOCAL_MACHINE');
  });

  it('un relevé de moteur manqué n\'empêche pas d\'ouvrir le projet (SF-39-08)', () => {
    setup();
    service.getWorkspace.and.returnValue(of({ ...detail, executionTarget: 'RUNNER' as const }));
    service.getEngine.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));

    component.selectWorkspace(summary);

    // Repli sur la cible déjà connue : le projet s'ouvre, et il s'ouvre au bon endroit.
    expect(component.activeWorkspaceId()).toBe('w1');
    expect(component.engine()).toBe('LOCAL_MACHINE');
    fixture.destroy();
  });

  it('relit le moteur après une bascule de cible d\'exécution (SF-39-08)', () => {
    setup();
    component.selectWorkspace(summary);
    service.getEngine.calls.reset();
    service.getRunnerStatus.and.returnValue(of({ connected: false, lastSeenAt: null }));
    service.setExecutionTarget.and.returnValue(
      of({ ...detail, executionTarget: 'RUNNER' as const }));

    component.setExecutionTarget('RUNNER');

    // La cible décide du moteur : la changer sans relire laisserait l'écran sur l'ancien.
    expect(service.getEngine).toHaveBeenCalledWith('w1');
    // Le sondage d'état runner vient de démarrer : le laisser tourner ferait fuir un intervalle.
    fixture.destroy();
  });

  it('conserve la transcription d\'un tour de boucle maison dans le fil (acquis F-30 SF-30-02)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('LOCAL_MACHINE');
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onAction({ type: 'bash', path: 'npm test' });
      handlers.onOutput?.('2 tests passés\n');
      handlers.onDone({ reply: 'Tout passe.', actions: [], messageId: 'm-1' });
      return Promise.resolve();
    });

    component.draft.set('lance les tests');
    component.send();

    const last = component.messages()[component.messages().length - 1];
    expect(last.terminal!.length).toBe(1);
    expect(last.terminal![0].command).toBe('npm test');
    expect(last.terminal![0].output).toBe('2 tests passés\n');
    // Rien ne défile plus : le tour terminé porte ce qui s'est passé.
    expect(component.execStreaming()).toBeNull();
  });

  it('n\'annonce aucun coût sur un tour de boucle maison (consommation non relevée)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('LOCAL_MACHINE');
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onDone({ reply: 'ok', actions: [], messageId: 'm-1' });
      return Promise.resolve();
    });

    component.draft.set('vas-y');
    component.send();

    // Une durée sans tokens se lirait comme une mesure : mieux vaut ne rien dire (F-30 SF-30-05).
    expect(component.messages()[1].cost).toBeUndefined();
  });

  it('interrompt le bon moteur selon le tour en cours (SF-39-08)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('LOCAL_MACHINE');
    component.submitting.set(true);
    service.interruptChat.and.returnValue(of(void 0));

    component.interruptRun();

    expect(service.interruptChat).toHaveBeenCalledWith('w1');
    expect(service.interruptAgentSession).not.toHaveBeenCalled();
  });

  // ---- F-39 / SF-39-09 : le runner proposé au bon moment (D6) ----

  it('reprend la recommandation de runner rendue par la gateway (SF-39-09)', () => {
    setup();
    service.getEngine.and.returnValue(of({
      engine: 'HOSTED_SANDBOX' as const, runnerConnected: false, runnerLastSeenAt: null,
      recommendRunner: true, recommendReason: 'GIT' as const,
    }));

    component.selectWorkspace(summary);

    // L'écran ne devine jamais qu'un projet « mérite » le runner : il lit ce que la gateway calcule.
    expect(component.runnerHint()).toBe('GIT');
  });

  it('ne propose rien quand la gateway ne recommande pas (SF-39-09)', () => {
    setup();

    component.selectWorkspace(summary);

    expect(component.runnerHint()).toBeNull();
  });

  it('ne propose rien sur un relevé de moteur manqué (SF-39-09)', () => {
    setup();
    service.getEngine.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));

    component.selectWorkspace(summary);

    // Ne rien proposer vaut mieux que proposer au hasard.
    expect(component.runnerHint()).toBeNull();
  });

  it('une recommandation sans motif ne dit rien : le motif est le message (SF-39-09)', () => {
    setup();
    service.getEngine.and.returnValue(of({
      engine: 'HOSTED_SANDBOX' as const, runnerConnected: false, runnerLastSeenAt: null,
      recommendRunner: true, recommendReason: null,
    }));

    component.selectWorkspace(summary);

    expect(component.runnerHint()).toBeNull();
  });

  it('classée sans suite, la proposition ne revient pas sur ce projet (D-L4-7)', () => {
    setup();
    service.getEngine.and.returnValue(of({
      engine: 'HOSTED_SANDBOX' as const, runnerConnected: false, runnerLastSeenAt: null,
      recommendRunner: true, recommendReason: 'FILE_LIMIT' as const,
    }));
    component.selectWorkspace(summary);
    expect(component.runnerHint()).toBe('FILE_LIMIT');

    component.dismissRunnerHint();
    expect(component.runnerHint()).toBeNull();

    // Rouvrir le même projet ne la remonte pas : « plus tard » a été entendu.
    component.selectWorkspace(summary);
    expect(component.runnerHint()).toBeNull();
  });

  it('un autre projet qui rencontre la même limite la remontre (D-L4-7)', () => {
    setup();
    service.getEngine.and.returnValue(of({
      engine: 'HOSTED_SANDBOX' as const, runnerConnected: false, runnerLastSeenAt: null,
      recommendRunner: true, recommendReason: 'GIT' as const,
    }));
    component.selectWorkspace(summary);
    component.dismissRunnerHint();

    service.getWorkspace.and.returnValue(of(gitDetail));
    component.selectWorkspace({
      id: 'w2', name: 'hello', createdAt: '2026-08-25T00:00:00Z',
      source: 'GIT', gitRepo: 'octocat/hello',
    });

    // C'est le besoin qui déclenche la bande, pas un compteur de refus.
    expect(component.runnerHint()).toBe('GIT');
  });

  // ---- F-32 / SF-32-02 : interrompre un run en cours ----

  it('demande l\'interruption du run en cours, une seule fois malgré deux clics', () => {
    setup();
    service.interruptAgentSession.and.returnValue(of(void 0));
    component.activeWorkspaceId.set('w1');
    component.submitting.set(true);

    component.interruptRun();
    component.interruptRun();

    expect(service.interruptAgentSession).toHaveBeenCalledOnceWith('w1');
    expect(component.interrupting()).toBeTrue();
  });

  it('n\'interrompt rien hors exécution', () => {
    setup();
    component.activeWorkspaceId.set('w1');

    component.interruptRun();

    expect(service.interruptAgentSession).not.toHaveBeenCalled();
  });

  it('sur 409, explique qu\'il n\'y a rien à interrompre et rend la main', () => {
    setup();
    service.interruptAgentSession.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { error: 'no_active_session' } })),
    );
    component.activeWorkspaceId.set('w1');
    component.submitting.set(true);

    component.interruptRun();

    expect(component.interrupting()).toBeFalse();
    expect(snackBar.open.calls.mostRecent().args[0] as string)
      .toContain('Aucune exécution en cours');
  });

  it('restitue la marque d\'interruption d\'un tour relu depuis l\'historique', () => {
    const item = toThreadItem({
      id: 'm1',
      role: 'ASSISTANT',
      content: 'Arrêté.',
      createdAt: '2026-08-25T00:00:00Z',
      terminal: {
        blocks: [
          { tool: 'bash', command: 'npm install', toolUseId: 'tu_1', output: '…', hasOutput: true, error: false },
        ],
        omittedBlocks: 0,
        inputTokens: 900,
        outputTokens: 100,
        activeSeconds: 42,
        interrupted: true,
      },
    });

    expect(item.interrupted).toBeTrue();
    expect(item.terminal!.length).toBe(1);
  });

  it('garde la marque d\'un tour interrompu avant la moindre commande', () => {
    // Sans blocs, la transcription est vide : la mention doit survivre quand même.
    const item = toThreadItem({
      id: 'm1',
      role: 'ASSISTANT',
      content: 'Arrêté.',
      createdAt: '2026-08-25T00:00:00Z',
      terminal: {
        blocks: [],
        omittedBlocks: 0,
        inputTokens: 0,
        outputTokens: 0,
        activeSeconds: 0,
        interrupted: true,
      },
    });

    expect(item.interrupted).toBeTrue();
    expect(item.terminal).toBeUndefined();
  });

  // ---- F-36 / SF-36-04 : plafond de dépense du run atteint ----

  it('marque le tour arrêté sur le plafond de dépense du run', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onDone({
        reply: 'J\'ai commencé…',
        changedFiles: [],
        inputTokens: 900,
        outputTokens: 100,
        activeSeconds: 42,
        interrupted: false,
        budgetReached: true,
      });
      return Promise.resolve();
    });

    component.draft.set('Teste');
    component.send();

    const last = component.messages()[component.messages().length - 1];
    // Le tour est conservé : il a eu lieu et il est facturé. L'écran le dit, il ne l'efface pas.
    expect(last.budgetReached).toBeTrue();
    expect(last.content).toBe('J\'ai commencé…');
    expect(last.interrupted).toBeFalse();
  });

  it('ne marque aucun plafond quand le flux ne le signale pas', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onDone({
        reply: 'Terminé.',
        changedFiles: [],
        inputTokens: 0,
        outputTokens: 0,
        activeSeconds: 0,
        interrupted: false,
      });
      return Promise.resolve();
    });

    component.draft.set('Teste');
    component.send();

    const last = component.messages()[component.messages().length - 1];
    expect(last.budgetReached).toBeFalse();
  });

  it('restitue la marque de plafond d\'un tour relu depuis l\'historique', () => {
    const item = toThreadItem({
      id: 'm1',
      role: 'ASSISTANT',
      content: 'J\'ai commencé…',
      createdAt: '2026-08-26T00:00:00Z',
      terminal: {
        blocks: [],
        omittedBlocks: 0,
        inputTokens: 900,
        outputTokens: 100,
        activeSeconds: 42,
        interrupted: false,
        budgetReached: true,
      },
    });

    expect(item.budgetReached).toBeTrue();
  });

  it('traite un historique écrit avant F-36 comme un tour sans plafond atteint', () => {
    const item = toThreadItem({
      id: 'm1',
      role: 'ASSISTANT',
      content: 'Terminé.',
      createdAt: '2026-08-20T00:00:00Z',
      terminal: {
        blocks: [],
        omittedBlocks: 0,
        inputTokens: 10,
        outputTokens: 5,
        activeSeconds: 1,
        interrupted: false,
      },
    });

    expect(item.budgetReached).toBeFalse();
  });

  // ---- F-39 / SF-39-15 : ce que coûte un message de la boucle maison, et son plafond ----

  it('affiche ce qu\'a coûté le tour de la boucle maison (acquis §4 n°6)', () => {
    // Jusqu'ici la boucle maison ne relevait aucune consommation : l'acquis « coût du tour affiché »
    // ne valait donc que pour le moteur hébergé, c'est-à-dire pas pour celui qui exécute réellement.
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('LOCAL_MACHINE');
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onDone({
        reply: 'Terminé.',
        actions: [],
        messageId: 'm1',
        inputTokens: 40_000,
        outputTokens: 2_000,
        activeSeconds: 137,
        budgetReached: false,
      });
      return Promise.resolve();
    });

    component.draft.set('Fais un truc');
    component.send();

    const last = component.messages()[component.messages().length - 1];
    expect(last.cost).toEqual({ elapsedSeconds: 137, tokens: 42_000 });
    expect(last.budgetReached).toBeFalse();
  });

  it('n\'affiche aucun coût quand la consommation n\'a pas été relevée', () => {
    // Un « 0 token » se lirait comme une mesure. Un backend antérieur n'émet pas ces champs.
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('LOCAL_MACHINE');
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onDone({ reply: 'Terminé.', actions: [], messageId: 'm1' });
      return Promise.resolve();
    });

    component.draft.set('Fais un truc');
    component.send();

    const last = component.messages()[component.messages().length - 1];
    expect(last.cost).toBeUndefined();
    expect(last.budgetReached).toBeFalse();
  });

  it('marque le tour de la boucle maison arrêté sur le plafond de dépense', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('LOCAL_MACHINE');
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onDone({
        reply: 'Ce message a atteint son plafond de consommation ; …',
        actions: [],
        messageId: 'm1',
        inputTokens: 1_400_000,
        outputTokens: 100_000,
        activeSeconds: 300,
        budgetReached: true,
      });
      return Promise.resolve();
    });

    component.draft.set('Refais tout le projet');
    component.send();

    const last = component.messages()[component.messages().length - 1];
    // Le tour est conservé : il a eu lieu et il est facturé. L'écran le dit, il ne l'efface pas.
    expect(last.budgetReached).toBeTrue();
    expect(last.cost?.tokens).toBe(1_500_000);
  });

  it('remplit la consommation de la ligne vivante pendant le tour', () => {
    // Acquis §4 n°5 : la ligne vivante affichait les étapes et la durée, jamais les tokens, sur la
    // boucle maison. Le flux ne les relayait tout simplement pas.
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('LOCAL_MACHINE');
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onAction({ type: 'bash', path: 'npm test' });
      handlers.onProgress?.(12_500);
      handlers.onProgress?.(31_800);
      return Promise.resolve();
    });

    component.draft.set('Lance les tests');
    component.send();

    expect(component.execStreaming()?.tokens).toBe(31_800);
  });

  it('relit le coût d\'un tour sans transcription', () => {
    // La boucle maison relève sa consommation sans persister de blocs (D-L8-6) : un tour mesuré
    // perdait sa mesure au rechargement.
    const item = toThreadItem({
      id: 'm1',
      role: 'ASSISTANT',
      content: 'Terminé.',
      createdAt: '2026-09-06T00:00:00Z',
      terminal: {
        blocks: [],
        omittedBlocks: 0,
        inputTokens: 40_000,
        outputTokens: 2_000,
        activeSeconds: 137,
        interrupted: false,
        budgetReached: false,
      },
    });

    expect(item.cost).toEqual({ elapsedSeconds: 137, tokens: 42_000 });
    expect(item.terminal).toBeUndefined();
  });

  // ---- F-34 / SF-34-02 : instructions portées par le projet ----

  it('expose le chemin des instructions du projet quand il en porte', () => {
    setup();
    service.getWorkspace.and.returnValue(of({ ...detail, instructionsPath: 'CLAUDE.md' }));

    component.selectWorkspace(summary);

    expect(component.instructionsPath()).toBe('CLAUDE.md');
  });

  it("n'affiche aucune instruction quand le projet n'en porte pas", () => {
    setup();
    component.selectWorkspace(summary); // `detail` ne porte pas `instructionsPath`

    expect(component.instructionsPath()).toBeNull();
  });

  it("ouvre l'explorateur sur le fichier d'instructions", () => {
    setup();
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate');
    service.getWorkspace.and.returnValue(
      of({ ...detail, instructionsPath: '.atelier/instructions.md' }),
    );
    component.selectWorkspace(summary);

    component.openInstructions();

    // Depuis SF-39-18, le fichier d'instructions s'ouvre dans le MÊME panneau que l'explorateur :
    // changer de route détruirait le terminal, et avec lui le flux du tour en cours.
    expect(component.filesExplorerOpen()).toBeTrue();
    expect(component.filesExplorerPath()).toBe('.atelier/instructions.md');
    expect(navigate.calls.mostRecent().args[0]).toEqual([]);
  });

  it("ne navigue nulle part si le projet ne porte pas d'instructions", () => {
    setup();
    const router = TestBed.inject(Router);
    const navigate = spyOn(router, 'navigate');
    component.selectWorkspace(summary);

    component.openInstructions();

    expect(navigate).not.toHaveBeenCalled();
  });

  it('un tour mené à son terme n\'est jamais marqué comme interrompu (non-régression F-30)', () => {
    const item = toThreadItem({
      id: 'm1',
      role: 'ASSISTANT',
      content: 'Terminé.',
      createdAt: '2026-08-25T00:00:00Z',
      terminal: {
        blocks: [
          { tool: 'bash', command: 'ls', toolUseId: null, output: 'a', hasOutput: true, error: false },
        ],
        omittedBlocks: 0,
        inputTokens: 10,
        outputTokens: 5,
        activeSeconds: 1,
      },
    });

    expect(item.interrupted).toBeFalse();
  });

  // ---------------------------- F-33 / SF-33-03 : invite d'autorisation

  /** Lance un run qui pose une demande d'autorisation et ne se termine pas. */
  function runAwaitingConfirmation(): void {
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onAction({ tool: 'bash', detail: 'rm -rf build' });
      handlers.onConfirmRequest!({ toolUseId: 'sevt_1', tool: 'bash', detail: 'rm -rf build' });
      return Promise.resolve();
    });
    component.draft.set('nettoie le projet');
    component.send();
  }

  it("F-33 : une demande d'autorisation pose l'invite avec sa commande", () => {
    setup();
    runAwaitingConfirmation();

    const pending = component.pendingConfirmation();
    expect(pending).not.toBeNull();
    expect(pending!.toolUseId).toBe('sevt_1');
    expect(pending!.detail).toBe('rm -rf build');
    expect(pending!.answering).toBeFalse();
  });

  it('F-33 : Autoriser envoie allow et laisse la résolution retirer l\'invite', () => {
    setup();
    runAwaitingConfirmation();
    service.confirmToolUse.and.returnValue(of(void 0));

    component.answerConfirmation(true);

    expect(service.confirmToolUse).toHaveBeenCalledWith('w1', {
      toolUseId: 'sevt_1',
      decision: 'allow',
      reason: undefined,
    });
    // L'invite ne disparaît qu'à la résolution relayée par le flux : c'est elle qui prouve que la
    // décision est arrivée jusqu'à la session.
    expect(component.pendingConfirmation()).not.toBeNull();
  });

  it('F-33 : Refuser envoie deny avec le motif saisi', () => {
    setup();
    runAwaitingConfirmation();
    service.confirmToolUse.and.returnValue(of(void 0));

    component.startDenying();
    component.setConfirmationReason('  Ne touche pas au dossier build.  ');
    component.answerConfirmation(false);

    expect(service.confirmToolUse).toHaveBeenCalledWith('w1', {
      toolUseId: 'sevt_1',
      decision: 'deny',
      reason: 'Ne touche pas au dossier build.',
    });
  });

  it('F-33 : une seconde décision est ignorée pendant l\'envoi de la première', () => {
    setup();
    runAwaitingConfirmation();
    service.confirmToolUse.and.returnValue(of(void 0));

    component.answerConfirmation(true);
    component.answerConfirmation(false);

    expect(service.confirmToolUse).toHaveBeenCalledTimes(1);
  });

  it('F-33 : la résolution retire l\'invite, et un refus par expiration est annoncé', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onConfirmRequest!({ toolUseId: 'sevt_1', tool: 'bash', detail: 'rm -rf build' });
      handlers.onConfirmResolved!({ toolUseId: 'sevt_1', decision: 'timeout' });
      return Promise.resolve();
    });

    component.draft.set('nettoie');
    component.send();

    expect(component.pendingConfirmation()).toBeNull();
    expect(snackBar.open).toHaveBeenCalledWith(
      'Commande refusée : aucune réponse dans le délai imparti.', 'Fermer', { duration: 6000 });
  });

  it('F-33 : une réponse refusée par le serveur retire l\'invite avec un message lisible', () => {
    setup();
    runAwaitingConfirmation();
    service.confirmToolUse.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409 })));

    component.answerConfirmation(true);

    expect(component.pendingConfirmation()).toBeNull();
    expect(snackBar.open).toHaveBeenCalledWith(
      "L'exécution n'attend plus de réponse.", 'Fermer', jasmine.anything());
  });

  it('F-33 : la fin du run retire une invite restée en attente', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onConfirmRequest!({ toolUseId: 'sevt_1', tool: 'bash', detail: 'ls' });
      handlers.onDone({
        reply: 'Terminé.',
        changedFiles: [],
        inputTokens: 0,
        outputTokens: 0,
        activeSeconds: 0,
        interrupted: false,
      });
      return Promise.resolve();
    });

    component.draft.set('liste les fichiers');
    component.send();

    expect(component.pendingConfirmation()).toBeNull();
  });

  it("F-33 : la bascule enregistre l'option et dit qu'elle ne vaut pas pour la sandbox en cours", () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.activeDetail.set(detail);
    service.setAskBeforeBash.and.returnValue(of({ enabled: true, appliesToCurrentSession: false }));

    component.toggleAskBeforeBash();

    expect(service.setAskBeforeBash).toHaveBeenCalledWith('w1', true);
    expect(component.askBeforeBash()).toBeTrue();
    expect(component.togglingConfirmation()).toBeFalse();
    expect(snackBar.open).toHaveBeenCalledWith(
      jasmine.stringMatching('Prend effet à la prochaine sandbox'), 'Fermer', { duration: 6000 });
  });

  it('F-33 : un projet sans l\'option ne pose aucune invite (non-régression)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onAction({ tool: 'bash', detail: 'npm test' });
      handlers.onDone({
        reply: 'Tests OK.',
        changedFiles: [],
        inputTokens: 0,
        outputTokens: 0,
        activeSeconds: 0,
        interrupted: false,
      });
      return Promise.resolve();
    });

    component.draft.set('lance les tests');
    component.send();

    expect(component.pendingConfirmation()).toBeNull();
    expect(service.confirmToolUse).not.toHaveBeenCalled();
  });
  // ---- F-28 SF-28-16 : nommer et renommer un projet ----

  it('renomme le projet actif et met la liste à jour (F-28)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    dialog.open.and.returnValue({
      afterClosed: () => of('  Refonte SCRM  '),
    } as MatDialogRef<unknown, unknown>);
    service.renameWorkspace.and.returnValue(of({ ...detail, name: 'Refonte SCRM' }));

    component.renameActiveWorkspace();

    // Le nom est élagué avant l'appel.
    expect(service.renameWorkspace).toHaveBeenCalledWith('w1', 'Refonte SCRM');
    expect(component.workspaces()[0].name).toBe('Refonte SCRM');
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Projet renommé.');
  });

  it('un dialogue annulé ne renomme rien (F-28)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    dialog.open.and.returnValue({
      afterClosed: () => of(undefined),
    } as MatDialogRef<unknown, unknown>);

    component.renameActiveWorkspace();

    expect(service.renameWorkspace).not.toHaveBeenCalled();
  });

  it('un nom vide ne renomme rien (F-28)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    dialog.open.and.returnValue({
      afterClosed: () => of('   '),
    } as MatDialogRef<unknown, unknown>);

    component.renameActiveWorkspace();

    expect(service.renameWorkspace).not.toHaveBeenCalled();
  });

  it('un échec de renommage affiche un message lisible (F-28)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    dialog.open.and.returnValue({
      afterClosed: () => of('Nouveau nom'),
    } as MatDialogRef<unknown, unknown>);
    service.renameWorkspace.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));

    component.renameActiveWorkspace();

    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Projet introuvable.');
  });
  // ---- F-37 SF-37-02 : modifications du tour dans le fil ----

  it('mode Terminal : les modifications du tour rejoignent le fil, repliées (F-37)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onDone({
        reply: "C'est fait.",
        changedFiles: ['src/main.ts'],
        inputTokens: 0,
        outputTokens: 0,
        activeSeconds: 0,
        interrupted: false,
        diffs: [
          {
            path: 'src/main.ts',
            added: false,
            diff: '@@ -1,1 +1,1 @@\n-un\n+deux',
            addedLines: 1,
            removedLines: 1,
            omittedLines: 0,
            unreadable: false,
          },
        ],
      });
      return Promise.resolve();
    });

    component.draft.set('Corrige');
    component.send();

    const last = component.messages()[component.messages().length - 1];
    expect(last.diffs!.length).toBe(1);
    expect(last.diffs![0].path).toBe('src/main.ts');
    expect(last.diffs![0].addedLines).toBe(1);
    expect(last.diffs![0].expanded).toBeFalse();
  });

  it('mode Terminal : un flux sans modifications laisse le tour tel qu\'avant F-37', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.engine.set('HOSTED_SANDBOX');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onDone({
        reply: 'Rien à changer.',
        changedFiles: [],
        inputTokens: 0,
        outputTokens: 0,
        activeSeconds: 0,
        interrupted: false,
      });
      return Promise.resolve();
    });

    component.draft.set('Regarde');
    component.send();

    const last = component.messages()[component.messages().length - 1];
    expect(last.diffs).toEqual([]);
  });

  it('restitue les modifications d\'un tour Terminal depuis l\'historique (F-37)', () => {
    const item = toThreadItem({
      id: 'm1',
      role: 'ASSISTANT',
      content: "C'est fait.",
      createdAt: '2026-08-26T00:00:00Z',
      terminal: {
        blocks: [],
        omittedBlocks: 0,
        inputTokens: 0,
        outputTokens: 0,
        activeSeconds: 0,
        diffs: [
          {
            path: 'src/main.ts',
            added: true,
            diff: '@@ -0,0 +1,1 @@\n+un',
            addedLines: 1,
            removedLines: 0,
            omittedLines: 0,
            unreadable: false,
          },
        ],
      },
    });

    expect(item.diffs!.length).toBe(1);
    expect(item.diffs![0].added).toBeTrue();
    expect(item.diffs![0].expanded).toBeFalse();
  });

  it('un tour d\'historique sans modifications reste sans section (non-régression F-37)', () => {
    const item = toThreadItem({
      id: 'm1',
      role: 'ASSISTANT',
      content: 'Tests verts.',
      createdAt: '2026-08-26T00:00:00Z',
      terminal: {
        blocks: [],
        omittedBlocks: 0,
        inputTokens: 0,
        outputTokens: 0,
        activeSeconds: 0,
      },
    });

    expect(item.diffs).toBeUndefined();
  });
  // --- Reprise du fil (F-39 / SF-39-04) ------------------------------------------------------

  it('ne propose aucun choix de reprise sur un projet actif', () => {
    setup();
    component.selectWorkspace({ ...summary, id: 'w-resume' });

    expect(service.getResume).toHaveBeenCalledWith('w-resume');
    expect(component.resumeChoice()).toBeFalse();
  });

  it('propose le choix de reprise sur un projet inactif', () => {
    setup();
    service.getResume.and.returnValue(
      of({
        turns: 6,
        lastMessageAt: '2026-08-01T10:00:00Z',
        threadStartedAt: null,
        prompt: 'IDLE' as const,
      }),
    );

    component.selectWorkspace({ ...summary, id: 'w-resume' });

    expect(component.resumeChoice()).toBeTrue();
    expect(component.resumeTurns()).toBe(6);
  });

  it('« reprendre le fil » ferme la proposition sans rien appeler', () => {
    setup();
    service.getResume.and.returnValue(
      of({ turns: 3, lastMessageAt: null, threadStartedAt: null, prompt: 'IDLE' as const }),
    );
    component.selectWorkspace({ ...summary, id: 'w-resume' });

    component.keepThread();

    expect(component.resumeChoice()).toBeFalse();
    expect(service.restartThread).not.toHaveBeenCalled();
  });

  it('« repartir à neuf » appelle le service, ferme la proposition et le dit', () => {
    setup();
    service.getResume.and.returnValue(
      of({ turns: 3, lastMessageAt: null, threadStartedAt: null, prompt: 'IDLE' as const }),
    );
    service.restartThread.and.returnValue(
      of({
        turns: 0,
        lastMessageAt: null,
        threadStartedAt: '2026-09-06T00:00:00Z',
        prompt: 'NONE' as const,
      }),
    );
    component.selectWorkspace({ ...summary, id: 'w-resume' });

    component.restartThread();

    expect(service.restartThread).toHaveBeenCalledWith('w-resume');
    expect(component.resumeChoice()).toBeFalse();
    expect(component.resumeTurns()).toBe(0);
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('un état de reprise illisible ne bloque pas le travail', () => {
    setup();
    service.getResume.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));

    component.selectWorkspace({ ...summary, id: 'w-resume' });

    expect(component.resumeChoice()).toBeFalse();
  });
  // ------------------------------------------------- SF-38-16 : projet sur ma machine

  it('creates a local project from a name alone, then opens pairing', () => {
    setup();
    const localDetail = { ...detail, id: 'w-local', source: 'LOCAL' as const, executionTarget: 'RUNNER' as const };
    service.createLocalWorkspace.and.returnValue(of(localDetail));
    // Le dialogue sert deux fois : la saisie du nom, puis l'écran d'appairage.
    dialog.open.and.returnValue({
      afterClosed: () => of('runner-claude'),
    } as MatDialogRef<unknown, unknown>);

    component.openLocalProjectDialog();

    // Un NOM, et rien d'autre : aucun chemin ne transite.
    expect(service.createLocalWorkspace).toHaveBeenCalledWith('runner-claude');
    expect(component.activeWorkspaceId()).toBe('w-local');
    // L'appairage s'enchaîne : un projet local sans machine n'a nulle part où travailler.
    expect(dialog.open).toHaveBeenCalledTimes(2);
  });

  it('does not create anything when the name dialog is dismissed', () => {
    setup();
    dialog.open.and.returnValue({
      afterClosed: () => of(undefined),
    } as MatDialogRef<unknown, unknown>);

    component.openLocalProjectDialog();

    expect(service.createLocalWorkspace).not.toHaveBeenCalled();
  });

  it('reports a creation failure without leaving the button spinning', () => {
    setup();
    service.createLocalWorkspace.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })),
    );
    dialog.open.and.returnValue({
      afterClosed: () => of('runner-claude'),
    } as MatDialogRef<unknown, unknown>);

    component.openLocalProjectDialog();

    expect(snackBar.open).toHaveBeenCalled();
    expect(component.creating()).toBeFalse();
  });

  it('shows the machine folder only once the runner has declared it', () => {
    setup();
    fixture.detectChanges();
    component.activeWorkspaceId.set('w1');
    // Projet d'archive : aucun dossier de machine à afficher.
    expect(component.localFolder()).toBeNull();

    component.workspaces.set([
      { ...summary, id: 'w1', source: 'LOCAL', runnerRootName: 'runner-claude' },
    ]);
    expect(component.localFolder()).toBe('runner-claude');

    // Projet local dont aucune machine ne s'est encore appairée : rien plutôt qu'un nom inventé.
    component.workspaces.set([{ ...summary, id: 'w1', source: 'LOCAL' }]);
    expect(component.localFolder()).toBeNull();
  });

  // ------------------------------------------------- SF-39-13 : le plan de travail

  it('shows the plan sent during a turn and replaces it on update', () => {
    setup();
    const captured: { onPlan?: (steps: { title: string; status: string }[]) => void } = {};
    service.streamChat.and.callFake((_id: string, _msg: string, handlers: typeof captured) => {
      captured.onPlan = handlers.onPlan;
      return Promise.resolve();
    });
    component.activeWorkspaceId.set('w1');
    // Le plan n'existe que sur la boucle maison : c'est elle qui le pose (F-39 / SF-39-13).
    component.engine.set('LOCAL_MACHINE');
    component.draft.set('vas-y');
    component.send();

    captured.onPlan?.([{ title: 'Lire', status: 'active' }]);
    expect(component.execStreaming()?.plan).toEqual([{ title: 'Lire', status: 'active' }]);

    // Remplacement, jamais fusion : le dernier plan est celui qui vaut.
    captured.onPlan?.([
      { title: 'Lire', status: 'done' },
      { title: 'Écrire', status: 'active' },
    ]);
    expect(component.execStreaming()?.plan).toHaveSize(2);
    expect(component.execStreaming()?.plan?.[0].status).toBe('done');
  });

  // ------------------------------------------------- SF-39-18 : l'explorateur ne tue plus le tour

  it('opens the file explorer as an overlay, without navigating away from the terminal', () => {
    setup();
    component.activeWorkspaceId.set('w1');

    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    component.openFileExplorer();

    // Le panneau s'ouvre, le terminal reste monté : c'est ce qui garde vivant le flux du tour.
    expect(component.filesExplorerOpen()).toBeTrue();
    // Aucune navigation de ROUTE : seul un paramètre de requête change.
    expect(navigate.calls.mostRecent().args[0]).toEqual([]);
    expect(
      (navigate.calls.mostRecent().args[1] as { queryParams: Record<string, unknown> })
        .queryParams['vue'],
    ).toBe('fichiers');
  });

  it('closes the overlay and clears the query parameter', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    component.openFileExplorer();

    component.closeFileExplorer();

    expect(component.filesExplorerOpen()).toBeFalse();
    expect(
      (navigate.calls.mostRecent().args[1] as { queryParams: Record<string, unknown> })
        .queryParams['vue'],
    ).toBeNull();
  });

  it('opens a given file in the same overlay rather than on its own route', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);

    // Même panneau que l'explorateur : ouvrir un fichier — celui d'instructions compris — ne doit
    // pas non plus détruire le terminal (F-39 / SF-39-18).
    component.openFileExplorer('CLAUDE.md');

    expect(component.filesExplorerOpen()).toBeTrue();
    expect(component.filesExplorerPath()).toBe('CLAUDE.md');
  });

  // ------------------------------------------------- SF-38-20 : tout autoriser pour ce message

  it('sends the blanket approval only when asked for it', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    service.confirmChatToolUse.and.returnValue(of(void 0));
    component.pendingConfirmation.set({
      toolUseId: 'tu1',
      tool: 'bash',
      detail: 'npm install',
      reason: '',
      denying: false,
      answering: false,
      source: 'LOCAL_MACHINE',
    });

    component.answerConfirmation(true, true);

    const decision = service.confirmChatToolUse.calls.mostRecent().args[1];
    expect(decision.decision).toBe('allow');
    expect(decision.allowAll).toBeTrue();
  });

  it('does not send the blanket approval on a plain authorisation', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    service.confirmChatToolUse.and.returnValue(of(void 0));
    component.pendingConfirmation.set({
      toolUseId: 'tu1',
      tool: 'bash',
      detail: 'npm install',
      reason: '',
      denying: false,
      answering: false,
      source: 'LOCAL_MACHINE',
    });

    component.answerConfirmation(true);

    // Autoriser une commande n'autorise pas les suivantes : ce sont deux gestes distincts.
    expect(service.confirmChatToolUse.calls.mostRecent().args[1].allowAll).toBeUndefined();
  });
});


// ---- F-30 SF-30-10 : ouvrir un projet (et son terminal) depuis l'URL ----

describe('AtelierComponent — projet demandé par l\'URL (F-30 SF-30-10)', () => {
  let service: jasmine.SpyObj<AtelierService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  function setupWithUrl(id: string | null, mode: string | null) {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'createWorkspace', 'listWorkspaces', 'getWorkspace',
      'getEngine', 'getFile', 'writeFile',
      'importLibrary', 'chat', 'streamChat', 'streamAgent', 'resetAgentSession', 'getHistory', 'getResume', 'restartThread',
    ]);
    const apiKeyService = jasmine.createSpyObj<ApiKeyService>('ApiKeyService', ['getStatus']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    const dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    const urlSummary: WorkspaceSummary = {
      id: 'w1', name: 'projet', createdAt: '2026-08-26T00:00:00Z', source: 'ARCHIVE', gitRepo: null,
    };
    const urlDetail: WorkspaceDetail = {
      id: 'w1', name: 'projet', fileCount: 0, files: [], createdAt: '2026-08-26T00:00:00Z',
      source: 'ARCHIVE', gitRepoUrl: null, gitRepo: null, gitBranch: null, truncated: false,
    };
    const urlStatus: ApiKeyStatus = {
      present: false, maskedKey: null, last4: null, provider: null, mode: 'HOSTED',
      validatedAt: null, createdAt: null,
    };
    service.listWorkspaces.and.returnValue(of([urlSummary]));
    service.getWorkspace.and.returnValue(of(urlDetail));
    service.getHistory.and.returnValue(of([]));
    service.getEngine.and.returnValue(of({
      engine: 'HOSTED_SANDBOX' as const, runnerConnected: false, runnerLastSeenAt: null,
      recommendRunner: false, recommendReason: null,
    }));
    // SF-39-04 : par défaut, la reprise du fil ne demande rien.
    service.getResume.and.returnValue(
      of({ turns: 0, lastMessageAt: null, threadStartedAt: null, prompt: 'NONE' as const }),
    );
    apiKeyService.getStatus.and.returnValue(of(urlStatus));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AtelierComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AtelierService, useValue: service },
        { provide: ApiKeyService, useValue: apiKeyService },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialog, useValue: dialog },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: { get: (k: string) => (k === 'id' ? id : null) },
              queryParamMap: { get: (k: string) => (k === 'mode' ? mode : null) },
            },
            // Panneau d'explorateur piloté par `?vue=` (F-39 / SF-39-18) : aucun ici.
            queryParamMap: of({ get: () => null } as unknown as ParamMap),
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(AtelierComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('sélectionne le projet nommé dans l\'URL et l\'ouvre dans le terminal', () => {
    const fixture = setupWithUrl('w1', null);

    expect(fixture.componentInstance.activeWorkspaceId()).toBe('w1');
    // F-39 / SF-39-08 : un projet ouvert EST un terminal, sans qu'aucun mode ne soit demandé.
    expect(fixture.nativeElement.querySelector('app-atelier-terminal')).not.toBeNull();
  });

  it('accepte et ignore ?mode=terminal des liens antérieurs (F-30 SF-30-10)', () => {
    const fixture = setupWithUrl('w1', 'terminal');

    expect(fixture.componentInstance.activeWorkspaceId()).toBe('w1');
    expect(fixture.nativeElement.querySelector('app-atelier-terminal')).not.toBeNull();
  });

  it('sans identifiant, le comportement est inchangé (non-régression)', () => {
    const fixture = setupWithUrl(null, null);

    expect(fixture.componentInstance.activeWorkspaceId()).toBeNull();
    expect(fixture.nativeElement.querySelector('app-atelier-terminal')).toBeNull();
    expect(snackBar.open).not.toHaveBeenCalled();
  });

  it('un projet inconnu retombe sur l\'écran habituel avec un message', () => {
    const fixture = setupWithUrl('inconnu', 'terminal');

    expect(fixture.componentInstance.activeWorkspaceId()).toBeNull();
    expect(fixture.nativeElement.querySelector('app-atelier-terminal')).toBeNull();
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Projet introuvable.');
  });

});


// ---- F-38 SF-38-06 : cible d'exécution, état du runner, écran d'appairage ----

describe('AtelierComponent — écrans runner (F-38 SF-38-06)', () => {
  let service: jasmine.SpyObj<AtelierService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let fixture: ComponentFixture<AtelierComponent>;
  let component: AtelierComponent;

  const runnerSummary: WorkspaceSummary = {
    id: 'w1', name: 'projet', createdAt: '2026-08-30T00:00:00Z', source: 'ARCHIVE', gitRepo: null,
  };
  const sandboxDetail: WorkspaceDetail = {
    id: 'w1', name: 'projet', fileCount: 1, files: ['src/main.ts'],
    createdAt: '2026-08-30T00:00:00Z', source: 'ARCHIVE', gitRepoUrl: null, gitRepo: null,
    gitBranch: null, truncated: false, executionTarget: 'SANDBOX',
  };
  const runnerDetail: WorkspaceDetail = { ...sandboxDetail, executionTarget: 'RUNNER' };
  const runnerGitDetail: WorkspaceDetail = {
    ...runnerDetail, source: 'GIT', gitRepoUrl: 'https://github.com/octocat/hello',
    gitRepo: 'octocat/hello', gitBranch: 'main',
  };

  function setup(detail: WorkspaceDetail = sandboxDetail): void {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'createWorkspace', 'listWorkspaces', 'getWorkspace',
      'getEngine', 'getFile', 'writeFile',
      'importLibrary', 'chat', 'streamChat', 'streamAgent', 'resetAgentSession', 'getHistory', 'getResume', 'restartThread',
      'setExecutionTarget', 'getRunnerStatus', 'createRunnerPairingCode', 'downloadRunnerJar',
    ]);
    const apiKeyService = jasmine.createSpyObj<ApiKeyService>('ApiKeyService', ['getStatus']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    const status: ApiKeyStatus = {
      present: false, maskedKey: null, last4: null, provider: null, mode: 'HOSTED',
      validatedAt: null, createdAt: null,
    };
    apiKeyService.getStatus.and.returnValue(of(status));
    service.listWorkspaces.and.returnValue(of([runnerSummary]));
    service.getWorkspace.and.returnValue(of(detail));
    service.getHistory.and.returnValue(of([]));
    // Le moteur vient de la gateway (SF-39-07) : ici, il suit la cible du projet sous test.
    service.getEngine.and.returnValue(of({
      engine: detail.executionTarget === 'RUNNER'
        ? ('LOCAL_MACHINE' as const) : ('HOSTED_SANDBOX' as const),
      runnerConnected: true, runnerLastSeenAt: null,
      recommendRunner: false, recommendReason: null,
    }));
    // SF-39-04 : par défaut, la reprise du fil ne demande rien.
    service.getResume.and.returnValue(
      of({ turns: 0, lastMessageAt: null, threadStartedAt: null, prompt: 'NONE' as const }),
    );
    service.getRunnerStatus.and.returnValue(
      of({ connected: true, lastSeenAt: new Date().toISOString() }));

    TestBed.configureTestingModule({
      imports: [AtelierComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AtelierService, useValue: service },
        { provide: ApiKeyService, useValue: apiKeyService },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialog, useValue: dialog },
      ],
    });

    fixture = TestBed.createComponent(AtelierComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component.selectWorkspace(runnerSummary);
    fixture.detectChanges();
  }

  it('la cible par défaut est SANDBOX et aucun statut runner n\'est relevé', () => {
    setup();
    expect(component.executionTarget()).toBe('SANDBOX');
    expect(component.runnerTarget()).toBeFalse();
    expect(service.getRunnerStatus).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it('bascule la cible vers RUNNER et relève aussitôt l\'état du runner', () => {
    setup();
    service.setExecutionTarget.and.returnValue(of(runnerDetail));

    component.setExecutionTarget('RUNNER');
    fixture.detectChanges();

    expect(service.setExecutionTarget).toHaveBeenCalledWith('w1', 'RUNNER');
    expect(component.executionTarget()).toBe('RUNNER');
    expect(service.getRunnerStatus).toHaveBeenCalledWith('w1');
    expect(component.runnerStatus()?.connected).toBeTrue();
    fixture.destroy();
  });

  it('laisse la cible inchangée quand le backend refuse la bascule', () => {
    setup();
    service.setExecutionTarget.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })));

    component.setExecutionTarget('RUNNER');

    expect(component.executionTarget()).toBe('SANDBOX');
    expect(snackBar.open.calls.mostRecent().args[0])
      .toContain("La cible d'exécution n'a pas pu être changée");
    fixture.destroy();
  });

  it('signale un projet introuvable sur 404 de bascule', () => {
    setup();
    service.setExecutionTarget.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404 })));

    component.setExecutionTarget('RUNNER');

    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Projet introuvable.');
    fixture.destroy();
  });

  it('relève l\'état du runner à l\'ouverture d\'un projet en cible RUNNER', () => {
    setup(runnerDetail);
    expect(service.getRunnerStatus).toHaveBeenCalledWith('w1');
    expect(component.runnerStatusLabel()).toBe('Runner connecté');
    expect(component.runnerLastSeenLabel()).toContain('dernier signe de vie');
    fixture.destroy();
  });

  it('n\'ouvre aucune snackbar quand le relevé d\'état échoue', () => {
    setup(runnerDetail);
    snackBar.open.calls.reset();
    service.getRunnerStatus.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 503 })));

    component.refreshRunnerStatus();

    expect(component.runnerStatus()).toBeNull();
    expect(component.runnerStatusLabel()).toBe('État du runner inconnu');
    expect(snackBar.open).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it('annonce « aucun runner connecté » quand la passerelle ne voit personne', () => {
    setup(runnerDetail);
    service.getRunnerStatus.and.returnValue(of({ connected: false, lastSeenAt: null }));

    component.refreshRunnerStatus();

    expect(component.runnerStatusLabel()).toBe('Aucun runner connecté');
    expect(component.runnerLastSeenLabel()).toBeNull();
    fixture.destroy();
  });

  it('un projet en cible RUNNER tourne sur la machine de l\'utilisateur (F-39 SF-39-08)', () => {
    setup(runnerDetail);

    // Le moteur est lu auprès de la gateway, plus déduit de la source ni de la cible.
    expect(service.getEngine).toHaveBeenCalledWith('w1');
    expect(component.engine()).toBe('LOCAL_MACHINE');
    fixture.destroy();
  });

  it('un projet Git en cible RUNNER tourne aussi sur la machine (le dépôt y est cloné)', () => {
    setup(runnerGitDetail);

    expect(component.engine()).toBe('LOCAL_MACHINE');
    fixture.destroy();
  });

  it('ouvre l\'écran d\'appairage avec le projet courant et relève l\'état au retour', () => {
    setup(runnerDetail);
    dialog.open.and.returnValue(
      { afterClosed: () => of(undefined) } as MatDialogRef<unknown>);
    service.getRunnerStatus.calls.reset();

    component.openRunnerPairing();

    expect(dialog.open).toHaveBeenCalled();
    expect(dialog.open.calls.mostRecent().args[1]?.data)
      .toEqual({ workspaceId: 'w1', workspaceName: 'projet' });
    expect(service.getRunnerStatus).toHaveBeenCalled();
    fixture.destroy();
  });

  it('tolère un type d\'action inconnu sans le déguiser en lecture', () => {
    setup();
    expect(component.stepLabel({ type: 'bash', path: 'npm test' })).toBe('npm test');
    expect(component.stepIcon('bash')).toBe('terminal');
    expect(component.stepIcon('inconnu')).toBe('bolt');
    expect(component.stepLabel({ type: 'inconnu' })).toBe('inconnu');
    // Les types connus gardent leur libellé historique.
    expect(component.stepLabel({ type: 'read', path: 'a.ts' })).toBe('Lecture de a.ts');
    expect(component.stepLabel({ type: 'list' })).toBe('Liste des fichiers');
    fixture.destroy();
  });
});

/**
 * Garde-fous d'exécution sur la machine connectée (F-38 / SF-38-08). Deux propriétés tiennent
 * l'écran : la décision part **au bon endroit** (mode Assistant ≠ session sandbox), et le
 * coupe-circuit ramène réellement le projet sur le sandbox hébergé.
 */
describe('AtelierComponent — garde-fous runner (F-38 / SF-38-08)', () => {
  let fixture: ComponentFixture<AtelierComponent>;
  let component: AtelierComponent;
  let service: jasmine.SpyObj<AtelierService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let dialog: jasmine.SpyObj<MatDialog>;

  const summary: WorkspaceSummary = {
    id: 'w1', name: 'projet', createdAt: '2026-08-30T00:00:00Z', source: 'ARCHIVE', gitRepo: null,
  };
  const runnerDetail: WorkspaceDetail = {
    id: 'w1', name: 'projet', fileCount: 1, files: ['src/main.ts'],
    createdAt: '2026-08-30T00:00:00Z', source: 'ARCHIVE', gitRepoUrl: null, gitRepo: null,
    gitBranch: null, truncated: false, executionTarget: 'RUNNER',
  };

  function setup(): void {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'createWorkspace', 'listWorkspaces', 'getWorkspace',
      'getEngine', 'getFile', 'writeFile',
      'importLibrary', 'chat', 'streamChat', 'streamAgent', 'resetAgentSession', 'getHistory', 'getResume', 'restartThread',
      'setExecutionTarget', 'getRunnerStatus', 'createRunnerPairingCode', 'downloadRunnerJar',
      'confirmToolUse', 'confirmChatToolUse', 'killRunner', 'getRunnerAudit', 'interruptChat',
    ]);
    const apiKeyService = jasmine.createSpyObj<ApiKeyService>('ApiKeyService', ['getStatus']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    apiKeyService.getStatus.and.returnValue(of({
      present: false, maskedKey: null, last4: null, provider: null, mode: 'HOSTED',
      validatedAt: null, createdAt: null,
    } as ApiKeyStatus));
    service.listWorkspaces.and.returnValue(of([summary]));
    service.getWorkspace.and.returnValue(of(runnerDetail));
    service.getHistory.and.returnValue(of([]));
    // Projet en cible « ma machine » : la gateway rend donc la boucle maison (SF-39-07).
    service.getEngine.and.returnValue(of({
      engine: 'LOCAL_MACHINE' as const, runnerConnected: true, runnerLastSeenAt: null,
      recommendRunner: false, recommendReason: null,
    }));
    // SF-39-04 : par défaut, la reprise du fil ne demande rien.
    service.getResume.and.returnValue(
      of({ turns: 0, lastMessageAt: null, threadStartedAt: null, prompt: 'NONE' as const }),
    );
    service.getRunnerStatus.and.returnValue(of({ connected: true, lastSeenAt: null }));

    TestBed.configureTestingModule({
      imports: [AtelierComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AtelierService, useValue: service },
        { provide: ApiKeyService, useValue: apiKeyService },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialog, useValue: dialog },
      ],
    });

    fixture = TestBed.createComponent(AtelierComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    component.selectWorkspace(summary);
    fixture.detectChanges();
  }

  /** Lance un tour Assistant qui s'arrête sur une demande d'autorisation. */
  function runAwaitingConfirmation(): void {
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onConfirmRequest!({ toolUseId: 'toolu_1', tool: 'bash', detail: 'npm test' });
      return Promise.resolve();
    });
    component.draft.set('lance les tests');
    component.send();
  }

  it("pose l'invite d'autorisation sur le tour de la boucle maison", () => {
    setup();
    runAwaitingConfirmation();

    const pending = component.pendingConfirmation();
    expect(pending).not.toBeNull();
    expect(pending!.source).toBe('LOCAL_MACHINE');
    expect(pending!.detail).toBe('npm test');
    fixture.destroy();
  });

  it('envoie la décision de la boucle maison sur /chat/confirm, pas sur la session sandbox', () => {
    setup();
    runAwaitingConfirmation();
    service.confirmChatToolUse.and.returnValue(of(void 0));

    component.answerConfirmation(true);

    expect(service.confirmChatToolUse).toHaveBeenCalledWith('w1', {
      toolUseId: 'toolu_1', decision: 'allow', reason: undefined,
    });
    // Le mauvais endpoint laisserait la commande en attente jusqu'à son refus automatique.
    expect(service.confirmToolUse).not.toHaveBeenCalled();
    fixture.destroy();
  });

  it('relaie le motif du refus au modèle', () => {
    setup();
    runAwaitingConfirmation();
    service.confirmChatToolUse.and.returnValue(of(void 0));

    component.startDenying();
    component.setConfirmationReason('  pas maintenant  ');
    component.answerConfirmation(false);

    expect(service.confirmChatToolUse).toHaveBeenCalledWith('w1', {
      toolUseId: 'toolu_1', decision: 'deny', reason: 'pas maintenant',
    });
    fixture.destroy();
  });

  it("annonce le refus automatique quand personne n'a répondu à temps", () => {
    setup();
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onConfirmRequest!({ toolUseId: 'toolu_1', tool: 'bash', detail: 'npm test' });
      handlers.onConfirmResolved!({ toolUseId: 'toolu_1', decision: 'timeout' });
      return Promise.resolve();
    });
    component.draft.set('lance');
    component.send();

    expect(component.pendingConfirmation()).toBeNull();
    expect(snackBar.open).toHaveBeenCalled();
    fixture.destroy();
  });

  it('le coupe-circuit coupe la liaison et ramène le projet sur le sandbox hébergé', () => {
    setup();
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as MatDialogRef<unknown>);
    service.killRunner.and.returnValue(
      of({ revokedTokens: 2, disconnected: true, executionTarget: 'SANDBOX' as const }));

    component.killRunner();
    fixture.detectChanges();

    expect(service.killRunner).toHaveBeenCalledWith('w1');
    expect(component.executionTarget()).toBe('SANDBOX');
    expect(component.runnerTarget()).toBeFalse();
    expect(snackBar.open).toHaveBeenCalled();
    fixture.destroy();
  });

  it('un coupe-circuit annulé ne coupe rien', () => {
    setup();
    dialog.open.and.returnValue({ afterClosed: () => of(false) } as MatDialogRef<unknown>);

    component.killRunner();

    expect(service.killRunner).not.toHaveBeenCalled();
    expect(component.executionTarget()).toBe('RUNNER');
    fixture.destroy();
  });

  it("ouvre le journal d'activité sur le projet courant", () => {
    setup();
    dialog.open.and.returnValue({ afterClosed: () => of(undefined) } as MatDialogRef<unknown>);

    component.openRunnerAudit();

    expect(dialog.open).toHaveBeenCalled();
    expect(dialog.open.calls.mostRecent().args[1]?.data)
      .toEqual({ workspaceId: 'w1', workspaceName: 'projet' });
    fixture.destroy();
  });


});
