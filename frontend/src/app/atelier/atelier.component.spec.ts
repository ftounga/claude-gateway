import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
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
      'setAskBeforeBash',
      'confirmToolUse',
      'getHistory',
    ]);
    apiKeyService = jasmine.createSpyObj<ApiKeyService>('ApiKeyService', ['getStatus']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    // Valeurs par défaut : la liste est chargée à l'init.
    service.listWorkspaces.and.returnValue(of([summary]));
    service.getWorkspace.and.returnValue(of(detail));
    service.getHistory.and.returnValue(of([]));
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
      'getFile',
      'writeFile',
      'importLibrary',
      'chat',
      'streamChat',
      'getHistory',
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
      'getFile',
      'writeFile',
      'importLibrary',
      'chat',
      'streamChat',
      'getHistory',
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
      'getFile',
      'writeFile',
      'importLibrary',
      'chat',
      'streamChat',
      'getHistory',
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

    component.selectWorkspace(summary);

    expect(service.getHistory).toHaveBeenCalledWith('w1');
    expect(service.getWorkspace).toHaveBeenCalledWith('w1');
    expect(component.messages().length).toBe(1);
    expect(component.tree()).toEqual(['src/main.ts']);
  });

  it('streams a message: relays a step then replaces it with the final reply and refreshes the tree', () => {
    setup();
    component.activeWorkspaceId.set('w1');
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

  it('does not send when the draft is blank', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.draft.set('   ');
    component.send();
    expect(service.streamChat).not.toHaveBeenCalled();
  });

  it('removes the optimistic user turn and notifies when the stream fails', () => {
    setup();
    component.activeWorkspaceId.set('w1');
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

  it('mode Assistant (défaut) : send() appelle streamChat, pas streamAgent (non-régression)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    service.streamChat.and.callFake((_id, _message, handlers) => {
      handlers.onDone({ reply: 'ok', actions: [], messageId: 'm1' });
      return Promise.resolve();
    });

    component.draft.set('Lis main.ts');
    component.send();

    expect(service.streamChat).toHaveBeenCalled();
    expect(service.streamAgent).not.toHaveBeenCalled();
    expect(component.agentMode()).toBe('edit');
  });

  it('mode Terminal : send() appelle streamAgent (pas streamChat) et ajoute la réponse finale', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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

  it('ne change pas de mode pendant un envoi en cours', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.setAgentMode('exec');
    // Flux qui ne se termine pas : submitting reste vrai.
    service.streamAgent.and.callFake(() => Promise.resolve());
    component.draft.set('Tâche');
    component.send();
    expect(component.submitting()).toBeTrue();

    component.setAgentMode('edit');
    expect(component.agentMode()).toBe('exec');
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
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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

  it('affiche les modes « Assistant » et « Terminal », plus « Édition » / « Exécution » (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    fixture.detectChanges();

    const selector: HTMLElement = fixture.nativeElement.querySelector('.mode-selector');
    expect(selector.textContent).toContain('Assistant');
    expect(selector.textContent).toContain('Terminal');
    expect(selector.textContent).not.toContain('Édition');
    expect(selector.textContent).not.toContain('Exécution');
  });

  it('signale le mode Terminal comme capacité Gold par un badge (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    fixture.detectChanges();

    const badge: HTMLElement = fixture.nativeElement.querySelector('.mode-badge');
    expect(badge).not.toBeNull();
    expect(badge.textContent!.trim()).toBe('Gold');
  });

  it('les messages d\'erreur du flux reprennent le nom « Terminal » (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.setAgentMode('exec');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onError('agent_disabled');
      return Promise.resolve();
    });

    component.draft.set('Teste');
    component.send();

    expect(snackBar.open.calls.mostRecent().args[0]).toBe(
      'Le mode Terminal est momentanément indisponible.',
    );
  });

  it('le mode Terminal refusé (non-Gold) renvoie vers l\'offre Gold (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.setAgentMode('exec');
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onError('forbidden');
      return Promise.resolve();
    });

    component.draft.set('Teste');
    component.send();

    expect(snackBar.open.calls.mostRecent().args[0]).toBe(
      "Le mode Terminal est réservé à l'offre Gold.",
    );
  });

  it('les valeurs techniques de mode restent inchangées (non-régression F-30)', () => {
    setup();
    expect(component.agentMode()).toBe('edit');
    component.setAgentMode('exec');
    expect(component.agentMode()).toBe('exec');
  });
  // ---- F-30 SF-30-06 : réinitialiser la sandbox ----

  it('réinitialise la sandbox après confirmation (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.setAgentMode('exec');
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as MatDialogRef<unknown, unknown>);
    service.resetAgentSession.and.returnValue(of(void 0));

    component.resetSandbox();

    expect(service.resetAgentSession).toHaveBeenCalledWith('w1');
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Sandbox réinitialisée.');
  });

  it('n\'appelle rien si le dialogue est fermé sans confirmer (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.setAgentMode('exec');
    dialog.open.and.returnValue({ afterClosed: () => of(false) } as MatDialogRef<unknown, unknown>);

    component.resetSandbox();

    expect(service.resetAgentSession).not.toHaveBeenCalled();
  });

  it('affiche un message lisible si la réinitialisation échoue (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
    component.submitting.set(true);

    component.resetSandbox();

    expect(dialog.open).not.toHaveBeenCalled();
    expect(service.resetAgentSession).not.toHaveBeenCalled();
  });
  // ---- F-30 SF-30-05 : coût du tour (durée + tokens) ----

  it('affiche durée et tokens sur le tour terminé (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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

  it('passer en mode Terminal ouvre la vue immersive sans envoyer de message (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    fixture.detectChanges();
    // Mode Assistant : écran habituel, pas de terminal.
    expect(fixture.nativeElement.querySelector('app-atelier-terminal')).toBeNull();
    expect(fixture.nativeElement.querySelector('.atelier-layout')).not.toBeNull();

    component.setAgentMode('exec');
    fixture.detectChanges();

    // La vue terminal remplace l'écran : ni liste de projets, ni fil conversationnel.
    expect(fixture.nativeElement.querySelector('app-atelier-terminal')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.atelier-layout')).toBeNull();
    expect(service.streamAgent).not.toHaveBeenCalled();
    expect(service.streamChat).not.toHaveBeenCalled();
  });

  it('quitter la vue terminal restaure l\'écran habituel sans perdre la conversation (F-30)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.messages.set([
      { id: 'm1', role: 'USER', content: 'lance les tests', actions: [] },
    ]);
    component.setAgentMode('exec');
    fixture.detectChanges();

    component.setAgentMode('edit');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-atelier-terminal')).toBeNull();
    expect(fixture.nativeElement.querySelector('.atelier-layout')).not.toBeNull();
    expect(component.messages().length).toBe(1);
  });

  it('sans projet sélectionné, la vue terminal ne s\'ouvre pas (F-30)', () => {
    setup();
    component.activeWorkspaceId.set(null);
    component.setAgentMode('exec');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-atelier-terminal')).toBeNull();
  });
  it('credit_exhausted affiche un message qui n\'invite pas à réessayer (F-30 SF-30-08)', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.setAgentMode('exec');
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

    expect(component.agentMode()).toBe('exec');
  });

  it("refuse le mode Assistant sur un projet Git et l'explique", () => {
    setup();
    service.getWorkspace.and.returnValue(of(gitDetail));
    component.selectWorkspace({
      id: 'w2',
      name: 'hello',
      createdAt: '2026-08-25T00:00:00Z',
      source: 'GIT',
      gitRepo: 'octocat/hello',
    });

    component.setAgentMode('edit');

    expect(component.agentMode()).toBe('exec');
    expect(snackBar.open.calls.mostRecent().args[0]).toContain('Terminal');
  });

  it("laisse le mode Assistant disponible sur un projet d'archive", () => {
    setup();
    component.selectWorkspace(summary);

    component.setAgentMode('edit');

    expect(component.agentMode()).toBe('edit');
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

  it("quitter le terminal referme un projet Git au lieu d'un mode indisponible", () => {
    setup();
    openGitProject();

    component.leaveTerminal();

    expect(component.activeWorkspaceId()).toBeNull();
    expect(component.agentMode()).toBe('edit');
    expect(component.pushResult()).toBeNull();
  });

  it("quitter le terminal d'un projet d'archive revient au mode Assistant", () => {
    setup();
    component.selectWorkspace(summary);
    component.setAgentMode('exec');

    component.leaveTerminal();

    expect(component.agentMode()).toBe('edit');
    expect(component.activeWorkspaceId()).toBe('w1');
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
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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

    expect(navigate).toHaveBeenCalledWith(['/atelier', 'w1', 'fichiers'], {
      queryParams: { path: '.atelier/instructions.md' },
    });
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
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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
    component.setAgentMode('exec');
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
});


// ---- F-30 SF-30-10 : ouvrir un projet (et son terminal) depuis l'URL ----

describe('AtelierComponent — projet demandé par l\'URL (F-30 SF-30-10)', () => {
  let service: jasmine.SpyObj<AtelierService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  function setupWithUrl(id: string | null, mode: string | null) {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'createWorkspace', 'listWorkspaces', 'getWorkspace', 'getFile', 'writeFile',
      'importLibrary', 'chat', 'streamChat', 'streamAgent', 'resetAgentSession', 'getHistory',
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
          },
        },
      ],
    });
    const fixture = TestBed.createComponent(AtelierComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('sélectionne le projet nommé dans l\'URL', () => {
    const fixture = setupWithUrl('w1', null);

    expect(fixture.componentInstance.activeWorkspaceId()).toBe('w1');
    expect(fixture.componentInstance.agentMode()).toBe('edit');
  });

  it('ouvre directement le terminal avec ?mode=terminal', () => {
    const fixture = setupWithUrl('w1', 'terminal');

    expect(fixture.componentInstance.activeWorkspaceId()).toBe('w1');
    expect(fixture.componentInstance.agentMode()).toBe('exec');
    expect(fixture.nativeElement.querySelector('app-atelier-terminal')).not.toBeNull();
  });

  it('sans identifiant, le comportement est inchangé (non-régression)', () => {
    const fixture = setupWithUrl(null, null);

    expect(fixture.componentInstance.activeWorkspaceId()).toBeNull();
    expect(fixture.componentInstance.agentMode()).toBe('edit');
    expect(snackBar.open).not.toHaveBeenCalled();
  });

  it('un projet inconnu retombe sur l\'écran habituel avec un message', () => {
    const fixture = setupWithUrl('inconnu', 'terminal');

    expect(fixture.componentInstance.activeWorkspaceId()).toBeNull();
    // Le mode revient à Assistant : ouvrir un terminal sans projet n'aurait aucun sens.
    expect(fixture.componentInstance.agentMode()).toBe('edit');
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Projet introuvable.');
  });
});