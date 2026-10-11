import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierNextStep } from '../../core/models/atelier.models';
import { AtelierThreadItem } from '../atelier.types';

/**
 * **« Et maintenant ? »** dans le terminal (F-192 / SF-192-02) : les suites de la dernière réponse,
 * sous elle ; un choix remplit la saisie et n'envoie rien ; jamais en lecture seule, pendant un tour,
 * avec une question en attente, ni après un tour interrompu ou au plafond.
 */
describe('AtelierTerminalComponent — Et maintenant ? (F-192 / SF-192-02)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
  let http: HttpTestingController;
  let drafts: string[];
  let sent: number;

  const steps: AtelierNextStep[] = [
    { title: 'Vérifier l’export', why: 'Non vérifié', label: 'VERIFIER', request: 'Vérifie l’export CSV.' },
    { title: 'Ouvrir la PR', why: 'Tests verts', label: 'LIVRER', request: 'Ouvre la PR.' },
  ];

  function thread(last: Partial<AtelierThreadItem> = {}): AtelierThreadItem[] {
    return [
      { id: 'u-1', role: 'USER', content: 'Corrige', actions: [] },
      { id: 'a-1', role: 'ASSISTANT', content: 'Corrigé.', actions: [], nextSteps: steps, ...last },
    ];
  }

  function panel(): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector('app-terminal-next-steps');
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([])],
    });
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    component.projectId = 'ws-1';
    drafts = [];
    sent = 0;
    component.draftChange.subscribe((value) => drafts.push(value));
    component.send.subscribe(() => sent++);
  });

  afterEach(() => {
    http.match(() => true);
  });

  it('LE CRITÈRE : les suites de la dernière réponse apparaissent sous elle', () => {
    component.messages = thread();
    fixture.detectChanges();

    expect(component.nextStepsOfTurn()).toEqual(steps);
    expect(panel()?.textContent).toContain('Et maintenant ?');
    expect(panel()?.textContent).toContain('Ouvrir la PR');
  });

  it('un choix remplit la saisie et n’envoie rien', () => {
    component.messages = thread();
    fixture.detectChanges();

    (panel()?.querySelectorAll('.opt')[1] as HTMLButtonElement).click();

    expect(drafts).toEqual(['Ouvre la PR.']);
    expect(sent).toBe(0);
  });

  it('absent en lecture seule', () => {
    component.readOnly = true;
    component.messages = thread();
    fixture.detectChanges();
    expect(component.nextStepsOfTurn()).toEqual([]);
    expect(panel()).toBeNull();
  });

  it('absent pendant un tour', () => {
    component.messages = thread();
    component.submitting = true;
    expect(component.nextStepsOfTurn()).toEqual([]);
  });

  it('absent avec une question en attente', () => {
    component.messages = thread();
    component.pendingQuestion = { toolUseId: 't' } as never;
    expect(component.nextStepsOfTurn()).toEqual([]);
  });

  it('absent après un tour interrompu ou au plafond', () => {
    component.messages = thread({ interrupted: true });
    expect(component.nextStepsOfTurn()).toEqual([]);
    component.messages = thread({ budgetReached: true });
    expect(component.nextStepsOfTurn()).toEqual([]);
  });

  it('absent quand le dernier élément n’est pas une réponse de l’agent', () => {
    component.messages = [...thread(), { id: 'u-2', role: 'USER', content: 'suite', actions: [] }];
    expect(component.nextStepsOfTurn()).toEqual([]);
  });
});
