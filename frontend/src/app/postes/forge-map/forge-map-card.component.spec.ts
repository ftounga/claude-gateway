import { Clipboard } from '@angular/cdk/clipboard';
import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { MapCard } from '../../core/models/governance.models';
import { GovernanceService } from '../../core/services/governance.service';
import { ForgeMapCardComponent, factSource, relationPhrase } from './forge-map-card.component';
import { mapNode } from './forge-map.testing';

describe('ForgeMapCardComponent (F-173 / SF-173-03)', () => {
  let fixture: ComponentFixture<ForgeMapCardComponent>;
  let governance: jasmine.SpyObj<GovernanceService>;
  let clipboard: jasmine.SpyObj<Clipboard>;
  let dialog: jasmine.SpyObj<MatDialog>;

  const card: MapCard = {
    node: mapNode('lzi', {
      label: 'lzi-prod', environment: 'prod', state: 'joignable', identifiers: ['lzi.cagip.fr'],
      observedOn: '2026-05-01', stale: true,
    }),
    totalFacts: 3,
    facts: [
      { path: 'acces.md', heading: 'Proxy', lineNo: 7, text: '- ne jamais redémarrer à la main', kind: 'PIEGE', observedOn: null, dueOn: null, stale: false },
      { path: 'infra.md', heading: null, lineNo: 3, text: '- 3 nœuds', kind: 'FAIT', observedOn: '2026-05-01', dueOn: null, stale: true },
    ],
    relations: [
      { nature: 'dans', direction: 'out', otherId: 'compte', otherLabel: 'compte prod' },
      { nature: 'accede_a', direction: 'in', otherId: 'proxy', otherLabel: 'netskope' },
    ],
    sources: [{ path: 'infra.md', heading: 'Clusters' }],
  };

  function build(response = of(card) as ReturnType<GovernanceService['hostMapEntity']>): void {
    governance = jasmine.createSpyObj<GovernanceService>('GovernanceService', ['hostMapEntity']);
    governance.hostMapEntity.and.returnValue(response);
    clipboard = jasmine.createSpyObj<Clipboard>('Clipboard', ['copy']);
    clipboard.copy.and.returnValue(true);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    TestBed.configureTestingModule({
      imports: [ForgeMapCardComponent],
      providers: [
        { provide: GovernanceService, useValue: governance },
        { provide: Clipboard, useValue: clipboard },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']) },
      ],
    });
    fixture = TestBed.createComponent(ForgeMapCardComponent);
    fixture.componentRef.setInput('hostRef', 'h1');
    fixture.componentRef.setInput('hostName', 'CAGIP');
    fixture.componentRef.setInput('nodeId', 'lzi');
    fixture.detectChanges();
  }

  const root = () => fixture.nativeElement as HTMLElement;

  it('lit la fiche de la ressource et met les pièges en tête', () => {
    build();
    expect(governance.hostMapEntity).toHaveBeenCalledOnceWith('h1', 'lzi');
    const sections = Array.from(root().querySelectorAll('.map-card__section h4')).map((h) => h.textContent?.trim());
    expect(sections[1]).toContain('Pièges (1)');
    expect(root().textContent).toContain('lzi-prod');
    expect(root().textContent).toContain('périmé');
  });

  it('copie un identifiant d’un clic', () => {
    build();
    (root().querySelector('.map-card__ids button') as HTMLButtonElement).click();
    expect(clipboard.copy).toHaveBeenCalledWith('lzi.cagip.fr');
  });

  it('la source d’un fait ouvre le fichier de la carte', () => {
    build();
    (root().querySelector('.map-card__source') as HTMLButtonElement).click();
    expect(dialog.open).toHaveBeenCalled();
    const data = dialog.open.calls.mostRecent().args[1]?.data as { hostRef: string; file: { path: string } };
    expect(data.hostRef).toBe('h1');
    expect(data.file.path).toBe('acces.md');
  });

  it('une relation est cliquable et mène à la ressource liée', () => {
    build();
    let target = '';
    fixture.componentInstance.navigate.subscribe((id) => (target = id));
    (root().querySelector('.map-card__link') as HTMLButtonElement).click();
    expect(target).toBe('compte');
  });

  it('une fiche illisible le dit, sans rien inventer', () => {
    build(throwError(() => new HttpErrorResponse({ status: 404 })));
    expect(root().textContent).toContain("La fiche n'a pas pu être lue.");
  });

  it('dit les relations et les sources en français', () => {
    expect(relationPhrase(card.relations[0])).toBe('est dans');
    expect(relationPhrase(card.relations[1])).toBe('est atteint par');
    expect(factSource(card.facts[0])).toBe('acces.md § Proxy · L7');
    expect(factSource(card.facts[1])).toBe('infra.md · L3');
  });
});
