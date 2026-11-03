import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent, leadingForeignCount } from './atelier-terminal.component';
import { AtelierThreadItem } from '../atelier.types';
import { toThreadItem } from '../thread-item';

const item = (id: string, content: string, chantier?: number | null): AtelierThreadItem => ({
  id, role: 'USER', content, actions: [], ...(chantier !== undefined ? { chantier } : {}),
});

/** F-246 / SF-246-02 — le fil d'un chantier ne montre que lui : le passé commun est replié. */
describe('F-246 / SF-246-02 — le passé commun replié', () => {
  it('leadingForeignCount : compte les messages de tête d\'autres fils, s\'arrête au premier du chantier', () => {
    const messages = [item('a', 'fil commun', null), item('b', 'du 9', 9), item('c', 'du 10', 10),
      item('d', 'commun après', null)];
    expect(leadingForeignCount(messages, 10)).toBe(2);
    expect(leadingForeignCount(messages, null)).withContext('fil commun : rien de replié').toBe(0);
    expect(leadingForeignCount([item('a', 'du 9', 9)], 10)).withContext('aucun message à lui : tout').toBe(1);
    expect(leadingForeignCount([item('x', 'optimiste'), item('b', 'du 9', 9)], 10))
      .withContext('fil inconnu : jamais replié').toBe(0);
  });

  it('toThreadItem reporte le fil quand la gateway le sert, rien sinon', () => {
    const base = { id: 'm', role: 'USER' as const, content: 'x', createdAt: '2026-11-03T18:00:00Z' };
    expect(toThreadItem({ ...base, chantier: 9 }).chantier).toBe(9);
    expect(toThreadItem({ ...base, chantier: null }).chantier).toBeNull();
    expect('chantier' in toThreadItem(base)).toBeFalse();
  });

  describe('dans le terminal', () => {
    let fixture: ComponentFixture<AtelierTerminalComponent>;
    let component: AtelierTerminalComponent;

    beforeEach(() => {
      TestBed.configureTestingModule({
        imports: [AtelierTerminalComponent],
        providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([])],
      });
      fixture = TestBed.createComponent(AtelierTerminalComponent);
      component = fixture.componentInstance;
    });

    afterEach(() => TestBed.inject(HttpTestingController).match(() => true));

    const fold = () => (fixture.nativeElement as HTMLElement).querySelector('.terminal-history-fold') as HTMLButtonElement | null;
    const shown = () => component.displayedMessages.map((m) => m.id);

    it('volet du chantier 10 : le passé du 9 est replié derrière « Contexte d\'avant le chantier 10 »', () => {
      component.laneChantier = 10;
      component.messages = [item('a', 'Question du 9', 9), item('b', 'Réponse du 9', 9), item('c', 'Demande du 10', 10)];
      fixture.detectChanges();

      expect(shown()).toEqual(['c']);
      expect(fold()!.textContent).toContain('Contexte d\'avant le chantier 10 (2 messages d\'autres fils)');

      fold()!.click();
      fixture.detectChanges();
      expect(shown()).toEqual(['a', 'b', 'c']);
      expect(fold()!.textContent).toContain('Masquer le contexte d\'avant le chantier 10');
    });

    it('un nouveau départ plus long l\'emporte : libellé d\'historique ordinaire', () => {
      component.laneChantier = 10;
      component.foldedTurns = 3;
      component.messages = [item('a', 'du 9', 9), item('b', 'du 10', 10), item('c', 'du 10', 10), item('d', 'du 10', 10)];
      fixture.detectChanges();

      expect(shown()).toEqual(['d']);
      expect(fold()!.textContent).toContain('Voir l\'historique (3 messages)');
    });

    it('fil commun : rien n\'est replié, comme avant', () => {
      component.messages = [item('a', 'du 9', 9), item('b', 'commun', null)];
      fixture.detectChanges();

      expect(shown()).toEqual(['a', 'b']);
      expect(fold()).toBeNull();
    });

    it('changer de chantier : le contexte du nouveau repart replié', () => {
      component.laneChantier = 10;
      component.messages = [item('a', 'du 9', 9), item('c', 'du 10', 10)];
      fixture.detectChanges();
      fold()!.click();
      expect(component.historyRevealed()).toBeTrue();

      component.projectId = 'ws-1';
      component.laneChantier = 11;

      expect(component.historyRevealed()).toBeFalse();
    });
  });
});
