import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { HostProjectSummary } from '../../core/models/atelier.models';
import { KillHostDialogComponent, KillHostDialogData } from './kill-host-dialog.component';

/**
 * Le dialogue du coupe-circuit (F-82 / SF-82-02).
 *
 * <p><b>C'est le cœur de la subfeature.</b> Le geste, lui, ne change pas : il existait déjà
 * (SF-38-08). Ce qui manquait, c'est que l'écran dise ce que le libellé « Couper la liaison » ne
 * laisse pas deviner — les projets ramenés au bac à sable, le processus qui <b>continue de
 * tourner</b> sur la machine, et comment l'arrêter là-bas.</p>
 */
describe('KillHostDialogComponent', () => {
  let fixture: ComponentFixture<KillHostDialogComponent>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<KillHostDialogComponent, boolean>>;

  function project(name: string, target: 'RUNNER' | 'SANDBOX'): HostProjectSummary {
    return { id: name, name, projectPath: name, executionTarget: target, calls: 0, active: false };
  }

  function setup(data: KillHostDialogData): void {
    dialogRef = jasmine.createSpyObj<MatDialogRef<KillHostDialogComponent, boolean>>(
      'MatDialogRef', ['close']);
    TestBed.configureTestingModule({
      imports: [KillHostDialogComponent],
      providers: [
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: dialogRef },
        provideNoopAnimations(),
      ],
    });
    fixture = TestBed.createComponent(KillHostDialogComponent);
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function buttons(): HTMLButtonElement[] {
    return Array.from((fixture.nativeElement as HTMLElement)
      .querySelectorAll('mat-dialog-actions button'));
  }

  // ------------------------------------- (a) ce qui change tout de suite

  it('nomme les projets qui repassent en bac à sable, au lieu de les compter', () => {
    setup({
      hostName: 'Poste CAGIP',
      projects: [project('web', 'RUNNER'), project('api', 'RUNNER'), project('ops', 'RUNNER')],
    });

    // Un compte ne dit pas ce qu'on change ; un nom, si.
    expect(text()).toContain('web');
    expect(text()).toContain('api');
    expect(text()).toContain('ops');
    expect(text()).toContain('bac à sable');
  });

  it('marque les projets déjà au bac à sable : on ne promet pas un changement qui n\'aura pas lieu',
    () => {
      setup({
        hostName: 'Poste CAGIP',
        projects: [project('web', 'RUNNER'), project('api', 'SANDBOX')],
      });

      expect(text()).toContain('déjà au bac à sable');
      expect(fixture.componentInstance.returningCount()).toBe(1);
    });

  it('dit que le poste ne porte aucun projet — le cas vécu par le PO', () => {
    // Machine branchée, aucun projet : c'est précisément le poste qui n'a pas de terminal, donc
    // pas de bouton, et qui a motivé cette subfeature.
    setup({ hostName: 'Poste CAGIP', projects: [] });

    expect(fixture.componentInstance.hasNoProject()).toBeTrue();
    expect(text()).toContain('ne porte aucun projet');
    // Et ce n'est PAS un refus : le geste reste offert.
    expect(buttons().length).toBe(2);
  });

  it('ne marque pas « déjà au bac à sable » un projet dont la cible est inconnue', () => {
    // Gateway plus ancienne : le champ est absent. On ne marque que ce qu'on sait.
    setup({
      hostName: 'Poste CAGIP',
      projects: [{ id: 'w1', name: 'web', calls: 0, active: false }],
    });

    expect(text()).toContain('web');
    expect(text()).not.toContain('déjà au bac à sable');
    expect(fixture.componentInstance.returningCount()).toBe(1);
  });

  it('dit que les jetons sont révoqués et qu\'il faudra réappairer', () => {
    setup({ hostName: 'Poste CAGIP', projects: [] });

    expect(text()).toContain('révoqués');
    expect(text()).toContain('réappairer');
  });

  // ------------------------------------- (b) ce qui ne s'arrête pas

  it('dit que le runner continue de tourner sur la machine et sera refusé', () => {
    setup({ hostName: 'Poste CAGIP', projects: [] });

    expect(text()).toContain('continue de tourner sur la machine');
    expect(text()).toContain('reconnecter');
    expect(text()).toContain('refusé');
  });

  // ------------------------------------- (c) comment l'arrêter vraiment

  it('dit comment arrêter le runner SUR LA MACHINE, sans prétendre le faire', () => {
    setup({ hostName: 'Poste CAGIP', projects: [] });

    expect(text()).toContain('Ctrl-C');
    expect(text()).toContain('pkill -f claude-runner.jar');
    expect(text()).toContain('taskkill');
    // Hors périmètre absolu de F-82 : l'application ne s'arroge pas l'extinction à distance.
    expect(text()).toContain('ne peut pas arrêter un programme sur votre machine');
  });

  // ------------------------------------- le contrat du dialogue

  it('rend false sur Annuler et true sur Couper', () => {
    setup({ hostName: 'Poste CAGIP', projects: [] });
    const [cancel, confirm] = buttons();

    cancel.click();
    expect(dialogRef.close).toHaveBeenCalledWith(false);

    confirm.click();
    expect(dialogRef.close).toHaveBeenCalledWith(true);
  });

  it('nomme le poste dans son titre', () => {
    setup({ hostName: 'Poste CAGIP', projects: [] });

    expect(text()).toContain('Poste CAGIP');
  });
});
