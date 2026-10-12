import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierThreadItem } from '../atelier.types';
import { chantierResolution, chantierResolvedMessage } from '../../core/models/journey.models';

const user = (id: string, content: string): AtelierThreadItem => ({ id, role: 'USER', content, actions: [] });
const MARK = chantierResolvedMessage(2);

/** F-196 / SF-196-03 — la résolution du chantier marquée dans le fil. */
describe('F-196 / SF-196-03 — séparateur « Chantier résolu »', () => {
  it('reconnaît la marque en première ligne, et le brouillon qui la suit', () => {
    expect(chantierResolution(MARK)).toEqual({ label: MARK, rest: '' });
    expect(chantierResolution(`${MARK}\n\net vérifie les logs`)).toEqual({ label: MARK, rest: 'et vérifie les logs' });
    expect(chantierResolution('✓ Chantier clos.')).toEqual({ label: '✓ Chantier clos.', rest: '' });
    expect(chantierResolution(chantierResolvedMessage(null))?.label).toContain('✓ Chantier résolu');
  });

  it('ne confond pas un message ordinaire', () => {
    expect(chantierResolution('Le ✓ Chantier 2 résolu ?')).toBeNull();
    expect(chantierResolution('✓ Chantier 2 résolution partielle')).toBeNull();
    expect(chantierResolution('✓ Plan v2 validé — exécution lancée.')).toBeNull();
    expect(chantierResolution(null)).toBeNull();
  });

  describe('dans le terminal', () => {
    let fixture: ComponentFixture<AtelierTerminalComponent>;

    beforeEach(() => {
      TestBed.configureTestingModule({
        imports: [AtelierTerminalComponent],
        providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([])],
      });
      fixture = TestBed.createComponent(AtelierTerminalComponent);
      fixture.componentInstance.projectId = 'ws-1';
    });

    afterEach(() => TestBed.inject(HttpTestingController).match(() => true));

    const root = (): HTMLElement => fixture.nativeElement as HTMLElement;

    it('rend la marque seule en séparateur, sans bulle « Votre question »', () => {
      fixture.componentInstance.messages = [user('u1', MARK)];
      fixture.detectChanges();
      const separator = root().querySelector('.terminal-resolution');
      expect(separator?.getAttribute('role')).toBe('separator');
      expect(separator?.getAttribute('aria-label')).toBe(MARK);
      expect(separator?.textContent).toContain('✓ Chantier 2 résolu — ce qui suit va au-delà du besoin de départ');
      expect(root().querySelector('.terminal-question')).toBeNull();
    });

    it('une marque suivie d’un brouillon : séparateur puis bulle du brouillon seul', () => {
      fixture.componentInstance.messages = [user('u1', `${MARK}\n\net vérifie les logs`)];
      fixture.detectChanges();
      expect(root().querySelector('.terminal-resolution')).not.toBeNull();
      const request = root().querySelector('.terminal-question .terminal-request');
      expect(request?.textContent?.trim()).toBe('et vérifie les logs');
    });

    it('un message ordinaire reste une bulle', () => {
      fixture.componentInstance.messages = [user('u1', 'corrige le WAF')];
      fixture.detectChanges();
      expect(root().querySelector('.terminal-resolution')).toBeNull();
      expect(root().querySelector('.terminal-question .terminal-request')?.textContent?.trim()).toBe('corrige le WAF');
    });
  });
});
