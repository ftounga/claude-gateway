import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { MAX_UPLOAD_BYTES } from '../shared/http-error.util';

import { AtelierComponent } from './atelier.component';
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

  const summary: WorkspaceSummary = { id: 'w1', name: 'projet', createdAt: '2026-07-11T00:00:00Z' };
  const detail: WorkspaceDetail = {
    id: 'w1',
    name: 'projet',
    fileCount: 1,
    files: ['src/main.ts'],
    createdAt: '2026-07-11T00:00:00Z',
  };

  function setup(): void {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'createWorkspace',
      'listWorkspaces',
      'getWorkspace',
      'getFile',
      'writeFile',
      'chat',
      'streamChat',
      'streamAgent',
      'getHistory',
    ]);
    apiKeyService = jasmine.createSpyObj<ApiKeyService>('ApiKeyService', ['getStatus']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

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
      'listWorkspaces',
      'getWorkspace',
      'getFile',
      'writeFile',
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
      'listWorkspaces',
      'getWorkspace',
      'getFile',
      'writeFile',
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
      'listWorkspaces',
      'getWorkspace',
      'getFile',
      'writeFile',
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

    component.onZipPicked(event);

    expect(service.createWorkspace).toHaveBeenCalledWith(file);
    expect(component.activeWorkspaceId()).toBe('w1');
    expect(component.tree()).toEqual(['src/main.ts']);
  });

  it('notifies when workspace creation fails', () => {
    setup();
    service.createWorkspace.and.returnValue(throwError(() => new Error('bad zip')));
    const file = new File(['zip'], 'bad.zip', { type: 'application/zip' });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;

    component.onZipPicked(event);

    expect(snackBar.open).toHaveBeenCalled();
    expect(component.activeWorkspaceId()).toBeNull();
  });

  it('rejette côté client une archive trop volumineuse sans appeler le backend', () => {
    setup();
    const file = new File(['x'], 'gros.zip', { type: 'application/zip' });
    Object.defineProperty(file, 'size', { value: MAX_UPLOAD_BYTES + 1 });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;

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

  it('mode Édition (défaut) : send() appelle streamChat, pas streamAgent (non-régression)', () => {
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

  it('mode Exécution : send() appelle streamAgent (pas streamChat) et ajoute la réponse finale', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.setAgentMode('exec');
    // Le flux relaie un état, une commande bash, un commentaire, puis la réponse finale.
    service.streamAgent.and.callFake((_id, _message, handlers) => {
      handlers.onStatus('running');
      handlers.onAction({ tool: 'bash', detail: 'npm test' });
      handlers.onAgent('Je lance les tests…');
      handlers.onDone({ reply: 'Tests OK.', changedFiles: ['src/main.ts'] });
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

  it('mode Exécution : accumule l\'état, les étapes d\'outil et le texte partiel du tour en cours', () => {
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
    expect(live!.steps.map((s) => component.execStepLabel(s))).toEqual([
      'bash: npm install',
      'bash: npm test',
    ]);
    expect(live!.text).toBe('Installation puis tests.');
    expect(component.submitting()).toBeTrue();
  });

  it('mode Exécution : onError(forbidden) affiche le message Gold sans ajouter de message assistant', () => {
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

  it('mode Exécution : onError(session_timeout) affiche un message de délai dépassé', () => {
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
});
