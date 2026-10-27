import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { TerminalChantiersBoardComponent } from './terminal-chantiers-board.component';
import { UnblockerMark } from '../../core/models/client-board.models';
import { JourneyPlan, JourneyPlanStep, OpenChantier, SubjectJourney } from '../../core/models/journey.models';

const mark = (who: UnblockerMark['who'], detail: string, step: number | null = null): UnblockerMark => ({
  who, label: who === 'OUTIL' ? 'L\'outil peut avancer' : who === 'MOI' ? 'Ça attend votre décision'
    : 'On attend quelqu\'un', detail, step, waitingOn: who === 'AUTRUI' ? 'Gino' : null, since: null,
});

const step = (title: string, extra: Partial<JourneyPlanStep> = {}): JourneyPlanStep => ({
  title, risk: 'REVERSIBLE', riskLabel: 'modification réversible', verify: null, rollback: null, waitsOn: null,
  waitsOnStatus: null, status: 'A_FAIRE', evidence: null, changed: false, dependsOn: [], unlocked: true,
  blockedBy: [], inProgress: false, subSteps: [], ...extra,
});

const plan = (steps: JourneyPlanStep[]): JourneyPlan => ({
  version: 1, validatedVersion: 1, validatedAt: null, awaitingValidation: false, amendment: false,
  waitingInputs: 0, steps,
});

const chantier = (number: number, extra: Partial<OpenChantier> = {}): OpenChantier => ({
  number, title: `Titre ${number}`, openedAt: null, phase: 'EXECUTION', phaseLabel: 'Exécution', focus: false,
  planVersion: 1, validatedVersion: 1, awaitingValidation: false, diagnosisPending: false, closeProposed: false,
  executing: true, done: 1, total: 3, plan: null, ...extra,
});

const journey = (chantiers: OpenChantier[]): SubjectJourney => ({
  mode: 'GUIDE', phase: 'EXECUTION', phaseLabel: 'Exécution', phaseChangedAt: null, chantiers,
  chantier: { number: chantiers.find((c) => c.focus)?.number ?? 1, title: null, openedAt: null },
}) as SubjectJourney;

/** F-220 / SF-220-04 — la pastille « qui débloque » dans le tableau des chantiers d'un sujet. */
describe('F-220 / SF-220-04 — la pastille dans le sujet', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.match((r) => r.url.endsWith('/journey/autonomy'));
    http.verify();
  });

  const mount = (j: SubjectJourney) => {
    const fixture = TestBed.createComponent(TerminalChantiersBoardComponent);
    fixture.componentRef.setInput('journey', j);
    fixture.componentRef.setInput('workspaceId', 'w1');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  it('onglet, résumé et étapes portent la pastille ; une étape faite n\'en a pas', () => {
    const el = mount(journey([
      chantier(1, {
        focus: true,
        unblocker: mark('AUTRUI', 'Étape 2 : en attente de « cert » auprès de Gino (demandé).', 2),
        plan: plan([
          step('Lire', { status: 'VERIFIE', evidence: 'ok' }),
          step('Obtenir le certificat', { unblocker: mark('AUTRUI', 'Étape 2 : en attente.', 2) }),
          step('Remplacer', { unblocker: mark('AUTRUI', 'Étape 3 : attend l\'étape 2 — …', 2) }),
        ]),
      }),
      chantier(2, { unblocker: mark('OUTIL', 'Étape 1 : « Lire ».', 1) }),
    ]));

    const tabs = Array.from(el.querySelectorAll('.board__tab'));
    expect(tabs[0].querySelector('.pill')?.textContent).toContain('on attend');
    expect(tabs[1].querySelector('.pill')?.textContent).toContain('l\'outil avance');
    expect(tabs[1].querySelector('.pill mat-icon')?.textContent).toContain('bolt');

    const who = el.querySelector('.board__who') as HTMLElement;
    expect(who.textContent).toContain('On attend quelqu\'un');
    expect(who.textContent).toContain('auprès de Gino');

    const steps = Array.from(el.querySelectorAll('.board__step'));
    expect(steps[0].querySelector('.pill')).toBeNull();
    expect(steps[1].querySelector('.pill')?.textContent).toContain('on attend');
    expect(steps[1].querySelector('.pill')?.getAttribute('aria-label')).toContain('Étape 2 : en attente.');
  });

  it('sans champ « unblocker » (gateway antérieure), aucune pastille', () => {
    const el = mount(journey([
      chantier(1, { focus: true, plan: plan([step('Lire')]) }),
    ]));
    expect(el.querySelector('.pill')).toBeNull();
    expect(el.querySelector('.board__who')).toBeNull();
  });
});
