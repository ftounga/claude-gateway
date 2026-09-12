import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';

import { MapFileDialogComponent, MapFileDialogData } from './map-file-dialog.component';
import { GovernanceService } from '../../core/services/governance.service';
import {
  GovernanceMapFile,
  GovernanceMapFileContent,
} from '../../core/models/governance.models';

/**
 * Un fichier de la carte, ouvert (F-92 / SF-92-03).
 *
 * <p>Ce que ces tests protègent : <b>rien n'est inventé</b>. Un échec de lecture ne devient jamais
 * un contenu vide — qui passerait pour une carte vide, c'est-à-dire le contresens exact que cette
 * feature combat.</p>
 */
describe('MapFileDialogComponent', () => {
  let fixture: ComponentFixture<MapFileDialogComponent>;
  let component: MapFileDialogComponent;
  let governance: jasmine.SpyObj<GovernanceService>;

  const file: GovernanceMapFile = {
    path: 'acces.md',
    title: 'Accès',
    present: true,
    readable: true,
    sections: [
      { title: 'Récapitulatif VPN', facts: 2 },
      { title: 'Les pièges', facts: 0 },
    ],
    facts: 2,
    truncated: false,
    message: null,
  };

  const content: GovernanceMapFileContent = {
    path: 'acces.md',
    title: 'Accès',
    present: true,
    content: '# Accès\n\n## Les pièges\n',
    truncated: false,
    message: null,
  };

  function build(answer = of(content)): void {
    governance = jasmine.createSpyObj<GovernanceService>('GovernanceService',
      ['getMap', 'readMapFile']);
    governance.readMapFile.and.returnValue(answer);
    const data: MapFileDialogData = { hostRef: 'h1', hostName: 'FREE', file };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [MapFileDialogComponent],
      providers: [
        { provide: GovernanceService, useValue: governance },
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close: jasmine.createSpy('close') } },
        provideNoopAnimations(),
      ],
    });
    fixture = TestBed.createComponent(MapFileDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('montre les sections, puis le contenu exact', () => {
    build();

    expect(governance.readMapFile).toHaveBeenCalledOnceWith('h1', 'acces.md');
    expect(text()).toContain('Récapitulatif VPN');
    expect(text()).toContain('2 fait(s)');
    // Une section encore vide le dit : c'est là que la connaissance manque.
    expect(text()).toContain('vide');
    expect(text()).toContain('## Les pièges');
  });

  it("un échec le DIT et offre « Réessayer » — il n'invente aucun contenu", () => {
    build(throwError(() => new HttpErrorResponse({ status: 500 })));

    expect(component.failed()).toBeTrue();
    expect(component.content()).toBeNull();
    expect(text()).toContain("n'a pas pu être lu");
    expect(text()).toContain('Réessayer');
  });

  it('« Réessayer » rejoue la lecture', () => {
    build(throwError(() => new HttpErrorResponse({ status: 500 })));
    governance.readMapFile.and.returnValue(of(content));

    component.read();
    fixture.detectChanges();

    expect(governance.readMapFile).toHaveBeenCalledTimes(2);
    expect(component.failed()).toBeFalse();
    expect(text()).toContain('## Les pièges');
  });

  it('une coupe se DIT — une troncature muette laisserait croire qu’on a tout lu', () => {
    build(of({ ...content, truncated: true }));

    expect(text()).toContain('trop long');
  });

  it("un fichier absent porte son geste, et aucun contenu n'est affiché", () => {
    build(of({
      ...content,
      present: false,
      content: '',
      message: 'Ce fichier de carte manque : reprenez « Appliquer » sur ce poste pour le reposer.',
    }));

    expect(text()).toContain('Appliquer');
    expect((fixture.nativeElement as HTMLElement).querySelector('.map-file__content')).toBeNull();
  });
});
