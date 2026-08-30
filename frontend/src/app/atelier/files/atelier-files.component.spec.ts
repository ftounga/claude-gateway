import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { AtelierFilesComponent } from './atelier-files.component';
import { AtelierService } from '../../core/services/atelier.service';
import { FileContent, WorkspaceDetail } from '../../core/models/atelier.models';

describe('AtelierFilesComponent', () => {
  let fixture: ComponentFixture<AtelierFilesComponent>;
  let component: AtelierFilesComponent;
  let service: jasmine.SpyObj<AtelierService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let router: Router;

  const detail: WorkspaceDetail = {
    id: 'w1',
    name: 'projet',
    fileCount: 3,
    files: ['src/a.js', 'src/utils/b.js', 'bibliotheque/c.md'],
    createdAt: '2026-07-11T00:00:00Z',
    source: 'ARCHIVE',
    gitRepoUrl: null,
    gitRepo: null,
    gitBranch: null,
    truncated: false,
  };

  function setup(paramId: string | null = 'w1', queryPath: string | null = null): void {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', [
      'getWorkspace',
      'getFile',
      'writeFile',
      'deleteFile',
      'renameFile',
      'exportZip',
      'importLibrary',
      'commitGitFiles',
    ]);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    service.getWorkspace.and.returnValue(of(detail));
    // Stub par défaut : les tests qui s'intéressent au contenu le redéfinissent.
    service.getFile.and.returnValue(of({ path: 'src/a.js', content: 'const x = 1;' }));

    TestBed.configureTestingModule({
      imports: [AtelierFilesComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AtelierService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialog, useValue: dialog },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              paramMap: convertToParamMap(paramId ? { id: paramId } : {}),
              // `?path=` : fichier à ouvrir dès le chargement (F-34 / SF-34-02).
              queryParamMap: convertToParamMap(queryPath ? { path: queryPath } : {}),
            },
          },
        },
      ],
    });

    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);

    fixture = TestBed.createComponent(AtelierFilesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  /** Simule un MatDialogRef fermé avec la valeur donnée. */
  function dialogClosingWith(value: unknown): void {
    dialog.open.and.returnValue({
      afterClosed: () => of(value),
    } as MatDialogRef<unknown, unknown>);
  }

  it('charge le workspace à l\'init et construit l\'arbre', () => {
    setup();
    expect(service.getWorkspace).toHaveBeenCalledWith('w1');
    expect(component.workspaceName()).toBe('projet');
    // 2 dossiers (bibliotheque, src) avant 0 fichier racine.
    expect(component.nodes().map((n) => `${n.type}:${n.name}`)).toEqual([
      'folder:bibliotheque',
      'folder:src',
    ]);
    expect(component.fileCount()).toBe(3);
  });

  it('redirige vers /atelier si l\'id de route est absent', () => {
    setup(null);
    expect(router.navigate).toHaveBeenCalledWith(['/atelier']);
    expect(service.getWorkspace).not.toHaveBeenCalled();
  });

  it('sur un échec de chargement, passe en notFound et notifie', () => {
    setup();
    service.getWorkspace.and.returnValue(throwError(() => new Error('boom')));
    // Recharge explicite pour déclencher l'erreur.
    (component as unknown as { loadWorkspace: () => void }).loadWorkspace();
    expect(component.notFound()).toBeTrue();
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('sélectionne un fichier → getFile charge le contenu dans l\'aperçu', () => {
    setup();
    const content: FileContent = { path: 'src/a.js', content: 'const x = 1;' };
    service.getFile.and.returnValue(of(content));

    component.openFile('src/a.js');

    expect(service.getFile).toHaveBeenCalledWith('w1', 'src/a.js');
    expect(component.selectedPath()).toBe('src/a.js');
    expect(component.fileContent()).toBe('const x = 1;');
  });

  // ---------- SF-31-09 : éditer et publier un projet Git ----------

  /** Recharge le composant sur un projet Git, comme le ferait l'API. */
  function asGitProject(): void {
    setup();
    service.getWorkspace.and.returnValue(
      of({ ...detail, source: 'GIT', gitRepo: 'octocat/hello', gitBranch: 'main' } as WorkspaceDetail),
    );
    (component as unknown as { loadWorkspace: () => void }).loadWorkspace();
  }

  it('projet Git : enregistrer retient la modification sans appeler writeFile', () => {
    asGitProject();
    component.openFile('src/a.js');
    component.fileContent.set('const x = 2;');

    component.saveFile();

    expect(service.writeFile).not.toHaveBeenCalled();
    expect(component.pendingCount()).toBe(1);
  });

  it('projet Git : deux enregistrements du même fichier ne font qu’une modification', () => {
    asGitProject();
    component.openFile('src/a.js');
    component.fileContent.set('v1');
    component.saveFile();
    component.fileContent.set('v2');
    component.saveFile();

    expect(component.pendingCount()).toBe(1);
  });

  it('projet Git : rouvrir un fichier modifié montre la version retenue', () => {
    asGitProject();
    component.openFile('src/a.js');
    component.fileContent.set('retenu');
    component.saveFile();

    component.openFile('src/utils/b.js');
    component.openFile('src/a.js');

    expect(component.fileContent()).toBe('retenu');
  });

  it('projet Git : publier envoie un seul appel avec tous les fichiers, puis vide la file', () => {
    asGitProject();
    component.openFile('src/a.js');
    component.fileContent.set('un');
    component.saveFile();
    component.openFile('src/utils/b.js');
    component.fileContent.set('deux');
    component.saveFile();

    dialog.open.and.returnValue({
      afterClosed: () => of({ branch: 'claude/edition', message: 'Mes modifications' }),
    } as never);
    service.commitGitFiles.and.returnValue(
      of({
        branch: 'claude/edition',
        commitSha: 'abc123',
        branchCreated: true,
        compareUrl: 'https://github.com/octocat/hello/compare/main...claude/edition?expand=1',
        pullRequestUrl: null,
      }),
    );
    snackBar.open.and.returnValue({ onAction: () => of(void 0) } as never);

    component.publishEdits();

    expect(service.commitGitFiles).toHaveBeenCalledTimes(1);
    const [, branch, message, files] = service.commitGitFiles.calls.mostRecent().args;
    expect(branch).toBe('claude/edition');
    expect(message).toBe('Mes modifications');
    expect(files.length).toBe(2);
    expect(component.pendingCount()).toBe(0);
  });

  it('projet Git : après publication, le message nomme les deux branches sans conseiller de réinitialiser', () => {
    asGitProject();
    component.openFile('src/a.js');
    component.fileContent.set('un');
    component.saveFile();

    dialog.open.and.returnValue({
      afterClosed: () => of({ branch: 'claude/edition', message: 'm' }),
    } as never);
    service.commitGitFiles.and.returnValue(
      of({
        branch: 'claude/edition',
        commitSha: 'abc',
        branchCreated: true,
        compareUrl: 'https://github.com/octocat/hello/compare/main...claude/edition',
        pullRequestUrl: null,
      }),
    );
    snackBar.open.and.returnValue({ onAction: () => of(void 0) } as never);

    component.publishEdits();

    const messages = snackBar.open.calls.all().map((c) => String(c.args[0]));
    const warning = messages.find((m) => m.includes('La session Claude'));
    expect(warning).toContain('claude/edition');
    expect(warning).toContain('main');
    // Le conseil de réinitialisation était faux : la session repart de la branche du projet,
    // où le commit n'est pas.
    expect(warning).not.toContain('réinitialis');
  });

  it('projet Git : un échec de publication conserve les modifications', () => {
    asGitProject();
    component.openFile('src/a.js');
    component.fileContent.set('un');
    component.saveFile();

    dialog.open.and.returnValue({
      afterClosed: () => of({ branch: 'main', message: 'direct' }),
    } as never);
    service.commitGitFiles.and.returnValue(
      throwError(() => ({ status: 409, error: { message: 'Publier sur main est refusé.' } })),
    );

    component.publishEdits();

    expect(component.pendingCount()).withContext('la file survit à l’échec').toBe(1);
    expect(component.publishing()).toBeFalse();
  });

  it('projet Git : publier ne fait rien sans modification en attente', () => {
    asGitProject();

    component.publishEdits();

    expect(dialog.open).not.toHaveBeenCalled();
    expect(service.commitGitFiles).not.toHaveBeenCalled();
  });

  it('projet Git : quitter avec des modifications demande confirmation', () => {
    asGitProject();
    component.openFile('src/a.js');
    component.fileContent.set('un');
    component.saveFile();
    dialog.open.and.returnValue({ afterClosed: () => of(false) } as never);

    component.confirmLeave(['/atelier', 'w1']);

    expect(dialog.open).toHaveBeenCalled();
    expect(router.navigate).not.toHaveBeenCalledWith(['/atelier', 'w1'], {});
  });

  it('projet Git : confirmer la sortie navigue malgré les modifications', () => {
    asGitProject();
    component.openFile('src/a.js');
    component.fileContent.set('un');
    component.saveFile();
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as never);

    component.confirmLeave(['/atelier', 'w1']);

    expect(router.navigate).toHaveBeenCalledWith(['/atelier', 'w1'], {});
  });

  it('sans modification en attente, la sortie ne demande rien', () => {
    asGitProject();

    component.confirmLeave(['/atelier', 'w1']);

    expect(dialog.open).not.toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/atelier', 'w1'], {});
  });

  it('enregistre le fichier édité via writeFile', () => {
    setup();
    component.selectedPath.set('src/a.js');
    component.fileContent.set('const x = 2;');
    service.writeFile.and.returnValue(of(void 0));

    component.saveFile();

    expect(service.writeFile).toHaveBeenCalledWith('w1', 'src/a.js', 'const x = 2;');
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('supprimer : confirmation MatDialog → deleteFile + refresh', () => {
    setup();
    dialogClosingWith(true);
    service.deleteFile.and.returnValue(of(void 0));
    service.getWorkspace.calls.reset();
    service.getWorkspace.and.returnValue(of(detail));

    component.deleteFile('src/a.js');

    expect(dialog.open).toHaveBeenCalled();
    expect(service.deleteFile).toHaveBeenCalledWith('w1', 'src/a.js');
    // Refresh de l'arborescence après suppression.
    expect(service.getWorkspace).toHaveBeenCalledWith('w1');
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Fichier supprimé.');
  });

  it('supprimer : dialogue annulé → n\'appelle pas deleteFile', () => {
    setup();
    dialogClosingWith(false);

    component.deleteFile('src/a.js');

    expect(service.deleteFile).not.toHaveBeenCalled();
  });

  it('renommer : saisie MatDialog → renameFile met à jour l\'arbre', () => {
    setup();
    const renamed: WorkspaceDetail = { ...detail, files: ['src/renamed.js', 'src/utils/b.js', 'bibliotheque/c.md'] };
    dialogClosingWith('src/renamed.js');
    service.renameFile.and.returnValue(of(renamed));

    component.renameFile('src/a.js');

    expect(service.renameFile).toHaveBeenCalledWith('w1', 'src/a.js', 'src/renamed.js');
    expect(component.paths()).toEqual(renamed.files);
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Fichier renommé.');
  });

  it('renommer : même nom → n\'appelle pas renameFile', () => {
    setup();
    dialogClosingWith('src/a.js');

    component.renameFile('src/a.js');

    expect(service.renameFile).not.toHaveBeenCalled();
  });

  it('exporter : exportZip renvoie un blob et déclenche le téléchargement', () => {
    setup();
    const blob = new Blob(['zip-bytes'], { type: 'application/zip' });
    service.exportZip.and.returnValue(of(blob));
    const clickSpy = spyOn(HTMLAnchorElement.prototype, 'click');
    spyOn(URL, 'createObjectURL').and.returnValue('blob:x');
    spyOn(URL, 'revokeObjectURL');

    component.exportZip();

    expect(service.exportZip).toHaveBeenCalledWith('w1');
    expect(clickSpy).toHaveBeenCalled();
  });

  it('nouveau dossier : writeFile(.gitkeep) + refresh', () => {
    setup();
    dialogClosingWith('src/nouveau');
    service.writeFile.and.returnValue(of(void 0));
    service.getWorkspace.calls.reset();
    service.getWorkspace.and.returnValue(of(detail));

    component.newFolder();

    expect(service.writeFile).toHaveBeenCalledWith('w1', 'src/nouveau/.gitkeep', '');
    expect(service.getWorkspace).toHaveBeenCalledWith('w1');
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Dossier créé.');
  });

  it('ajout PC : fichier texte → writeFile + refresh (SF-28-13)', async () => {
    setup();
    service.writeFile.and.returnValue(of(void 0));
    service.getWorkspace.calls.reset();
    service.getWorkspace.and.returnValue(of(detail));
    const file = new File(['const x = 1;'], 'main.ts', { type: 'text/plain' });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;

    await component.onFilePicked(event);

    expect(service.writeFile).toHaveBeenCalledWith('w1', 'main.ts', 'const x = 1;');
    expect(service.getWorkspace).toHaveBeenCalledWith('w1');
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Fichier ajouté.');
  });

  it('ajout PC : fichier binaire (image) refusé sans writeFile (SF-28-13)', async () => {
    setup();
    const file = new File(['\x00\x01'], 'photo.png', { type: 'image/png' });
    const event = { target: { files: [file], value: 'x' } } as unknown as Event;

    await component.onFilePicked(event);

    expect(service.writeFile).not.toHaveBeenCalled();
    expect(snackBar.open.calls.mostRecent().args[0] as string).toContain('bibliothèque');
  });

  it('ajout bibliothèque : dialog → importLibrary met à jour l\'arbre (SF-28-13)', () => {
    setup();
    const imported: WorkspaceDetail = { ...detail, files: [...detail.files, 'bibliotheque/contrat.pdf.md'] };
    dialogClosingWith([{ id: 'd1', filename: 'contrat.pdf' }]);
    service.importLibrary.and.returnValue(of(imported));

    component.openLibraryPicker();

    expect(service.importLibrary).toHaveBeenCalledWith('w1', ['d1']);
    expect(component.paths()).toEqual(imported.files);
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Document importé.');
  });

  it('télécharger un fichier : getFile → blob téléchargé', () => {
    setup();
    service.getFile.and.returnValue(of({ path: 'src/a.js', content: 'const x = 1;' }));
    const clickSpy = spyOn(HTMLAnchorElement.prototype, 'click');
    spyOn(URL, 'createObjectURL').and.returnValue('blob:x');
    spyOn(URL, 'revokeObjectURL');

    component.downloadFile('src/a.js');

    expect(service.getFile).toHaveBeenCalledWith('w1', 'src/a.js');
    expect(clickSpy).toHaveBeenCalled();
  });

  it('recherche : filtre les chemins de l\'arbre (client-side)', () => {
    setup();
    component.search.set('utils');
    // Seul src/utils/b.js correspond → un seul dossier src contenant utils/b.js.
    expect(component.nodes().map((n) => n.name)).toEqual(['src']);
    expect(component.fileCount()).toBe(1);
  });

  it('repli/dépli d\'un dossier via toggleFolder', () => {
    setup();
    expect(component.isExpanded('src')).toBeTrue();
    component.toggleFolder('src');
    expect(component.isExpanded('src')).toBeFalse();
    component.toggleFolder('src');
    expect(component.isExpanded('src')).toBeTrue();
  });

  // ---- F-31 SF-31-03 : explorateur d'un projet Git (lecture seule) ----

  it("passe en lecture seule et affiche le dépôt sur un projet Git", () => {
    setup();
    service.getWorkspace.and.returnValue(
      of({
        ...detail,
        source: 'GIT' as const,
        gitRepoUrl: 'https://github.com/octocat/hello',
        gitRepo: 'octocat/hello',
        gitBranch: 'main',
        truncated: false,
      }),
    );
    component.ngOnInit();

    expect(component.readOnly()).toBeTrue();
    expect(component.gitRepo()).toBe('octocat/hello');
    expect(component.gitBranch()).toBe('main');
  });

  it("signale une arborescence partielle plutôt que de la présenter comme complète", () => {
    setup();
    service.getWorkspace.and.returnValue(
      of({
        ...detail,
        source: 'GIT' as const,
        gitRepoUrl: 'https://github.com/octocat/hello',
        gitRepo: 'octocat/hello',
        gitBranch: 'main',
        truncated: true,
      }),
    );
    component.ngOnInit();

    expect(component.truncated()).toBeTrue();
  });

  it("reste modifiable sur un projet d'archive", () => {
    setup();

    expect(component.readOnly()).toBeFalse();
    expect(component.gitRepo()).toBeNull();
    expect(component.truncated()).toBeFalse();
  });

  // ---- F-34 / SF-34-02 : ouverture directe du fichier désigné par `?path=` ----

  it('ouvre le fichier demandé en paramètre de requête', () => {
    setup('w1', 'src/a.js');

    expect(component.selectedPath()).toBe('src/a.js');
    expect(service.getFile).toHaveBeenCalledWith('w1', 'src/a.js');
    expect(component.fileContent()).toBe('const x = 1;');
  });

  it("ignore en silence un chemin absent de l'arborescence", () => {
    setup('w1', 'CLAUDE.md');

    // Le lien peut être obsolète (fichier renommé, supprimé) : l'explorateur s'ouvre normalement.
    expect(component.selectedPath()).toBeNull();
    expect(service.getFile).not.toHaveBeenCalled();
  });
});
