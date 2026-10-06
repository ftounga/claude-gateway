import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import {
  HANDOFF_PREVIEW_CHARS,
  HandoffCardComponent,
  HandoffOriginComponent,
  handoffBlock,
  handoffPreview,
} from './handoff-card.component';
import { AtelierService } from '../../core/services/atelier.service';
import { AtelierSubjectHandoff } from '../../core/models/atelier.models';

const handoff: AtelierSubjectHandoff = {
  workspaceId: 's1', name: 'data-platform', phrase: 'Reprends data-platform : lis le PLAN-ACTION.md',
};

/** La carte [Ouvrir le sujet] et la bande « Ouvert depuis… » (F-179 / SF-179-02). */
describe('HandoffCardComponent (F-179 / SF-179-02)', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HandoffCardComponent, HandoffOriginComponent, NoopAnimationsModule] });
  });

  it('range la passation dans un bloc du fil', () => {
    const block = handoffBlock('tu-1', handoff);
    expect(block.handoff).toEqual(handoff);
    expect(block.toolUseId).toBe('tu-1');
    expect(block.hasOutput).toBeFalse();
  });

  it("coupe l'aperçu d'une longue phrase et le dit", () => {
    expect(handoffPreview('court')).toBe('court');
    const long = 'x'.repeat(HANDOFF_PREVIEW_CHARS + 20);
    expect(handoffPreview(long).endsWith('…')).toBeTrue();
    expect(handoffPreview(long).length).toBe(HANDOFF_PREVIEW_CHARS + 1);
  });

  it('montre le sujet et la phrase ; [Ouvrir le sujet] émet la passation', () => {
    const fixture = TestBed.createComponent(HandoffCardComponent);
    fixture.componentRef.setInput('handoff', handoff);
    const opened: AtelierSubjectHandoff[] = [];
    fixture.componentInstance.open.subscribe((h) => opened.push(h));
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Sujet prêt : data-platform');
    expect(text).toContain('lis le PLAN-ACTION.md');
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    expect(opened).toEqual([handoff]);
  });

  it('en lecture seule (mosaïque), pas de bouton', () => {
    const fixture = TestBed.createComponent(HandoffCardComponent);
    fixture.componentRef.setInput('handoff', handoff);
    fixture.componentRef.setInput('readOnly', true);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });

  it('la bande dit d\'où vient le sujet ; [Revenir] émet', () => {
    const fixture = TestBed.createComponent(HandoffOriginComponent);
    fixture.componentRef.setInput('fromName', 'Terminal du poste');
    let back = 0;
    fixture.componentInstance.back.subscribe(() => back++);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Ouvert depuis Terminal du poste');
    (fixture.nativeElement.querySelector('button') as HTMLButtonElement).click();
    expect(back).toBe(1);
  });

  it("le flux relaie l'événement handoff, et ignore un événement sans sujet", () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [AtelierService, provideHttpClient(), provideHttpClientTesting()] });
    const atelier = TestBed.inject(AtelierService);
    const dispatch = (atelier as unknown as {
      dispatchSseEvent: (raw: string, handlers: object) => void;
    }).dispatchSseEvent.bind(atelier);
    const got: unknown[] = [];
    const handlers = {
      onAction: () => undefined, onText: () => undefined, onDone: () => undefined, onError: () => undefined,
      onHandoff: (event: unknown) => got.push(event),
    };

    dispatch('event: handoff\ndata: {"toolUseId":"tu_1","handoff":{"workspaceId":"s1","name":"dp","phrase":"go"}}',
      handlers);
    dispatch('event: handoff\ndata: {"toolUseId":"tu_2"}', handlers);

    expect(got).toEqual([{ toolUseId: 'tu_1', handoff: { workspaceId: 's1', name: 'dp', phrase: 'go' } }]);
  });
});
