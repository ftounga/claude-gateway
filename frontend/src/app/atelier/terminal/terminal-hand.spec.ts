import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { TerminalHandComponent } from './terminal-hand.component';
import { handOf, journeyAwaitsUser } from './terminal-hand';
import { SubjectJourney } from '../../core/models/journey.models';

const guided = (extra: Partial<SubjectJourney>): SubjectJourney => ({
  mode: 'GUIDE', phase: 'PLAN', phaseLabel: 'Plan', phaseChangedAt: null, ...extra,
});
const plan = (awaitingValidation: boolean) => ({
  version: 1, validatedVersion: null, validatedAt: null, awaitingValidation, amendment: false, waitingInputs: 0,
  steps: [],
});

/** **À qui la main** (F-176 / SF-176-10, décision D7). */
describe('F-176 / SF-176-10 — à qui la main', () => {
  const base = { submitting: false, pendingConfirmation: null, pendingQuestion: null, journey: null };

  it('à vous par défaut', () => {
    expect(handOf(base)).toEqual({ state: 'user', label: 'À vous — écrivez ou cliquez une option', target: null });
  });

  it('l’agent travaille pendant un tour', () => {
    expect(handOf({ ...base, submitting: true }).state).toBe('working');
  });

  it('une autorisation ou une question en attente prime sur le tour (le tour est en pause)', () => {
    expect(handOf({ ...base, submitting: true, pendingConfirmation: {} })).toEqual(
      jasmine.objectContaining({ state: 'awaiting', target: 'ask' }));
    expect(handOf({ ...base, submitting: true, pendingQuestion: {} })).toEqual(
      jasmine.objectContaining({ state: 'awaiting', target: 'question' }));
  });

  it('le parcours qui attend un geste : plan à valider, diagnostic, clôture, proposition du guidé', () => {
    expect(handOf({ ...base, journey: guided({ plan: plan(true) }) })).toEqual(
      jasmine.objectContaining({ state: 'awaiting', label: 'L’agent attend votre validation', target: 'journey' }));
    expect(journeyAwaitsUser(guided({ plan: plan(false) }))).toBeFalse();
    expect(journeyAwaitsUser(guided({ phase: 'VERIFICATION', closeProposed: true }))).toBeTrue();
    expect(journeyAwaitsUser(guided({ phase: 'INVESTIGATION',
      diagnosis: { text: 'x', evidence: null, confidence: 'MOYENNE', pending: true } }))).toBeTrue();
    expect(journeyAwaitsUser({ mode: 'LIBRE', phase: null, phaseLabel: null, phaseChangedAt: null,
      guidedProposal: { reason: 'x', proposedAt: '2026-10-06T08:00:00Z' } })).toBeTrue();
    expect(journeyAwaitsUser({ mode: 'LIBRE', phase: 'CLOS', phaseLabel: 'Clos', phaseChangedAt: null,
      plan: plan(true) })).toBeFalse();
  });

  it('l’indicateur dit l’état et l’ancre ramène la carte', () => {
    TestBed.configureTestingModule({ providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(TerminalHandComponent);
    fixture.componentRef.setInput('hand', handOf(base));
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.textContent).toContain('À vous');
    expect(el.querySelector('.terminal-hand__anchor')).toBeNull();

    fixture.componentRef.setInput('hand', handOf({ ...base, journey: guided({ plan: plan(true) }) }));
    fixture.detectChanges();
    const revealed: string[] = [];
    fixture.componentInstance.reveal.subscribe(t => revealed.push(t));
    (el.querySelector('.terminal-hand__anchor') as HTMLButtonElement).click();
    expect(revealed).toEqual(['journey']);
    expect(el.querySelector('.terminal-hand')?.getAttribute('data-state')).toBe('awaiting');
  });
});
