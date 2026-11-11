import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import {
  AdminJarvisReplayComponent, parisTime, percent, seconds, verdictLabel, verdictTone,
} from './admin-jarvis-replay.component';
import { AdminJarvisReplayService, Replay, ReplayBilan, ReplaySession, ReplayTurn } from './admin-jarvis-replay.service';

const BILAN: ReplayBilan = {
  turns: 27, heard: 16, lost: 11, lostRate: 0.407, repeated: 3, slow: 15, firstSoundP50: 3_884, firstSoundP90: 5_932,
  wrongSubject: 1, shown: 0, voiciWithoutShow: 1, delegations: 5, silentDelegations: 0, streamFallbacks: 18,
  streamFallbackRate: 0.667, unmeasured: 3,
};

const SESSION: ReplaySession = { from: '2026-11-09T16:11:56Z', to: '2026-11-09T16:26:10Z', pages: 3, turns: 27, bilan: BILAN };

function turn(over: Partial<ReplayTurn> = {}): ReplayTurn {
  return {
    number: 1, turnId: null, pageId: 'p', correlation: 'ESTIMEE', at: '2026-11-09T16:24:00Z', listenMode: 'CONV_STREAM',
    heard: { chars: 85, rejected: null, streamText: 'montre-moi la galerie', finalText: 'Montre-moi la galerie vidéo.',
      userText: 'Montre-moi la galerie vidéo.', captureId: 'cap-1', captureSeconds: 5 },
    steps: { speechMs: 6684, endSilenceMs: 2033, transcriptMs: 718, sentMs: 719, firstTokenMs: 2734, firstSoundMs: 3118,
      doneMs: 4067 },
    brain: 'FAST', speculation: 'adoptee', delegationReason: null, emptyDiagnostic: null, saidChars: 38,
    reply: 'Voici la galerie des vidéos, Monsieur.', screenSubject: { id: 'w', name: 'waf' }, delegations: [],
    shows: [{ action: 'galerie', refused: true, reason: 'NOT_FOUND' }], streamFallbacks: [], streamError: false,
    measured: true,
    verdicts: [{ code: 'ENTENDU', detail: null }, { code: 'LENT', detail: 'premier son à 3,1 s' },
      { code: 'VOICI_SANS_AFFICHAGE', detail: 'refusé : galerie NOT_FOUND' }],
    events: ['+0.0 s listen_end', '+5.7 s show_refused galerie NOT_FOUND'],
    ...over,
  };
}

describe('AdminJarvisReplayComponent (F-255 / SF-255-03)', () => {
  let api: jasmine.SpyObj<AdminJarvisReplayService>;

  function create() {
    TestBed.configureTestingModule({
      imports: [AdminJarvisReplayComponent],
      providers: [provideNoopAnimations(), { provide: AdminJarvisReplayService, useValue: api }],
    });
    const fixture = TestBed.createComponent(AdminJarvisReplayComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    api = jasmine.createSpyObj<AdminJarvisReplayService>('AdminJarvisReplayService', ['sessions', 'replay', 'audio']);
  });

  it('liste les séances avec leur bilan, comparables en colonnes', () => {
    api.sessions.and.returnValue(of([SESSION]));
    const el: HTMLElement = create().nativeElement;
    const row = el.querySelector('tbody tr')!.textContent!;
    expect(row).toContain('27');
    expect(row).toContain('11');
    expect(row).toContain('41 %');
    expect(row).toContain('3,9 s');
  });

  it('rejoue une séance : tours, verdicts en français, ce qui a été dit et répondu, écran refusé', () => {
    api.sessions.and.returnValue(of([SESSION]));
    const replay: Replay = { from: SESSION.from, to: SESSION.to, pages: 3, turns: [turn()], bilan: BILAN,
      thresholds: { slowFirstSoundMs: 3000, repeatWindow: 5, sessionGapMinutes: 10 } };
    api.replay.and.returnValue(of(replay));
    const fixture = create();
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelector('tbody button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(api.replay).toHaveBeenCalledWith(SESSION.from, SESSION.to);
    const text = el.querySelector('.replay__session')!.textContent!;
    expect(text).toContain('« Montre-moi la galerie vidéo. »');
    expect(text).toContain('« Voici la galerie des vidéos, Monsieur. »');
    expect(text).toContain('« Voici » sans affichage');
    expect(text).toContain('galerie — refusé (NOT_FOUND)');
    expect(text).toContain('premier son 3,1 s');
    expect(el.querySelector('.replay__chip--bad')!.textContent!.trim()).toBe('« Voici » sans affichage');
    expect(el.querySelector('.replay__listen')).not.toBeNull();
  });

  it('un tour sans mesure, sans audio, perdu : le dit', () => {
    api.sessions.and.returnValue(of([SESSION]));
    api.replay.and.returnValue(of({ from: SESSION.from, to: SESSION.to, pages: 1, bilan: BILAN,
      thresholds: { slowFirstSoundMs: 3000, repeatWindow: 5, sessionGapMinutes: 10 },
      turns: [turn({ measured: false, reply: null, shows: [],
        heard: { chars: 0, rejected: 'empty', streamText: null, finalText: null, userText: null, captureId: null,
          captureSeconds: null },
        verdicts: [{ code: 'PERDU', detail: 'parole refusée : empty' }] })] }));
    const fixture = create();
    (fixture.nativeElement.querySelector('tbody button') as HTMLButtonElement).click();
    fixture.detectChanges();
    const text = fixture.nativeElement.querySelector('.replay__session').textContent as string;
    expect(text).toContain('rien retenu (empty)');
    expect(text).toContain('aucune mesure reçue');
    expect(fixture.nativeElement.querySelector('.replay__listen')).toBeNull();
  });

  it('aucune séance ; échec du chargement', () => {
    api.sessions.and.returnValue(of([]));
    expect(create().nativeElement.textContent).toContain('Aucune séance vocale');
    TestBed.resetTestingModule();
    api.sessions.and.returnValue(throwError(() => new Error('x')));
    expect(create().nativeElement.textContent).toContain('n\'ont pas pu être chargées');
  });

  it('libellés, tons et formats', () => {
    expect(verdictLabel('MAUVAIS_SUJET')).toBe('Mauvais sujet');
    expect(verdictTone('ENTENDU')).toBe('ok');
    expect(verdictTone('REPETE')).toBe('warn');
    expect(verdictTone('PERDU')).toBe('bad');
    expect(seconds(4632)).toBe('4,6 s');
    expect(seconds(null)).toBe('—');
    expect(percent(0.407)).toBe('41 %');
    expect(parisTime('2026-11-09T16:24:03Z')).toBe('17:24:03');
  });
});
