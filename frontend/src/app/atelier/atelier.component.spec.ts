import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { AtelierComponent } from './atelier.component';
import { AtelierService } from '../core/services/atelier.service';
import {
  AtelierChatResponse,
  AtelierMessage,
  FileContent,
  WorkspaceDetail,
  WorkspaceSummary,
} from '../core/models/atelier.models';

describe('AtelierComponent', () => {
  let fixture: ComponentFixture<AtelierComponent>;
  let component: AtelierComponent;
  let service: jasmine.SpyObj<AtelierService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

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
      'getHistory',
    ]);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    // Valeurs par défaut : la liste est chargée à l'init.
    service.listWorkspaces.and.returnValue(of([summary]));
    service.getWorkspace.and.returnValue(of(detail));
    service.getHistory.and.returnValue(of([]));

    TestBed.configureTestingModule({
      imports: [AtelierComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AtelierService, useValue: service },
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

  it('notifies when the workspace list fails to load', () => {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'createWorkspace',
      'listWorkspaces',
      'getWorkspace',
      'getFile',
      'writeFile',
      'chat',
      'getHistory',
    ]);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    service.listWorkspaces.and.returnValue(throwError(() => new Error('boom')));

    TestBed.configureTestingModule({
      imports: [AtelierComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AtelierService, useValue: service },
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

  it('sends a message, appends the user turn and the assistant reply, and refreshes the tree', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    const response: AtelierChatResponse = {
      reply: 'C\'est fait.',
      actions: [{ type: 'write', path: 'src/main.ts' }],
      messageId: 'm-assistant',
    };
    service.chat.and.returnValue(of(response));
    service.getWorkspace.calls.reset();

    component.draft.set('Modifie main.ts');
    component.send();

    expect(service.chat).toHaveBeenCalledWith('w1', 'Modifie main.ts');
    const messages = component.messages();
    expect(messages.length).toBe(2);
    expect(messages[0].role).toBe('USER');
    expect(messages[1].role).toBe('ASSISTANT');
    expect(messages[1].content).toBe('C\'est fait.');
    expect(messages[1].actions.length).toBe(1);
    expect(component.draft()).toBe('');
    // L'arborescence est rafraîchie car un tour a pu écrire des fichiers.
    expect(service.getWorkspace).toHaveBeenCalledWith('w1');
  });

  it('does not send when the draft is blank', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    component.draft.set('   ');
    component.send();
    expect(service.chat).not.toHaveBeenCalled();
  });

  it('removes the optimistic user turn and notifies when sending fails', () => {
    setup();
    component.activeWorkspaceId.set('w1');
    service.chat.and.returnValue(throwError(() => new Error('boom')));

    component.draft.set('Fais un truc');
    component.send();

    expect(component.messages().length).toBe(0);
    expect(snackBar.open).toHaveBeenCalled();
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
