import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
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

  it('deux coches remplissent la saisie de leur enchaînement, sans rien envoyer', () => {
    component.messages = thread();
    fixture.detectChanges();

    (panel()?.querySelectorAll('.opt')[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    (panel()?.querySelectorAll('.opt')[1] as HTMLButtonElement).click();

    expect(drafts[drafts.length - 1]).toMatch(/^Traite ces suites — en parallèle celles qui sont indépendantes : 1\) .+ ; 2\) Ouvre la PR\.$/);
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

  // F-192 / SF-192-03 — la suite prédite de F-144 est retirée.
  it('la fin d’un tour n’appelle plus aucune suite prédite, et la saisie garde son texte fixe', fakeAsync(() => {
    fixture.detectChanges();
    component.submitting = true;
    component.messages = thread();
    component.submitting = false;
    flushMicrotasks();
    fixture.detectChanges();

    expect(http.match((r) => r.url.includes('next-prompt')).length).toBe(0);
    const field = (fixture.nativeElement as HTMLElement).querySelector('.terminal-field') as HTMLInputElement;
    expect(field.placeholder).toBe('Décrivez la tâche à exécuter (@ pour un fichier)…');
    expect(field.classList).not.toContain('terminal-field--ghost');
    expect((fixture.nativeElement as HTMLElement).querySelector('app-turn-suggestions')).toBeNull();
  }));

  it('F-209 / SF-209-02 : « Lancer » envoie UNE demande visible par le chemin d’envoi', () => {
    const withPrereq: AtelierNextStep[] = [{
      title: 'Basculer', why: 'étape 6', label: 'CONTINUER', request: 'Bascule.', scope: 'CHANTIER',
      stepIndex: 6, chantier: 4, column: 'CHANTIER',
      prerequisites: [{ step: 4, title: 'Migrer', risk: 'REVERSIBLE', level: 1 }],
    }];
    component.messages = thread({ nextSteps: withPrereq, nextStepsChantier: { number: 4, done: 1, total: 6 } });
    fixture.detectChanges();
    (panel()!.querySelector('.pre__launch') as HTMLButtonElement).click();
    expect(drafts).toEqual(['Chantier 4 — d’abord les prérequis, dans cet ordre — 1) étape 4 « Migrer ». Puis : Bascule.']);
    expect(sent).toBe(1);
  });
});
