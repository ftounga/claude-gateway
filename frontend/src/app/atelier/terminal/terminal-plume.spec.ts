import { ComponentFixture, TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent, plumeErrorMessage } from './atelier-terminal.component';

/**
 * **Plume** dans le terminal (F-193 / SF-193-02) : « ✨ Enrichir » remplace la phrase par la demande
 * structurée, à relire ; « Revenir à mon texte » restaure l'original ; un échec ne touche jamais au
 * brouillon ; rien ne part sans geste.
 */
describe('AtelierTerminalComponent — Plume (F-193 / SF-193-02)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
  let http: HttpTestingController;
  let drafts: string[];
  let sent: number;

  const URL = '/api/workspaces/ws-1/enrich-prompt';

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function button(): HTMLButtonElement {
    return el().querySelector('.terminal-plume-btn') as HTMLButtonElement;
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
    component.draftChange.subscribe((value) => {
      drafts.push(value);
      component.draft = value; // le parent est propriétaire du brouillon
    });
    component.send.subscribe(() => sent++);
  });

  afterEach(() => {
    http.match(() => true);
  });

  it('le bouton est toujours là, grisé quand la saisie est vide', () => {
    component.draft = '';
    fixture.detectChanges();
    expect(button()).toBeTruthy();
    expect(button().disabled).toBeTrue();

    component.draft = 'ajoute la pagination';
    fixture.detectChanges();
    expect(button().disabled).toBeFalse();
    expect(button().textContent).toContain('Enrichir');
  });

  it('LE CRITÈRE : un clic enrichit, la demande remplace la phrase, l’original et les « à confirmer » restent visibles', fakeAsync(() => {
    component.draft = 'ajoute la pagination';
    fixture.detectChanges();

    button().click();
    fixture.detectChanges();

    const req = http.expectOne(URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ text: 'ajoute la pagination' });
    expect(el().querySelector('app-terminal-plume')?.textContent).toContain('Plume lit le contexte');
    expect(button().disabled).toBeTrue();

    req.flush({
      enrichedPrompt: 'Objectif : Paginer\nContraintes :\n- Taille 50 (à confirmer)',
      toConfirm: ['Taille 50'],
      sources: ['Fil récent du sujet (2 messages)', 'STATE.md du sujet'],
    });
    fixture.detectChanges();
    flushMicrotasks();

    expect(drafts).toEqual(['Objectif : Paginer\nContraintes :\n- Taille 50 (à confirmer)']);
    expect(el().querySelector('textarea.terminal-field--plume')).toBeTruthy();
    const panel = el().querySelector('app-terminal-plume')?.textContent ?? '';
    expect(panel).toContain('Votre texte : « ajoute la pagination »');
    expect(panel).toContain('Taille 50');
    expect(panel).toContain('enrichi par Plume');
    expect(panel).toContain('STATE.md du sujet');
    expect(sent).toBe(0);
  }));

  it('« Revenir à mon texte » restaure exactement l’original et referme Plume', fakeAsync(() => {
    component.draft = '  ma phrase  ';
    fixture.detectChanges();
    button().click();
    http.expectOne(URL).flush({ enrichedPrompt: 'Objectif : X', toConfirm: [], sources: [] });
    fixture.detectChanges();
    flushMicrotasks();

    (el().querySelector('.plume__back') as HTMLButtonElement).click();
    fixture.detectChanges();
    flushMicrotasks();

    expect(drafts[drafts.length - 1]).toBe('  ma phrase  ');
    expect(component.plume()).toBeNull();
    expect(el().querySelector('textarea.terminal-field--plume')).toBeNull();
  }));

  it('un échec ne touche jamais au brouillon et le dit', () => {
    component.draft = 'fais X';
    fixture.detectChanges();
    button().click();
    http.expectOne(URL).flush({ error: 'Plume est désactivé sur cette installation.' },
      { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();

    expect(drafts).toEqual([]);
    expect(el().querySelector('app-terminal-plume [role="alert"]')?.textContent)
      .toContain('Plume est désactivé sur cette installation.');

    (el().querySelector('.plume__back') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(component.plume()).toBeNull();
  });

  it('envoyer après enrichissement émet l’envoi et referme le panneau ; Ctrl+Entrée envoie aussi', fakeAsync(() => {
    component.draft = 'fais X';
    fixture.detectChanges();
    button().click();
    http.expectOne(URL).flush({ enrichedPrompt: 'Objectif : X', toConfirm: [], sources: [] });
    fixture.detectChanges();
    flushMicrotasks();

    const area = el().querySelector('textarea.terminal-field--plume') as HTMLTextAreaElement;
    area.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(sent).toBe(0); // Entrée seule va à la ligne
    area.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true }));
    fixture.detectChanges();

    expect(sent).toBe(1);
    expect(component.plume()).toBeNull();
  }));

  it('aucun appel sans saisie, ni pendant un enrichissement en cours', () => {
    component.draft = '   ';
    component.enrichDraft();
    http.expectNone(URL);

    component.draft = 'x';
    component.enrichDraft();
    component.enrichDraft();
    expect(http.match(URL).length).toBe(1);
  });

  it('changer de terminal referme Plume, et une réponse tardive ne réécrit rien', () => {
    component.draft = 'x';
    component.enrichDraft();
    const req = http.expectOne(URL);
    component.projectId = 'ws-2';
    expect(component.plume()).toBeNull();
    req.flush({ enrichedPrompt: 'Objectif : Y', toConfirm: [], sources: [] });
    expect(drafts).toEqual([]);
  });

  it('F-202 : refermer le panneau garde la zone multiligne et le texte à l’identique', fakeAsync(() => {
    component.draft = 'pagine';
    fixture.detectChanges();
    button().click();
    const prompt = '## Objectif\nPaginer l’export.\n\n## Points à confirmer\n- Taille 50';
    http.expectOne(URL).flush({ enrichedPrompt: prompt, toConfirm: ['Taille 50'], sources: [] });
    fixture.detectChanges();
    flushMicrotasks();

    component.closePlume();
    fixture.detectChanges();

    expect(component.plume()).toBeNull();
    expect(component.multilineDraft()).toBeTrue();
    const area = el().querySelector('textarea.terminal-field--plume') as HTMLTextAreaElement;
    expect(area).toBeTruthy();
    expect(area.getAttribute('aria-label')).toBe('Demande à exécuter');
    expect(component.draft).toBe(prompt);
    expect(el().querySelector('input.terminal-field')).toBeNull();
  }));

  it('F-202 : un brouillon d’une ligne garde le champ d’origine ; un brouillon multiligne, la zone', () => {
    component.draft = 'une ligne';
    fixture.detectChanges();
    expect(component.multilineDraft()).toBeFalse();
    expect(el().querySelector('input.terminal-field')).toBeTruthy();

    component.draft = 'ligne 1\nligne 2';
    fixture.detectChanges();
    expect(component.multilineDraft()).toBeTrue();
    expect(el().querySelector('textarea.terminal-field--plume')).toBeTruthy();
  });

  it('F-202 : la troncature signalée par le serveur est relayée telle quelle', () => {
    const message = 'La demande enrichie dépassait la longueur prévue. Votre texte est intact : réessayez avec une demande plus ciblée.';
    expect(plumeErrorMessage(new HttpErrorResponse({ status: 502, error: { error: message } }))).toBe(message);
  });

  it('message d’erreur : celui du serveur, sinon une phrase rassurante', () => {
    expect(plumeErrorMessage(new HttpErrorResponse({ status: 400, error: { error: 'Trop long.' } }))).toBe('Trop long.');
    expect(plumeErrorMessage(new HttpErrorResponse({ status: 0 }))).toContain('Votre texte est intact');
    expect(plumeErrorMessage(new HttpErrorResponse({ status: 503 }))).toContain('indisponible');
  });
});
