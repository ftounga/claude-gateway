import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';

/**
 * **« Envoyer à l'agent » depuis le lecteur du fil** (F-214 / SF-214-02) : le message des retours à la seconde
 * rejoint la saisie, sans être envoyé (patron Cap F-192 / Plume F-193) ; un brouillon en cours est gardé.
 */
describe('Retours à la seconde dans le terminal (F-214 / SF-214-02)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    fixture.componentInstance.projectName = 'mon-projet';
    fixture.componentInstance.projectId = 'w-1';
    fixture.detectChanges();
  });

  afterEach(() => TestBed.resetTestingModule());

  it('le message rejoint la saisie, rien n\'est envoyé ; le brouillon tapé reste dessous', () => {
    const drafts: string[] = [];
    let sent = 0;
    fixture.componentInstance.draftChange.subscribe((d: string) => drafts.push(d));
    fixture.componentInstance.send.subscribe(() => sent++);
    fixture.componentInstance.draft = 'et garde le logo';

    fixture.componentInstance.onVideoFeedback({
      message: 'Retours sur « Cours » v1 (v-1) :\n00:10 (image 304) — noir', workspaceId: 'w-1', videoId: 'v-1',
    });

    expect(drafts).toEqual(['Retours sur « Cours » v1 (v-1) :\n00:10 (image 304) — noir\n\net garde le logo']);
    expect(sent).toBe(0);
  });

  it('en lecture seule, rien ne bouge', () => {
    const drafts: string[] = [];
    fixture.componentInstance.draftChange.subscribe((d: string) => drafts.push(d));
    fixture.componentInstance.readOnly = true;

    fixture.componentInstance.onVideoFeedback({ message: 'x', workspaceId: 'w-1', videoId: 'v-1' });

    expect(drafts).toEqual([]);
  });
});
