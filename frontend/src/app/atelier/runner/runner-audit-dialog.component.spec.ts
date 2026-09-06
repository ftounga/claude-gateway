import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { RunnerAuditEntry } from '../../core/models/atelier.models';
import { AtelierService } from '../../core/services/atelier.service';
import {
  RunnerAuditDialogComponent,
  RunnerAuditDialogData,
} from './runner-audit-dialog.component';

/**
 * Journal d'activité de la machine (F-38 / SF-38-08, décision D11). Ce que l'écran doit garantir :
 * dire ce qui a été fait, en français, et **distinguer** un journal vide d'un journal illisible.
 */
describe('RunnerAuditDialogComponent', () => {
  let fixture: ComponentFixture<RunnerAuditDialogComponent>;
  let component: RunnerAuditDialogComponent;
  let service: jasmine.SpyObj<AtelierService>;

  const data: RunnerAuditDialogData = { workspaceId: 'w1', workspaceName: 'projet' };

  const entry: RunnerAuditEntry = {
    id: 'a1', callId: 'toolu_1', tool: 'bash', target: 'npm test', outcome: 'DENIED',
    errorCode: 'denied', exitCode: null, durationMs: 0, bytes: null,
    createdAt: '2026-08-30T10:00:00Z',
  };

  function setup(): void {
    service = jasmine.createSpyObj<AtelierService>('AtelierService', ['getRunnerAudit']);
    service.getRunnerAudit.and.returnValue(of([entry]));

    TestBed.configureTestingModule({
      imports: [RunnerAuditDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: AtelierService, useValue: service },
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: jasmine.createSpyObj('MatDialogRef', ['close']) },
      ],
    });

    fixture = TestBed.createComponent(RunnerAuditDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it("relève le journal du projet à l'ouverture", () => {
    setup();

    expect(service.getRunnerAudit).toHaveBeenCalledWith('w1', 100);
    expect(component.entries()).toEqual([entry]);
    expect(fixture.nativeElement.textContent).toContain('npm test');
    fixture.destroy();
  });

  it('traduit action et issue en français, sans jargon de protocole', () => {
    setup();

    expect(component.toolLabel(entry)).toBe('Commande');
    expect(component.outcomeLabel(entry)).toBe('Refusé');
    expect(component.isFailure(entry)).toBeTrue();
    expect(component.toolLabel({ ...entry, tool: 'read_file' })).toBe('Lecture');
    expect(component.toolLabel({ ...entry, tool: 'bootstrap' })).toBe('Consigne système');
    // F-39 / SF-39-06 : le journal distingue un passage remplacé d'un fichier réécrit, même si
    // l'écran, lui, traite les deux comme une écriture.
    expect(component.toolLabel({ ...entry, tool: 'edit_file' })).toBe('Édition ciblée');
    expect(component.toolIcon({ ...entry, tool: 'edit_file' })).toBe('edit_note');
    // Un outil inconnu s'affiche tel quel plutôt que de disparaître du journal.
    expect(component.toolLabel({ ...entry, tool: 'inconnu' })).toBe('inconnu');
    expect(component.outcomeLabel({ ...entry, outcome: 'OK' })).toBe('Effectué');
    expect(component.outcomeLabel({ ...entry, outcome: 'TIMEOUT' })).toBe('Délai dépassé');
    fixture.destroy();
  });

  it('affiche le code de sortie ou le code d\'erreur, jamais un contenu', () => {
    setup();

    expect(component.detailLabel(entry)).toBe('denied');
    expect(component.detailLabel({ ...entry, errorCode: null, exitCode: 1 })).toBe('code 1');
    expect(component.detailLabel({ ...entry, errorCode: null, exitCode: null, durationMs: 42 }))
      .toBe('42 ms');
    fixture.destroy();
  });

  it('distingue un journal vide d\'un journal illisible', () => {
    setup();
    service.getRunnerAudit.and.returnValue(of([]));
    component.refresh();
    fixture.detectChanges();
    expect(component.error()).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Aucune activité enregistrée');

    service.getRunnerAudit.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 500 })));
    component.refresh();
    fixture.detectChanges();
    expect(component.error()).toBe("Le journal n'a pas pu être relu. Veuillez réessayer.");
    fixture.destroy();
  });

  it('un projet introuvable est dit tel quel', () => {
    setup();
    service.getRunnerAudit.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 404 })));

    component.refresh();

    expect(component.error()).toBe('Projet introuvable.');
    expect(component.entries()).toEqual([]);
    fixture.destroy();
  });
});
