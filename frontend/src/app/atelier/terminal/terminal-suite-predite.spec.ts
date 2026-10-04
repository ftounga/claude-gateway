import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierService } from '../../core/services/atelier.service';

/**
 * **La suite prédite, comme Claude Code** (F-144 / SF-144-02).
 *
 * <p>Ce qui compte : la suite n'est demandée qu'**à la fin d'un tour** ; elle s'affiche en texte
 * fantôme dans le champ **vide** ; Tab ou → la posent dans le champ, Échap l'efface ; **rien n'est
 * envoyé** sans geste ; à défaut, les puces SF-144-01 restent le repli.</p>
 */
describe('AtelierTerminalComponent — la suite prédite (F-144 / SF-144-02)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
  let http: HttpTestingController;
  let drafts: string[];
  let sent: number;

  const WORKSPACE = 'ws-1';
  const URL = `/api/workspaces/${WORKSPACE}/next-prompt`;

  function field(): HTMLInputElement {
    return (fixture.nativeElement as HTMLElement).querySelector('.terminal-field') as HTMLInputElement;
  }

  function key(name: string): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key: name, cancelable: true });
    field().dispatchEvent(event);
    fixture.detectChanges();
    return event;
  }

  function chips(): HTMLButtonElement[] {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.suggestions__chip'));
  }

  /** Un tour qui part, puis finit sur une réponse de l'agent. */
  function finishTurn(): void {
    component.submitting = true;
    component.messages = [
      { id: 'u-1', role: 'USER', content: 'Corrige la TVA' } as never,
      { id: 'a-1', role: 'ASSISTANT', content: 'J\'ai corrigé la TVA.', diffs: [{}] } as never,
    ];
    component.submitting = false;
    flushMicrotasks();
  }

  function answer(suggestion: string | null): void {
    http.expectOne((r) => r.url === URL && r.method === 'POST').flush({ suggestion, messageId: 'm-1' });
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([])],
    });
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    component.projectId = WORKSPACE;
    component.isNarrow.set(false);
    drafts = [];
    sent = 0;
    component.draftChange.subscribe((value) => {
      drafts.push(value);
      component.draft = value;
    });
    component.send.subscribe(() => sent++);
    fixture.detectChanges();
  });

  afterEach(() => {
    // Seule la suite prédite nous intéresse : les autres lectures de l'écran sont écartées.
    http.match((r) => r.url !== URL);
    http.verify();
  });

  it('service : POST /api/workspaces/{id}/next-prompt, sans corps', () => {
    TestBed.inject(AtelierService).nextPrompt('abc').subscribe();
    const request = http.expectOne('/api/workspaces/abc/next-prompt');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush({ suggestion: null, messageId: null });
  });

  it('LE CRITÈRE : à la fin du tour, la suite est en texte fantôme dans le champ vide', fakeAsync(() => {
    finishTurn();
    answer('Lance les tests du module paiement.');

    expect(field().placeholder).toBe('Lance les tests du module paiement.');
    expect(field().classList).toContain('terminal-field--ghost');
    // Une seule suggestion visible : la prédite remplace les puces SF-144-01.
    expect(chips()).toHaveSize(0);
  }));

  it('Tab l\'accepte : elle REMPLIT le champ et n\'envoie rien', fakeAsync(() => {
    finishTurn();
    answer('Lance les tests.');

    const event = key('Tab');

    expect(event.defaultPrevented).toBeTrue();
    expect(drafts).toEqual(['Lance les tests.']);
    expect(sent).toBe(0);
    expect(component.predicted()).toBeNull();
  }));

  it('→ l\'accepte aussi ; Échap l\'efface', fakeAsync(() => {
    finishTurn();
    answer('Lance les tests.');
    key('ArrowRight');
    expect(drafts).toEqual(['Lance les tests.']);

    component.draft = '';
    component.predicted.set('Autre suite.');
    fixture.detectChanges();
    key('Escape');
    expect(component.predicted()).toBeNull();
    expect(field().placeholder).toContain('Décrivez la tâche');
  }));

  it('Entrée sur champ vide n\'envoie jamais la suggestion', fakeAsync(() => {
    finishTurn();
    answer('Lance les tests.');

    component.submit();

    expect(sent).toBe(0);
    expect(drafts).toEqual([]);
  }));

  it('toute frappe l\'efface', fakeAsync(() => {
    finishTurn();
    answer('Lance les tests.');

    component.onDraftInput('a', field());

    expect(component.predicted()).toBeNull();
  }));

  it('rien de prédit (ou échec) : les puces SF-144-01 restent le repli', fakeAsync(() => {
    finishTurn();
    answer(null);

    expect(field().placeholder).toContain('Décrivez la tâche');
    expect(chips().length).toBeGreaterThan(0);
  }));

  it('échec réseau : aucun message, repli sur les puces', fakeAsync(() => {
    finishTurn();
    http.expectOne(URL).flush('boom', { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();

    expect(component.predicted()).toBeNull();
    expect(chips().length).toBeGreaterThan(0);
  }));

  it('vue étroite : une puce « Suggestion : … » qu\'un toucher place dans le champ', fakeAsync(() => {
    component.isNarrow.set(true);
    finishTurn();
    answer('Lance les tests.');

    expect(field().placeholder).not.toBe('Lance les tests.');
    expect(chips()).toHaveSize(1);
    expect(chips()[0].textContent).toContain('Suggestion : Lance les tests.');

    chips()[0].click();
    fixture.detectChanges();

    expect(drafts).toEqual(['Lance les tests.']);
    expect(sent).toBe(0);
  }));

  it('n\'est demandée qu\'à la fin d\'un tour : ni au chargement, ni en lecture seule, ni sans réponse', fakeAsync(() => {
    // Chargement d'un fil : aucun tour ne vient de finir.
    component.messages = [{ id: 'a-0', role: 'ASSISTANT', content: 'ancienne réponse' } as never];
    fixture.detectChanges();
    flushMicrotasks();
    http.expectNone(URL);

    // Dernier message = une demande (tour tombé sans réponse).
    component.submitting = true;
    component.messages = [{ id: 'u-0', role: 'USER', content: 'fais' } as never];
    component.submitting = false;
    flushMicrotasks();
    http.expectNone(URL);

    // Lecture seule.
    component.readOnly = true;
    finishTurn();
    http.expectNone(URL);
  }));

  it('un nouveau tour efface la suite de l\'ancien', fakeAsync(() => {
    finishTurn();
    answer('Lance les tests.');

    component.submitting = true;
    fixture.detectChanges();

    expect(component.predicted()).toBeNull();
  }));

  it('le menu de slash-commands garde la priorité de Tab', fakeAsync(() => {
    finishTurn();
    answer('Lance les tests.');
    component.draft = '/';
    fixture.detectChanges();

    component.onComposerKeydown(new KeyboardEvent('keydown', { key: 'Tab' }), field());

    expect(drafts).not.toContain('Lance les tests.');
  }));
});
