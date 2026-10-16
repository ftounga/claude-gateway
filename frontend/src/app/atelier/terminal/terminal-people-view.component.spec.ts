import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { TerminalPeopleViewComponent } from './terminal-people-view.component';
import { Interlocuteur, InterlocuteurMergeSuggestion } from '../../core/models/terminal-actions.models';
import { TerminalActionsService } from '../../core/services/terminal-actions.service';
import { mockTerminalActionsService } from '../../core/services/terminal-actions.service.testing';

function person(id: string, displayName: string, over: Partial<Interlocuteur> = {}): Interlocuteur {
  return { id, displayName, role: null, team: null, radarPersonId: null, radarName: null, variants: [],
    openCount: 0, ...over };
}

/** La vue « Personnes » (F-180 / SF-180-03). */
describe('La vue Personnes (F-180 / SF-180-03)', () => {
  let fixture: ComponentFixture<TerminalPeopleViewComponent>;
  let component: TerminalPeopleViewComponent;
  let service: jasmine.SpyObj<TerminalActionsService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  function build(setup: (s: jasmine.SpyObj<TerminalActionsService>) => void): void {
    service = mockTerminalActionsService();
    setup(service);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [TerminalPeopleViewComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(),
        { provide: TerminalActionsService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar }],
    });
    fixture = TestBed.createComponent(TerminalPeopleViewComponent);
    fixture.componentRef.setInput('workspaceId', 'w-1');
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => TestBed.resetTestingModule());

  it('liste les interlocuteurs, leur rôle, le Radar et leurs attentes', () => {
    build(s => s.interlocuteurs.and.returnValue(of([
      person('i-1', 'Rémi Dupont', { role: 'Réseau', team: 'Infra', radarPersonId: 'r-1', radarName: 'Rémi D.',
        openCount: 11 }),
    ])));
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('Rémi Dupont');
    expect(text).toContain('11 attentes');
    expect(text).toContain('Réseau · Infra');
    expect(text).toContain('dans le Radar (Rémi D.)');
  });

  it('propose les fusions ; [Fusionner] garde le plus complet et prévient le panneau', () => {
    const s: InterlocuteurMergeSuggestion = {
      keep: person('i-1', 'Rémi Dupont'), merge: person('i-2', 'Rémi'), reason: 'PREFIX',
    };
    build(sv => {
      sv.interlocuteurs.and.returnValue(of([s.keep, s.merge]));
      sv.interlocuteurSuggestions.and.returnValue(of([s]));
      sv.mergeInterlocuteur.and.returnValue(of(s.keep));
    });
    expect(fixture.nativeElement.querySelector('.people-merge').textContent).toContain('un nom prolonge l’autre');
    const changed: void[] = [];
    component.changed.subscribe(() => changed.push(undefined));
    sv_merge();
    expect(service.mergeInterlocuteur).toHaveBeenCalledWith('w-1', 'i-2', 'i-1');
    expect(changed.length).toBe(1);

    function sv_merge(): void {
      component.merge(s);
      fixture.detectChanges();
    }
  });

  it('« deux personnes » : la proposition disparaît ; un échec la remet et le dit', () => {
    const s: InterlocuteurMergeSuggestion = {
      keep: person('i-1', 'Rémi Dupont'), merge: person('i-2', 'Rémi Martin'), reason: 'TYPO',
    };
    build(sv => {
      sv.interlocuteurSuggestions.and.returnValue(of([s]));
      sv.distinctInterlocuteurs.and.returnValue(throwError(() => new Error('boom')));
    });
    component.distinct(s);
    expect(component.suggestions().length).toBe(1);
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('modifier : rôle et équipe enregistrés', () => {
    const p = person('i-1', 'Rémi');
    build(sv => {
      sv.interlocuteurs.and.returnValue(of([p]));
      sv.updateInterlocuteur.and.returnValue(of({ ...p, role: 'Réseau' }));
    });
    component.startEdit(p);
    component.editRole = 'Réseau';
    component.save(p);
    expect(service.updateInterlocuteur).toHaveBeenCalledWith('w-1', 'i-1',
      { displayName: 'Rémi', role: 'Réseau', team: '' });
    expect(component.people()[0].role).toBe('Réseau');
  });
});
