import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { AtelierExecStreamingItem } from '../atelier.types';
import { AtelierTerminalPage } from '../../core/models/atelier.models';
import { PagesService } from '../../core/services/pages.service';
import { pageBlock } from './page-block';

/**
 * **Le bloc « Page publiée » dans le terminal** (F-109 / SF-109-03) : admis dans TOUT terminal (amendement F-89),
 * et aucune `iframe` rendue ne porte un jeton de bac à sable interdit.
 */
describe('AtelierTerminalComponent — la page publiée (F-109 / SF-109-03)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  const page: AtelierTerminalPage = { pageId: 'p-1', title: 'Radar MFA', description: null, version: 1 };

  function turn(): AtelierExecStreamingItem {
    return { status: 'running', tokens: null, text: '', plan: [], blocks: [pageBlock('tu_1', page)] };
  }

  beforeEach(async () => {
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(of({
      id: 'p-1', title: 'Radar MFA', description: null, space: 'VIGIE', hostId: 'h', workspaceId: 'w',
      currentVersion: 1, createdAt: '', updatedAt: '', viewUrl: '/api/p/t1.a.b/',
    }));
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [provideRouter([]), { provide: PagesService, useValue: pages }],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    component.projectName = 'mon-projet';
    component.projectId = 'ws-1';
  });

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function expectNoForbiddenSandbox(): void {
    const frames = Array.from(host().querySelectorAll('iframe'));
    expect(frames.length).toBeGreaterThan(0);
    for (const frame of frames) {
      const sandbox = frame.getAttribute('sandbox') ?? '';
      expect(sandbox).toBe('allow-scripts allow-popups');
      expect(sandbox).not.toContain('allow-same-origin');
      expect(sandbox).not.toContain('allow-forms');
      expect(sandbox).not.toContain('allow-top-navigation');
    }
  }

  for (const teams of [false, true]) {
    it(`est rendu dans un terminal ${teams ? 'Teams' : 'de projet'} — au fil de l'eau`, () => {
      component.teamsTerminal = teams;
      component.streaming = turn();
      fixture.detectChanges();

      expect(host().querySelector('app-page-block')).not.toBeNull();
      expect(host().textContent).toContain('Page publiée — Radar MFA');
      expectNoForbiddenSandbox();
    });
  }

  it("est rendu après rechargement, depuis la transcription d'un message", () => {
    component.messages = [{
      id: 'm1', role: 'ASSISTANT', content: 'Voici la page.', createdAt: '2026-09-13T10:00:00Z',
      terminal: [pageBlock('tu_1', page)],
    } as never];
    fixture.detectChanges();

    expect(host().textContent).toContain('Page publiée — Radar MFA');
  });

  it('Ouvrir affiche le panneau à droite, Fermer le retire', () => {
    component.streaming = turn();
    fixture.detectChanges();

    (host().querySelector('.page-block__open') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(host().querySelector('app-page-panel')).not.toBeNull();
    expectNoForbiddenSandbox();

    (host().querySelector('.page-panel__close') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(host().querySelector('app-page-panel')).toBeNull();
  });
});
