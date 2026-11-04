import { TestBed, discardPeriodicTasks, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, of, throwError } from 'rxjs';

import { BUDGET_SOFT_NOTICE, JARVIS_USER_AGENT, JarvisComponent, companionOffer, euros } from './jarvis.component';
import { JarvisSceneBus } from './scenes/jarvis-scene-bus.service';
import { testUniverse } from './scenes/jarvis-universe.fixture';
import { NotificationCenterService } from '../core/services/notification-center.service';
import { LiveTerminalService } from '../core/services/live-terminal.service';
import { signal as ngSignal } from '@angular/core';

/** F-234 / SF-234-10 — les relevés existants, vus par leurs signaux seulement. */
const notificationCenter = { items: ngSignal<{ id: string; event: string; workspaceId: string | null }[]>([]) };
const liveTerminals = { registry: ngSignal<{ terminals: { workspaceId: string }[] } | null>(null) };
import { AtelierMessage, AtelierStreamHandlers, WorkspaceSummary } from '../core/models/atelier.models';
import { trackOf } from './stage3d/hud/jarvis-replay';
import { COCKPIT_CHANNEL, CockpitLeader, LinkChannel } from './stage3d/hud/jarvis-cockpit-link';
import { AtelierService } from '../core/services/atelier.service';
import { DictationService } from '../core/services/dictation.service';
import { JarvisVoiceService } from '../core/services/jarvis-voice.service';
import { JarvisService } from '../core/services/jarvis.service';
import { ASK_SUBJECT, FastBrainHandlers, FastBrainOutcome, JarvisFastBrainClient, PortraitPin } from './jarvis-fast-brain';
import { VeilleService } from '../core/services/veille.service';
import { JarvisStreamOpener, StreamHandlers } from './jarvis-stream-listener';
import { WakeEvent } from './jarvis-wake';
import { AuthService } from '../core/services/auth.service';
import { JarvisVadService, VadHandlers } from './jarvis-barge-in';
import { FORMULAS, FORMULA_CLIPS, tutoie } from './jarvis-phrases';
import { JARVIS_3D_LOADER, JarvisEngineFactory } from './stage3d/jarvis-engine.contract';
import { INTERVIEW_END_MS, INTERVIEW_QUESTIONS, JarvisInterviewClient } from './jarvis-interview';
import { JarvisPortraitClient } from './jarvis-portrait';
import { JarvisMailClient, MailBrief } from './jarvis-mail';
import { JarvisCalendarClient } from './jarvis-calendar';
import { JARVIS_RENDER_PROBE, RenderProbe } from './stage3d/jarvis-render-capability';
import { FakeEngine, NO_WEBGL_PROBE, PC_PROBE, fakeEngineFactory } from './stage3d/jarvis-stage3d.testing';

const FILLERS = FORMULAS.filter((f) => f.kind === 'filler').map((f) => f.text);

const NO_COMPANION = {
  available: false, versionCode: null, versionName: null, sha256: null, signingCertSha256: null, sizeBytes: null,
};
const COMPANION_V4 = {
  available: true, versionCode: 4, versionName: '0.4.0', sha256: 'a'.repeat(64), signingCertSha256: 'b'.repeat(64),
  sizeBytes: 1200000,
};

function subject(id: string, name: string): WorkspaceSummary {
  return { id, name, createdAt: '2026-11-01T08:00:00Z', source: 'LOCAL', gitRepo: null } as WorkspaceSummary;
}

let fastDone: ((outcome: FastBrainOutcome) => void) | null = null;

/** **L'écran Jarvis** (F-231 / SF-231-02) : le même cerveau, à la voix. */
describe('L’écran Jarvis (F-231 / SF-231-02)', () => {
  let atelier: jasmine.SpyObj<AtelierService>;
  let dictation: jasmine.SpyObj<DictationService>;
  let voice: jasmine.SpyObj<JarvisVoiceService>;
  let handlers: AtelierStreamHandlers | null;
  let jarvisApi: jasmine.SpyObj<JarvisService>;
  let veilles: jasmine.SpyObj<VeilleService>;
  let userAgent: string;
  let opener: jasmine.SpyObj<JarvisStreamOpener>;
  let vadService: jasmine.SpyObj<JarvisVadService>;
  let vad: VadHandlers | null;
  let vadStop: jasmine.Spy;
  let fastBrain: jasmine.SpyObj<JarvisFastBrainClient>;
  let interviewClient: jasmine.SpyObj<JarvisInterviewClient>;
  let portraitClient: jasmine.SpyObj<JarvisPortraitClient>;
  let mailClient: jasmine.SpyObj<JarvisMailClient>;
  let fast: FastBrainHandlers | null;
  let renderProbe: RenderProbe;
  let loader3d: jasmine.Spy<() => Promise<JarvisEngineFactory>>;

  beforeEach(() => {
    renderProbe = NO_WEBGL_PROBE;
    loader3d = jasmine.createSpy('loader3d').and.callFake(() => Promise.resolve(fakeEngineFactory([])));
    try {
      localStorage.removeItem('cg.jarvis.view');
      localStorage.removeItem('cg.jarvis.subject');
    } catch {
      // ignoré
    }
    handlers = null;
    fast = null;
    // F-235 / SF-235-05 — par défaut, le cerveau rapide est éteint (503) : l'écran garde le mode actuel.
    fastBrain = jasmine.createSpyObj<JarvisFastBrainClient>('JarvisFastBrainClient', ['warm', 'converse']);
    // F-241 / SF-241-02 — l'entretien : par défaut, aucune proposition (la cadence est tenue par la passerelle).
    interviewClient = jasmine.createSpyObj<JarvisInterviewClient>('JarvisInterviewClient', ['offer', 'offered', 'draft']);
    interviewClient.offer.and.returnValue(of({ offer: false, offers: 0 }));
    interviewClient.offered.and.returnValue(of({ offer: false, offers: 1 }));
    portraitClient = jasmine.createSpyObj<JarvisPortraitClient>('JarvisPortraitClient',
      ['get', 'update', 'eraseAll', 'addSouvenir', 'patchSouvenir', 'deleteSouvenir', 'integrate']);
    // F-241 / SF-241-03 — le portrait lu à l'ouverture (termes « À ne pas aborder » du filet des lignes 🎙).
    portraitClient.get.and.returnValue(of({ exists: false, enabled: true, version: 0, sections: [], souvenirs: [],
      size: { chars: 0, tokens: 0, maxTokens: 1500, maxChars: 6000 }, lastInterviewAt: null, interviewOffers: 0,
      blockedTerms: [] }));
    // F-242 / SF-242-04 — le courrier : par défaut, rien de neuf.
    mailClient = jasmine.createSpyObj<JarvisMailClient>('JarvisMailClient', ['list', 'link', 'test', 'revoke', 'brief', 'body']);
    mailClient.brief.and.returnValue(of({ text: '', items: [] } as MailBrief));
    fastBrain.warm.and.resolveTo(false);
    fastBrain.converse.and.callFake((_body, h) => {
      fast = h;
      return new Promise<FastBrainOutcome>((resolve) => fastDone = resolve);
    });
    atelier = jasmine.createSpyObj<AtelierService>('AtelierService',
      ['listWorkspaces', 'streamChat', 'interruptChat', 'confirmChatToolUse']);
    atelier.interruptChat.and.returnValue(of(undefined));
    atelier.confirmChatToolUse.and.returnValue(of(undefined));
    atelier.listWorkspaces.and.returnValue(of([subject('w-1', 'Connecteur AGENOR'), subject('w-2', 'CAGIP')]));
    atelier.streamChat.and.callFake((_id: string, _text: string, h: AtelierStreamHandlers) => {
      handlers = h;
      return Promise.resolve();
    });
    dictation = jasmine.createSpyObj<DictationService>('DictationService',
      ['start', 'stop', 'stopRecording', 'cancel', 'transcribe'], { supported: true, stream: null });
    dictation.start.and.resolveTo();
    // F-239 / SF-239-04 — Jarvis arrête l'enregistrement sans le transcrire, puis le retranscrit lui-même ; sans
    // retranscription (503 par défaut ici), il retombe sur la dictée, dont `stop` porte le texte des tests.
    dictation.stop.and.resolveTo('');
    dictation.stopRecording.and.callFake(() => Promise.resolve(new Blob(['enregistrement'], { type: 'audio/webm' })));
    dictation.transcribe.and.callFake(() => dictation.stop());
    voice = jasmine.createSpyObj<JarvisVoiceService>('JarvisVoiceService',
      ['speak', 'stop', 'unlock', 'level', 'preloadClips', 'playClip', 'hasClip', 'enqueue', 'idle', 'status',
        'duck', 'interrupt', 'resetHeard', 'audioAllowed']);
    voice.interrupt.and.returnValue(null);
    // SF-233-12 — par défaut le navigateur bloque le son : l'invite plein écran attend un toucher.
    voice.audioAllowed.and.resolveTo(false);
    voice.status.and.returnValue(of({
      enabled: true, voice: 'onyx', monthlyBudgetEur: 100, monthSpentEur: 0, budgetReached: false, provider: 'openai',
    }));
    voice.idle.and.resolveTo();
    voice.speak.and.resolveTo();
    voice.preloadClips.and.resolveTo();
    voice.playClip.and.resolveTo(false);
    voice.hasClip.and.returnValue(false);
    voice.level.and.returnValue(0);
    jarvisApi = jasmine.createSpyObj<JarvisService>('JarvisService', ['usage', 'companion', 'companions', 'timings', 'sttSession', 'closeSttSession', 'preferences', 'setHumour', 'scenes', 'render', 'utterance', 'setCaptureAudio', 'capture', 'costs', 'correctCapture']);
    jarvisApi.sttSession.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    jarvisApi.utterance.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    jarvisApi.costs.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    jarvisApi.preferences.and.returnValue(of({ humour: 'LIGHT' as const }));
    jarvisApi.setHumour.and.callFake((humour) => of({ humour }));
    opener = jasmine.createSpyObj<JarvisStreamOpener>('JarvisStreamOpener', ['open']);
    vad = null;
    vadStop = jasmine.createSpy('vadStop');
    vadService = jasmine.createSpyObj<JarvisVadService>('JarvisVadService', ['start']);
    vadService.start.and.callFake((h: VadHandlers) => {
      vad = h;
      return Promise.resolve({ stop: vadStop });
    });
    jarvisApi.companions.and.returnValue(of([]));
    jarvisApi.companion.and.returnValue(of(NO_COMPANION));
    userAgent = 'Mozilla/5.0 (X11; Linux x86_64) Chrome/130';
    veilles = jasmine.createSpyObj<VeilleService>('VeilleService', ['alert', 'openAlert']);
    veilles.openAlert.and.returnValue(of(undefined));
    configure({ sujet: 'w-2' });
  });

  function configure(params: Record<string, string>): void {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [JarvisComponent],
      providers: [
        provideNoopAnimations(), provideRouter([]),
        { provide: AtelierService, useValue: atelier },
        { provide: DictationService, useValue: dictation },
        { provide: JarvisVoiceService, useValue: voice },
        { provide: JarvisService, useValue: jarvisApi },
        { provide: VeilleService, useValue: veilles },
        { provide: JarvisStreamOpener, useValue: opener },
        { provide: JarvisVadService, useValue: vadService },
        { provide: JarvisFastBrainClient, useValue: fastBrain },
        { provide: JarvisInterviewClient, useValue: interviewClient },
        { provide: JarvisPortraitClient, useValue: portraitClient },
        { provide: JarvisMailClient, useValue: mailClient },
        { provide: JARVIS_USER_AGENT, useFactory: () => userAgent },
        { provide: NotificationCenterService, useValue: notificationCenter },
        { provide: LiveTerminalService, useValue: liveTerminals },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(params) } } },
        // F-236 / SF-236-01 — Karma n'a pas de GPU fiable : l'écran est testé dans son rendu 2D (le repli).
        { provide: JARVIS_RENDER_PROBE, useValue: () => renderProbe },
        { provide: JARVIS_3D_LOADER, useValue: loader3d },
      ],
    });
  }

  it('F-236 / SF-236-01 — sans WebGL2 : l’écran 2D d’aujourd’hui, aucun canevas, aucun bouton « Vue », morceau 3D jamais demandé', fakeAsync(() => {
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-jarvis-stage-3d')).not.toBeNull();
    expect(el.querySelector('app-jarvis-stage-3d canvas')).toBeNull();
    expect(el.querySelector('.jv__view')).toBeNull();
    expect(el.querySelector('app-jarvis-constellation')).not.toBeNull();
    expect(loader3d).not.toHaveBeenCalled();
    expect(el.style.getPropertyValue('--cg-holo')).toBe('#E07B39');
    fixture.componentInstance.ngOnDestroy();
  }));

  it('F-236 / SF-236-16 — « Montrer » local au toucher d’un chiffre : sans tour, retouché ⇒ effacé, tour ⇒ effacé', fakeAsync(() => {
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const jarvis = fixture.componentInstance;
    TestBed.inject(JarvisSceneBus).setUniverse(testUniverse());
    jarvis.showLocal('chantiers-bloques');
    expect(jarvis.stageSignals().showFocus?.filter).toBe('chantiers-bloques');
    expect(jarvis.stageSignals().shown).toEqual(['s-forge#3']);
    expect(jarvis.localShowFilter()).toBe('chantiers-bloques');
    expect(atelier.streamChat).not.toHaveBeenCalled();
    jarvis.showLocal('chantiers-bloques');
    expect(jarvis.stageSignals().showFocus).toBeNull();
    jarvis.showLocal('connectes');
    expect(jarvis.stageSignals().shown).toEqual(['h-cagip']);
    jarvis.ngOnDestroy();
  }));

  it('F-236 / SF-236-22 — replay : l’image du lecteur privé remplace celle du bus, jamais alimenté ; un tour vivant l’arrête', fakeAsync(() => {
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const jarvis = fixture.componentInstance;
    const bus = TestBed.inject(JarvisSceneBus);
    bus.setUniverse(testUniverse());
    const before = bus.state();
    const feeds = ['action', 'output', 'text', 'done', 'turnStarted', 'plan'] as const;
    const spies = feeds.map((m) => spyOn(bus, m).and.callThrough());
    const messages = [
      { id: 'u1', role: 'USER', createdAt: '2026-11-02T08:10:00Z', content: 'Bascule' },
      { id: 'a1', role: 'ASSISTANT', createdAt: '2026-11-02T08:11:00Z', content: 'Fait.', terminal: {
        blocks: [{ tool: 'bash', command: 'kubectl get pods', toolUseId: null, output: '', hasOutput: false,
          error: false }], omittedBlocks: 0, inputTokens: 0, outputTokens: 0, activeSeconds: 0 } },
    ] as unknown as AtelierMessage[];
    const track = trackOf(messages, 'u1', testUniverse(), null)!;
    const replay = jarvis.replayView()!;
    replay.play([track]);
    expect(jarvis.stageSignals().scene?.id).toBe('S3');
    expect(jarvis.stageSignals().turnActive).toBeTrue();
    expect(jarvis.stageSignals().pulses).toBe(1);
    tick(1000);
    for (const spy of spies) {
      expect(spy).not.toHaveBeenCalled();
    }
    expect(bus.state()).toBe(before);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.rp')?.textContent).toContain('REPLAY');
    replay.stop();
    expect(jarvis.stageSignals().scene).toBeNull();
    jarvis.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-236 / SF-236-23 — ?presentation=1 : ni nom de personne, ni sortie, ni détail dans le DOM ni le plan ; Échap quitte', fakeAsync(() => {
    configure({ presentation: '1' });
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const jarvis = fixture.componentInstance;
    const bus = TestBed.inject(JarvisSceneBus);
    bus.setUniverse(testUniverse());
    bus.turnStarted();
    bus.action({ type: 'bash', path: 'kubectl get pods -n forge --token secret' });
    bus.output('forge-api Running\nZahi Ben a validé');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(jarvis.presentation()).toBeTrue();
    expect(el.querySelector('main')?.classList).toContain('jv--presentation');
    expect(el.querySelector('.jv__presenting')?.textContent).toContain('Échap');
    expect(el.querySelector('app-jarvis-scene')).toBeNull();
    expect(el.querySelector('.jv__exchange')).toBeNull();
    expect(el.querySelector('app-jarvis-replay')).toBeNull();
    for (const word of ['kubectl', 'Zahi', 'forge-api']) {
      expect(el.textContent).withContext(word).not.toContain(word);
    }
    const signals = jarvis.stageSignals();
    expect(signals.scene?.detail).toBeNull();
    expect(signals.scene?.data?.lines).toBeUndefined();
    expect(signals.admin).toBeFalse();
    expect(JSON.stringify(signals.universe)).not.toContain('Zahi');
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(jarvis.presentation()).toBeFalse();
    expect(jarvis.stageSignals().scene?.detail).toContain('kubectl');
    jarvis.ngOnDestroy();
    fixture.destroy();
    discardPeriodicTasks();
  }));

  it('F-242 / SF-242-04 — le bref à l’ouverture met le premier courriel en avant ; rien en présentation', fakeAsync(() => {
    mailClient.brief.and.returnValue(of({ text: 'Un mail à traiter : Helios demande le devis signé.',
      items: [{ id: '11111111-1111-1111-1111-111111111111', from: 'devis@helios.example', fromName: 'Helios',
        subject: 'Le devis', reason: 'Helios demande le devis signé' }] }));
    configure({});
    let fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    fixture.detectChanges();
    expect(mailClient.brief).toHaveBeenCalledWith(false);
    expect(fixture.componentInstance.mailCard()?.mode).toBe('highlight');
    expect((fixture.nativeElement as HTMLElement).querySelector('app-jarvis-mail-card')?.textContent).toContain('Helios');
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
    discardPeriodicTasks();

    mailClient.brief.calls.reset();
    configure({ presentation: '1' });
    fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    expect(mailClient.brief).not.toHaveBeenCalled();
    fixture.componentInstance.mailCard.set({ mode: 'capture', id: 'x', from: 'a@a', fromName: '', subject: 's',
      receivedAt: null, account: null, text: 'secret', truncated: false });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('app-jarvis-mail-card')).toBeNull();
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
    discardPeriodicTasks();
  }));

  it('F-243 / SF-243-02 — afficher(agenda:…) ouvre la vue d’agenda ; jamais en présentation', fakeAsync(() => {
    const calendar = jasmine.createSpyObj<JarvisCalendarClient>('JarvisCalendarClient', ['events']);
    calendar.events.and.returnValue(of({ from: '', to: '', items: [], updatedAt: null, feeds: 1 }));
    configure({});
    TestBed.overrideProvider(JarvisCalendarClient, { useValue: calendar });
    let fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    let ok = false;
    void fixture.componentInstance.applyShow({ action: 'piece', pieces: ['agenda:jour:2026-11-04'], reason: 'demande' })
      .then((r) => ok = r);
    flushMicrotasks();
    fixture.detectChanges();
    expect(ok).toBeTrue();
    expect(fixture.componentInstance.agendaView()).toEqual({ mode: 'jour', date: '2026-11-04' });
    expect((fixture.nativeElement as HTMLElement).querySelector('app-jarvis-agenda')).not.toBeNull();
    expect(calendar.events).toHaveBeenCalled();
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
    discardPeriodicTasks();

    configure({ presentation: '1' });
    TestBed.overrideProvider(JarvisCalendarClient, { useValue: calendar });
    fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    void fixture.componentInstance.applyShow({ action: 'piece', pieces: ['agenda:jour:2026-11-04'], reason: 'demande' })
      .then((r) => ok = r);
    flushMicrotasks();
    fixture.detectChanges();
    expect(ok).toBeFalse();
    expect((fixture.nativeElement as HTMLElement).querySelector('app-jarvis-agenda')).toBeNull();
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
    discardPeriodicTasks();
  }));

  it('F-242 / SF-242-04 — afficher(courriel:…) ouvre la visionneuse avec le corps relu, sans passer par la galerie', fakeAsync(() => {
    mailClient.body.and.returnValue(of({ item: { id: '22222222-2222-2222-2222-222222222222', accountId: 'a',
      account: 'Pro', from: 'devis@helios.example', fromName: 'Helios', subject: 'Le devis', snippet: '',
      receivedAt: '2026-11-03T08:00:00Z', triage: 'A_TRAITER', triageReason: null, hasCalendar: false },
      text: 'Bonjour, voici le devis.', truncated: false }));
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    let ok = false;
    void fixture.componentInstance.applyShow({ action: 'piece', pieces: ['courriel:22222222-2222-2222-2222-222222222222'],
      reason: 'demande' }).then((r) => ok = r);
    flushMicrotasks();
    fixture.detectChanges();
    expect(ok).toBeTrue();
    expect(mailClient.body).toHaveBeenCalledWith('22222222-2222-2222-2222-222222222222');
    expect(fixture.componentInstance.mailCard()?.mode).toBe('capture');
    expect((fixture.nativeElement as HTMLElement).querySelector('.jmc__body')?.textContent).toContain('voici le devis');
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
    discardPeriodicTasks();
  }));

  it('F-242 / SF-242-01 — « Mes boîtes » : bouton dans les réglages ; jamais en présentation, même ouvert', fakeAsync(() => {
    configure({});
    let fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    let el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jv__mail-open')?.textContent).toContain('Mes boîtes');
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();

    configure({ presentation: '1' });
    fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jv__mail-open')).toBeNull();
    fixture.componentInstance.mailOpen.set(true);
    fixture.detectChanges();
    expect(el.querySelector('app-jarvis-mailboxes')).toBeNull();
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-243 / SF-243-01 — « Mes agendas » : bouton dans les réglages ; jamais en présentation, même ouvert', fakeAsync(() => {
    configure({});
    let fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    let el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jv__calendar-open')?.textContent).toContain('Mes agendas');
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();

    configure({ presentation: '1' });
    fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jv__calendar-open')).toBeNull();
    fixture.componentInstance.calendarOpen.set(true);
    fixture.detectChanges();
    expect(el.querySelector('app-jarvis-calendars')).toBeNull();
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-245 / SF-245-04 — « Fil du jour » : bouton dans les réglages, replié ; jamais en présentation, même ouvert', fakeAsync(() => {
    configure({});
    let fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    let el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jv__thread-open')?.textContent).toContain('Fil du jour');
    expect(el.querySelector('app-jarvis-thread-panel')).toBeNull();
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();

    configure({ presentation: '1' });
    fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jv__thread-open')).toBeNull();
    fixture.componentInstance.threadOpen.set(true);
    fixture.detectChanges();
    expect(el.querySelector('app-jarvis-thread-panel')).toBeNull();
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-241 / SF-241-01 — « Mon portrait » : bouton dans les réglages ; jamais en présentation, même ouvert', fakeAsync(() => {
    configure({});
    let fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    let el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jv__portrait-open')?.textContent).toContain('Mon portrait');
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();

    configure({ presentation: '1' });
    fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jv__portrait-open')).toBeNull();
    fixture.componentInstance.portraitOpen.set(true);
    fixture.detectChanges();
    expect(el.querySelector('app-jarvis-portrait')).toBeNull();
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-241 / SF-241-02 — l’entretien : proposé (pas en présentation), « oui » lance les questions, réponses jamais envoyées au cerveau, brouillon à relire', fakeAsync(() => {
    interviewClient.offer.and.returnValue(of({ offer: true, offers: 0 }));
    interviewClient.draft.and.returnValue(of({ sections: { QUI: 'Chef de projet' }, souvenirs: [] }));
    configure({ presentation: '1' });
    let fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    expect(fixture.componentInstance.interviewOffer()).toBeFalse();
    expect(interviewClient.offer).not.toHaveBeenCalled();
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();

    configure({});
    fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const jarvis = fixture.componentInstance;
    fixture.detectChanges();
    expect(jarvis.interviewOffer()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).querySelector('.jv__interview-offer')).not.toBeNull();
    const spoken: string[] = [];
    spyOn(jarvis, 'say').and.callFake(async (phrase: string) => { spoken.push(phrase); });
    void jarvis.handle('oui');
    flushMicrotasks();
    expect(jarvis.interviewing()).toBeTrue();
    expect(spoken).toContain('Comment souhaitez-vous que je vous appelle ?');
    void jarvis.handle('Franck');
    flushMicrotasks();
    void jarvis.handle('je passe');
    flushMicrotasks();
    void jarvis.handle('on arrête là');
    flushMicrotasks();
    expect(jarvis.interviewing()).toBeFalse();
    expect(fastBrain.converse).not.toHaveBeenCalled();
    expect(atelier.streamChat).not.toHaveBeenCalled();
    const sent = interviewClient.draft.calls.mostRecent().args[0];
    expect(sent.length).toBe(2);
    expect(sent[0].answer).toBe('Franck');
    expect(sent[1].answer).toBe('');
    expect(jarvis.interviewDraft()?.sections.QUI).toBe('Chef de projet');
    expect(jarvis.portraitOpen()).toBeTrue();
    jarvis.interviewDraftDone();
    expect(jarvis.interviewDraft()).toBeNull();
    jarvis.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-241 / SF-241-02 — « plus tard » : la proposition est remise (passerelle), pas d’entretien', fakeAsync(() => {
    interviewClient.offer.and.returnValue(of({ offer: true, offers: 0 }));
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const jarvis = fixture.componentInstance;
    spyOn(jarvis, 'say').and.callFake(async () => undefined);
    void jarvis.handle('non');
    flushMicrotasks();
    expect(interviewClient.offered).toHaveBeenCalled();
    expect(jarvis.interviewOffer()).toBeFalse();
    expect(jarvis.interviewing()).toBeFalse();
    jarvis.ngOnDestroy();
    fixture.destroy();
  }));

  /** SF-241-06 — un entretien lancé par « oui » à la proposition ; la voix est remplacée par un relevé. */
  function startedInterview(params: Record<string, string> = {}) {
    interviewClient.offer.and.returnValue(of({ offer: true, offers: 0 }));
    interviewClient.draft.and.returnValue(of({ sections: { QUI: 'Chef de projet' }, souvenirs: [] }));
    configure(params);
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const jarvis = fixture.componentInstance;
    const spoken: string[] = [];
    spyOn(jarvis, 'say').and.callFake(async (phrase: string) => { spoken.push(phrase); });
    const listen = spyOn(jarvis, 'listen').and.resolveTo();
    void jarvis.handle('oui');
    flushMicrotasks();
    return { fixture, jarvis, spoken, listen };
  }

  it('F-241 / SF-241-06 (E2) — réponse longue en plusieurs segments : pas de question suivante, une seule réponse concaténée', fakeAsync(() => {
    const { fixture, jarvis, spoken } = startedInterview();
    const internals = jarvis as unknown as { listenCapped: boolean };
    void jarvis.handle('Franck');
    flushMicrotasks();
    expect(spoken[spoken.length - 1]).toBe(INTERVIEW_QUESTIONS[1]);
    const before = spoken.length;
    // L'écoute a été close par son plafond : ce n'est qu'un segment.
    internals.listenCapped = true;
    void jarvis.handle('Je suis chef de projet chez Orion depuis six ans,');
    flushMicrotasks();
    expect(spoken.length).toBe(before);
    expect(jarvis.interviewView()?.n).toBe(2);
    void jarvis.handle('et je forme les nouveaux.');
    flushMicrotasks();
    expect(spoken[spoken.length - 1]).toBe(INTERVIEW_QUESTIONS[2]);
    void jarvis.handle('Attendez, je n’avais pas fini.');
    flushMicrotasks();
    expect(spoken[spoken.length - 1]).toBe('Je vous écoute.');
    void jarvis.handle('Et j’aime ça.');
    flushMicrotasks();
    expect(spoken[spoken.length - 1]).toBe(INTERVIEW_QUESTIONS[2]);
    void jarvis.handle('on arrête là');
    flushMicrotasks();
    const sent = interviewClient.draft.calls.mostRecent().args[0];
    expect(sent.map((a) => a.answer)).toEqual(['Franck',
      'Je suis chef de projet chez Orion depuis six ans, et je forme les nouveaux. Et j’aime ça.']);
    expect(fastBrain.converse).not.toHaveBeenCalled();
    expect(atelier.streamChat).not.toHaveBeenCalled();
    jarvis.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-241 / SF-241-06 (E1) — en entretien, la fin de tour du fournisseur attend 3,5 s même sur une phrase close', fakeAsync(() => {
    const { fixture, jarvis } = startedInterview();
    const internals = jarvis as unknown as {
      live: { committed: string[]; lastPartial: string } | null; holdIfSuspended(): boolean; clearSuspendTimer(): void;
    };
    const finish = spyOn(jarvis, 'finishListening').and.resolveTo();
    internals.live = { committed: ['Chef de projet.'], lastPartial: '' };
    jarvis.state.set('ecoute');
    expect(internals.holdIfSuspended()).toBeTrue();
    tick(INTERVIEW_END_MS - 1);
    expect(finish).not.toHaveBeenCalled();
    tick(1);
    expect(finish).toHaveBeenCalledTimes(1);
    // Hors entretien : une phrase close n'est pas retenue.
    jarvis.state.set('repos');
    void jarvis.stopInterview();
    flushMicrotasks();
    expect(internals.holdIfSuspended()).toBeFalse();
    internals.live = null;
    jarvis.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-241 / SF-241-06 (E3-E5) — veille ⇒ réveil ⇒ « Bonjour Jarvis » jamais compté ; « On reprend l’entretien ? » ; « oui » reprend à la question en cours', fakeAsync(() => {
    const { fixture, jarvis, spoken, listen } = startedInterview();
    void jarvis.handle('Franck');
    flushMicrotasks();
    // « Bonjour Jarvis » en plein entretien : la question est redite, rien n'est compté.
    void jarvis.handle('Bonjour Jarvis.');
    flushMicrotasks();
    expect(spoken[spoken.length - 1]).toBe(INTERVIEW_QUESTIONS[1]);
    expect(jarvis.interviewView()?.n).toBe(2);
    // La veille au silence suspend l'entretien.
    jarvis.startConversation();
    void (jarvis as unknown as { endConversation(): Promise<void> }).endConversation();
    flushMicrotasks();
    expect(jarvis.interviewView()?.suspended).toBeTrue();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.jv__interview-bar')?.textContent)
      .toContain('Entretien en pause — question 2/10');
    // Le réveil : la question de reprise, et l'écoute sans la phrase du réveil.
    listen.calls.reset();
    void (jarvis as unknown as { wakeUpOn(): Promise<void> }).wakeUpOn();
    flushMicrotasks();
    expect(spoken[spoken.length - 1]).toBe('Nous en étions à la question 2 sur 10 de l’entretien. On reprend l’entretien ?');
    expect(listen).toHaveBeenCalledWith();
    void jarvis.handle('Bonjour Jarvis');
    flushMicrotasks();
    expect(spoken[spoken.length - 1]).toBe('On reprend l’entretien ? Oui ou non.');
    expect(jarvis.interviewView()?.suspended).toBeTrue();
    void jarvis.handle('Oui.');
    flushMicrotasks();
    expect(jarvis.interviewView()?.suspended).toBeFalse();
    expect(spoken[spoken.length - 1]).toBe(INTERVIEW_QUESTIONS[1]);
    void jarvis.handle('Chef de projet');
    flushMicrotasks();
    void jarvis.handle('on arrête là');
    flushMicrotasks();
    const sent = interviewClient.draft.calls.mostRecent().args[0];
    expect(sent.map((a) => a.answer)).toEqual(['Franck', 'Chef de projet']);
    expect(JSON.stringify(sent)).not.toContain('Bonjour');
    expect(atelier.streamChat).not.toHaveBeenCalled();
    jarvis.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-241 / SF-241-06 (E5) — après la reprise proposée, une autre demande clôt l’entretien (brouillon) et part normalement ; « Jarvis, pause » n’est pas une réponse', fakeAsync(() => {
    const { fixture, jarvis, spoken } = startedInterview({ sujet: 'w-2' });
    void jarvis.handle('Franck');
    flushMicrotasks();
    void jarvis.handle('Jarvis, pause');
    flushMicrotasks();
    expect(spoken[spoken.length - 1]).toBe('Je me mets en pause.');
    expect(jarvis.interviewView()?.suspended).toBeTrue();
    void jarvis.toggleConversation();
    flushMicrotasks();
    expect(spoken[spoken.length - 1]).toContain('On reprend l’entretien ?');
    void jarvis.handle('Où en est la migration ?');
    flushMicrotasks();
    expect(jarvis.interviewing()).toBeFalse();
    expect(jarvis.interviewView()).toBeNull();
    expect(interviewClient.draft.calls.mostRecent().args[0].map((a) => a.answer)).toEqual(['Franck']);
    expect(jarvis.interviewDraft()?.sections.QUI).toBe('Chef de projet');
    expect(atelier.streamChat.calls.mostRecent().args[1]).toBe('Où en est la migration ?');
    jarvis.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-241 / SF-241-06 (E6) — le bandeau « Entretien — question n/N » et « Arrêter » au toucher (réponses gardées)', fakeAsync(() => {
    const { fixture, jarvis } = startedInterview();
    void jarvis.handle('Franck');
    flushMicrotasks();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jv__interview-bar')?.textContent).toContain('Entretien — question 2/10');
    expect(el.querySelector('.jv__interview-resume')).toBeNull();
    (el.querySelector('.jv__interview-stop') as HTMLButtonElement).click();
    flushMicrotasks();
    fixture.detectChanges();
    expect(jarvis.interviewing()).toBeFalse();
    expect(el.querySelector('.jv__interview-bar')).toBeNull();
    expect(interviewClient.draft.calls.mostRecent().args[0].map((a) => a.answer)).toEqual(['Franck']);
    jarvis.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-236 / SF-236-24 — ?cockpit=1 : ne parle ni n’écoute ; dessine ce que l’écran principal relaie ; meneur parti ⇒ ses signaux', async () => {
    configure({ cockpit: '1' });
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    expect(jarvis.cockpitFollower).toBeTrue();
    expect(jarvis.wakeOn()).toBeFalse();
    const leader = new CockpitLeader(new BroadcastChannel(COCKPIT_CHANNEL) as unknown as LinkChannel);
    await new Promise((r) => setTimeout(r, 50));
    leader.publish({ ...jarvis.stageSignals(), pulses: 42, subjectId: 's-relaye' });
    await new Promise((r) => setTimeout(r, 50));
    expect(jarvis.stageSignals().pulses).toBe(42);
    expect(jarvis.linkLatency()).toBeLessThan(100);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.jv__presenting')?.textContent).toContain('Vue cockpit');
    leader.close();
    await new Promise((r) => setTimeout(r, 50));
    expect(jarvis.leaderGone()).toBeTrue();
    expect(jarvis.stageSignals().pulses).toBe(0);
    expect(atelier.streamChat).not.toHaveBeenCalled();
    jarvis.ngOnDestroy();
    fixture.destroy();
  });

  it('F-236 / SF-236-24 — veilleur de nuit : ouvert depuis ?veilleur=1, refermé par « Quitter le veilleur »', fakeAsync(() => {
    configure({ veilleur: '1' });
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-jarvis-night .nt__clock')).not.toBeNull();
    (el.querySelector('.nt__exit') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el.querySelector('app-jarvis-night')).toBeNull();
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
    discardPeriodicTasks();
  }));

  it('F-236 / SF-236-01 — 3D possible : bouton « Vue 2D » proposé, masqué en mode sobre', fakeAsync(() => {
    renderProbe = { ...PC_PROBE };
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jv__view')?.textContent).toContain('Vue 2D');
    expect(loader3d).toHaveBeenCalledTimes(1);
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-236 / SF-236-02 — le cœur 3D remplace l’orbe : orbe effacée quand la 3D est affichée, bouton « parler » gardé', fakeAsync(() => {
    renderProbe = { ...PC_PROBE };
    const engines: FakeEngine[] = [];
    loader3d.and.callFake(() => Promise.resolve(fakeEngineFactory(engines)));
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(engines.length).toBe(1);
    expect(engines[0].options.withCore).toBeTrue();
    expect(el.querySelector('main.jv')?.classList).not.toContain('jv--3d');
    engines[0].firstFrame();
    fixture.detectChanges();
    expect(el.querySelector('main.jv')?.classList).toContain('jv--3d');
    expect(el.querySelector('.jv__orb-button')).not.toBeNull();
    expect(engines[0].options.voiceLevel()).toBe(0);
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-236 / SF-236-06 — à la fermeture, la mesure du rendu part une fois (2D : la raison, aucun contenu)', fakeAsync(() => {
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    fixture.componentInstance.ngOnDestroy();
    expect(jarvisApi.render).toHaveBeenCalledOnceWith({ mode: '2d', render: { r_no_webgl: 1 } });
    fixture.destroy();
    expect(jarvisApi.render).toHaveBeenCalledTimes(1);
  }));

  it('F-236 / SF-236-06 — HUD 3D : volet « Réglages » (mêmes commandes), compteur « À envoyer » ; rien de tout ça en 2D', fakeAsync(() => {
    renderProbe = { ...PC_PROBE };
    const engines: FakeEngine[] = [];
    loader3d.and.callFake(() => Promise.resolve(fakeEngineFactory(engines)));
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jv__settings-toggle')).toBeNull();
    engines[0].firstFrame();
    fixture.detectChanges();
    const toggle = el.querySelector('.jv__settings-toggle') as HTMLButtonElement;
    expect(toggle).not.toBeNull();
    expect(el.querySelector('.jv__settings')).toBeNull();
    toggle.click();
    fixture.detectChanges();
    const panel = el.querySelector('.jv__settings') as HTMLElement;
    expect(panel.textContent).toContain('Mode sobre');
    expect(panel.textContent).toContain('Vue 2D');
    expect(panel.textContent).toContain('Humour');
    expect(panel.textContent).toContain('« Jarvis »');
    fixture.componentInstance.ngOnDestroy();
    expect(jarvisApi.render).toHaveBeenCalledWith(jasmine.objectContaining({ mode: '3d' }));
    fixture.destroy();
  }));

  it('F-236 / SF-236-05 — quitter : en 2D, navigation immédiate vers l’atelier (aucune sortie 3D à jouer)', fakeAsync(() => {
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const router = TestBed.inject(Router);
    const nav = spyOn(router, 'navigateByUrl').and.resolveTo(true);
    const event = new MouseEvent('click', { cancelable: true });
    fixture.componentInstance.leave(event);
    expect(event.defaultPrevented).toBeTrue();
    expect(nav).toHaveBeenCalledOnceWith('/atelier');
    fixture.componentInstance.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-236 / SF-236-04 — toucher un sujet de l’univers 3D = le choisir ; un client ou un chantier ne navigue pas', fakeAsync(() => {
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const component = fixture.componentInstance;
    const choose = spyOn(component, 'chooseSubject').and.callThrough();
    component.touched3d({ key: 'h-cagip', kind: 'client' });
    component.touched3d({ key: 'w-1#3', kind: 'chantier', subjectId: 'w-1' });
    expect(choose).not.toHaveBeenCalled();
    component.touched3d({ key: 'w-1', kind: 'subject', subjectId: 'w-1' });
    expect(choose).toHaveBeenCalledOnceWith('w-1');
    expect(jarvisApi.usage).toHaveBeenCalledWith('TOUCH');
    component.ngOnDestroy();
    fixture.destroy();
  }));

  it('F-235 / SF-235-09 — humour : léger par défaut, bascule vers aucun, retour si l’enregistrement échoue', fakeAsync(() => {
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const jarvis = fixture.componentInstance;
    expect(jarvisApi.preferences).toHaveBeenCalled();
    expect(jarvis.humour()).toBe('LIGHT');
    const button: HTMLButtonElement = fixture.nativeElement.querySelector('.jv__humour');
    expect(button.textContent).toContain('Humour : léger');

    jarvis.toggleHumour();
    expect(jarvisApi.setHumour).toHaveBeenCalledWith('NONE');
    expect(jarvis.humour()).toBe('NONE');

    jarvisApi.setHumour.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    jarvis.toggleHumour();
    expect(jarvis.humour()).toBe('NONE');
    jarvis.ngOnDestroy();
  }));

  it('SF-231-05 — compte la session, et l’alerte de veille est lue sur son sujet, puis Jarvis écoute', fakeAsync(() => {
    veilles.alert.and.returnValue(of({
      id: 'a-1', watchId: 'v-1', watchName: 'Jira AGENOR', platform: 'Jira', workspaceId: 'w-1', chantierNumber: null,
      sentence: 'AG-2 est passé en Bloqué.', changes: [], aiWritten: true, costEur: 0.01,
      createdAt: '2026-11-01T09:00:00Z', openedAt: null, link: '/jarvis?alerte=a-1',
    }));
    configure({ alerte: 'a-1' });
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const jarvis = fixture.componentInstance;

    expect(jarvisApi.usage).toHaveBeenCalledWith('SESSION');
    expect(jarvis.subjectId()).toBe('w-1');
    expect(veilles.openAlert).toHaveBeenCalledWith('a-1');
    expect(voice.speak).toHaveBeenCalledWith('Veille Jira AGENOR : AG-2 est passé en Bloqué. Que voulez-vous faire ?', 'w-1');
    expect(jarvis.state()).toBe('ecoute');
    jarvis.ngOnDestroy();
  }));

  it('SF-231-05 — le son refusé avant le premier geste : l’alerte attend l’appui', fakeAsync(() => {
    veilles.alert.and.returnValue(of({
      id: 'a-1', watchId: 'v-1', watchName: null, platform: null, workspaceId: 'w-1', chantierNumber: null,
      sentence: 'Deux tickets ont bougé.', changes: [], aiWritten: false, costEur: null,
      createdAt: '2026-11-01T09:00:00Z', openedAt: null, link: '/jarvis?alerte=a-1',
    }));
    voice.speak.and.rejectWith('echec');
    configure({ alerte: 'a-1' });
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const jarvis = fixture.componentInstance;
    expect(jarvis.notice()).toContain('Touche l’orbe');

    voice.speak.and.resolveTo();
    void jarvis.talk();
    flushMicrotasks();
    expect(voice.speak).toHaveBeenCalledTimes(2);
    expect(jarvis.state()).toBe('ecoute');
    jarvis.ngOnDestroy();
  }));

  it('ouvre sur le sujet demandé par le lien (?sujet=)', () => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.subject()?.name).toBe('CAGIP');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('CAGIP');
  });

  it('parle → écoute → envoie dans le fil du sujet, marqué JARVIS, puis dit la phrase orale', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    dictation.stop.and.resolveTo('Où en est la migration ?');

    void jarvis.talk();
    flushMicrotasks();
    expect(jarvis.state()).toBe('ecoute');

    void jarvis.talk();
    flushMicrotasks();
    expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Où en est la migration ?', jasmine.any(Object), 'ACT',
      false, [], null, false, 'JARVIS', null, false);
    expect(jarvis.state()).toBe('reflexion');

    handlers!.onText('🎙 La migration');
    handlers!.onDone({ reply: '🎙 La migration est à l’étape 4.\n\nDétail…', actions: [], messageId: 'm-1' });
    flushMicrotasks();
    expect(voice.speak).toHaveBeenCalledWith('La migration est à l’étape 4.', 'w-2', jasmine.any(Object));
    expect(jarvis.detail()).toContain('Détail');
    expect(jarvis.state()).toBe('repos');
    // F-233 / SF-233-01 — le tour vocal est chronométré, sans contenu ; F-240 : la mesure est gardée jusqu'au tour
    // suivant (étage atteint), puis déclarée.
    expect(jarvisApi.timings).not.toHaveBeenCalled();
    jarvis['flushParked']();
    expect(jarvisApi.timings).toHaveBeenCalledTimes(1);
    const report = jarvisApi.timings.calls.mostRecent().args[0];
    expect(report.workspaceId).toBe('w-2');
    expect(report.transcriptMs).not.toBeNull();
    expect(report.sentMs).not.toBeNull();
    expect(report.firstTokenMs).not.toBeNull();
    expect(report.doneMs).not.toBeNull();
    expect(JSON.stringify(report)).not.toContain('migration');
    // F-240 / SF-240-01 — ce qui est dit : 6 mots, aucune annonce avant outils.
    expect(report.spokenWords).toBe(6);
    expect(report.announceWords).toBe(0);
  }));

  it('« passe sur AGENOR » change de sujet sur l’appareil, sans tour', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;

    void jarvis.handle('passe sur agenor');
    flushMicrotasks();

    expect(jarvis.subjectId()).toBe('w-1');
    expect(atelier.streamChat).not.toHaveBeenCalled();
    expect(voice.speak).toHaveBeenCalledWith('Je passe sur Connecteur AGENOR.', 'w-1');
  }));

  it('F-234 / SF-234-13 — « c’est bon » referme ce qui est montré, sur l’appareil, sans tour', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    const bus = TestBed.inject(JarvisSceneBus);
    bus.turnStarted();
    bus.text('🖥 montrer: clients\n');
    bus.done();
    expect(bus.state().show).not.toBeNull();

    void jarvis.handle('C’est bon');
    flushMicrotasks();

    expect(bus.state().show).toBeNull();
    expect(atelier.streamChat).not.toHaveBeenCalled();
  }));

  it('un appui pendant que Jarvis parle le coupe et réécoute (interruption)', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    jarvis.state.set('parole');

    void jarvis.talk();
    flushMicrotasks();

    expect(voice.stop).toHaveBeenCalled();
    expect(dictation.start).toHaveBeenCalled();
    expect(jarvis.state()).toBe('ecoute');
  }));

  it('SF-231-03 / F-239 / SF-239-01 — un tour Opus qui tarde reçoit UNE attente liée à la demande, puis s’annonce terminé',
    fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;

    jarvis.send('w-2', 'Lance les tests sur CAGIP');
    tick(2_499);
    expect(voice.speak).not.toHaveBeenCalled();
    tick(1);
    expect(voice.speak).toHaveBeenCalledTimes(1);
    const said = voice.speak.calls.mostRecent().args[0];
    expect(said).toMatch(/^Je (regarde pour|consulte|me penche sur) CAGIP\.$/);
    expect(FILLERS).not.toContain(said);
    expect(jarvis.state()).toBe('reflexion');
    // Une seule formule par échange : rien de plus pendant que le tour tourne.
    tick(20_000);
    expect(voice.speak).toHaveBeenCalledTimes(1);

    tick(30_000);
    handlers!.onDone({ reply: '🎙 Les tests passent.', actions: [], messageId: 'm-1' });
    flushMicrotasks();
    expect(voice.speak).toHaveBeenCalledWith('C’est terminé. Les tests passent.', 'w-2', jasmine.any(Object));
  }));

  it('F-239 / SF-239-12 — le compteur : ce que Jarvis a coûté aujourd’hui et ce mois-ci, en euros', fakeAsync(() => {
    const zero = { fastBrain: 0, opus: 0, voice: 0, listening: 0, total: 0, opusTurns: 0 };
    jarvisApi.costs.and.returnValue(of({ today: { ...zero, total: 0.42 },
      month: { fastBrain: 1.2, opus: 4.9, voice: 0, listening: 0, total: 6.1, opusTurns: 5 } }));
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    expect(jarvis.costLine()).toBe(`Jarvis : ${euros(0.42)} aujourd’hui · ${euros(6.1)} ce mois`);
    expect(jarvis.costDetail()).toContain('Opus');
    expect(jarvis.costDetail()).toContain('5 tours');
    expect(euros(6.1)).toContain('6,10');
    jarvis.ngOnDestroy();
  }));

  it('F-244 / SF-244-04 — le compteur ouvre la ventilation en euros ; rien en présentation', fakeAsync(() => {
    const zero = { fastBrain: 0, opus: 0, voice: 0, listening: 0, total: 0, opusTurns: 0, mail: 0, agenda: 0 };
    jarvisApi.costs.and.returnValue(of({ today: { ...zero, total: 0.42, opus: 0.42 },
      month: { ...zero, opus: 4.9, fastBrain: 1.2, total: 6.1, opusTurns: 5 },
      budget: { monthlyCapEur: 150, applyOnByok: false, monthSpentEur: 6.1, percent: 4, state: 'NONE' as const,
        applies: true } }));
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    jarvis.costOpen.set(true);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('app-jarvis-cost-panel')).not.toBeNull();
    expect(el.querySelector('app-jarvis-cost-panel')!.textContent).toContain('Recherches Opus');
    expect(el.querySelector('app-jarvis-cost-panel')!.textContent).not.toContain('$');

    jarvis.presentation.set(true);
    fixture.detectChanges();
    expect(el.querySelector('app-jarvis-cost-panel')).toBeNull();
    expect(el.querySelector('.jv__cost')).toBeNull();
    jarvis.ngOnDestroy();
  }));

  it('F-239 / SF-239-06 — un toucher sur le texte compris l’ouvre ; Entrée envoie la demande corrigée', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    jarvis.said.set('Où en est Cagibi ?');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>('button.jv__said')!.click();
    fixture.detectChanges();
    const input = el.querySelector<HTMLInputElement>('.jv__said-edit input')!;
    expect(input.value).toBe('Où en est Cagibi ?');

    void jarvis.submitTouchCorrection('Où en est CAGIP ?');
    flushMicrotasks();
    expect(jarvis.said()).toBe('Où en est CAGIP ?');
    expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Où en est CAGIP ?', jasmine.any(Object), 'ACT', false, [],
      null, false, 'JARVIS', null, false);
    expect(jarvisApi.correctCapture).not.toHaveBeenCalled(); // capture éteinte
    jarvis.ngOnDestroy();
  }));

  it('F-239 / SF-239-06 — « non, j’ai dit CAGIP » corrige le mot mal compris de la demande précédente',
    fakeAsync(() => {
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      void jarvis.handle('Où en est Cagibi ?');
      flushMicrotasks();
      handlers!.onDone({ reply: '🎙 Je ne connais pas Cagibi.', actions: [], messageId: 'm-1' });
      flushMicrotasks();
      atelier.streamChat.calls.reset();

      void jarvis.handle('Non, j’ai dit CAGIP.');
      flushMicrotasks();
      expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Où en est CAGIP ?', jasmine.any(Object), 'ACT', false,
        [], null, false, 'JARVIS', null, false);
      expect(jarvis.said()).toBe('Où en est CAGIP ?');
      jarvis.ngOnDestroy();
    }));

  it('SF-231-03 — « stop » interrompt le travail en cours', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    jarvis.send('w-2', 'Lance les tests');

    void jarvis.handle('Stop !');
    flushMicrotasks();

    expect(atelier.interruptChat).toHaveBeenCalledWith('w-2');
    expect(voice.speak).toHaveBeenCalledWith('J’arrête.', 'w-2');
    expect(atelier.streamChat).toHaveBeenCalledTimes(1);
  }));

  it('SF-231-04 — une autorisation d’outil est dite, et « oui » l’accorde', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    jarvis.send('w-2', 'Pousse la branche');
    dictation.stop.and.resolveTo('Oui, vas-y');

    handlers!.onConfirmRequest!({ toolUseId: 't-1', tool: 'bash', detail: 'git push' });
    flushMicrotasks();
    expect(voice.speak).toHaveBeenCalledWith('Je vais lancer la commande « git push ». Vous confirmez ?', 'w-2');
    expect(jarvis.state()).toBe('ecoute');

    void jarvis.finishListening();
    flushMicrotasks();
    expect(atelier.confirmChatToolUse).toHaveBeenCalledWith('w-2', { toolUseId: 't-1', decision: 'allow' });
    jarvis.ngOnDestroy();
  }));

  it('F-244 / SF-244-03 — budget atteint : « Je la lance ? » est dit, et seul un « oui » confirme puis relance', fakeAsync(() => {
    jarvisApi = Object.assign(jarvisApi, { confirmBudget: jasmine.createSpy('confirmBudget').and.returnValue(of(undefined)) });
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    jarvis.send('w-2', 'Cherche la cause');
    const phrase = 'Budget Jarvis du mois atteint ; cette recherche coûte environ 1 €. Je la lance ?';

    handlers!.onError('jarvis_budget_confirm', phrase);
    flushMicrotasks();
    expect(voice.speak).toHaveBeenCalledWith(phrase, 'w-2');
    expect(jarvis.notice()).not.toBe('Le tour n’a pas abouti.');

    void jarvis.handle('oui');
    flushMicrotasks();
    expect((jarvisApi as unknown as { confirmBudget: jasmine.Spy }).confirmBudget).toHaveBeenCalledTimes(1);
    expect(atelier.streamChat).toHaveBeenCalledTimes(2);
    expect(atelier.streamChat.calls.mostRecent().args[1]).toBe('Cherche la cause');
    jarvis.ngOnDestroy();
  }));

  it('F-244 / SF-244-03 — budget atteint : « non » ne lance rien ; plafond dur : la phrase fixe, rien ne repart', fakeAsync(() => {
    jarvisApi = Object.assign(jarvisApi, { confirmBudget: jasmine.createSpy('confirmBudget').and.returnValue(of(undefined)) });
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    jarvis.send('w-2', 'Cherche la cause');

    handlers!.onError('jarvis_budget_confirm', 'Je la lance ?');
    flushMicrotasks();
    void jarvis.handle('non');
    flushMicrotasks();
    expect(voice.speak).toHaveBeenCalledWith('Entendu, je ne lance rien.', 'w-2');
    expect((jarvisApi as unknown as { confirmBudget: jasmine.Spy }).confirmBudget).not.toHaveBeenCalled();
    expect(atelier.streamChat).toHaveBeenCalledTimes(1);

    jarvis.send('w-2', 'Cherche encore');
    handlers!.onError('jarvis_budget_hard', 'Monsieur, le budget de Jarvis de ce mois est épuisé.');
    flushMicrotasks();
    expect(voice.speak).toHaveBeenCalledWith('Monsieur, le budget de Jarvis de ce mois est épuisé.', 'w-2');
    expect(atelier.streamChat).toHaveBeenCalledTimes(2);
    jarvis.ngOnDestroy();
  }));

  it('SF-231-04 — un doute n’accorde rien et n’envoie rien', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    jarvis.send('w-2', 'Pousse la branche');
    dictation.stop.and.resolveTo('oui mais attends');

    handlers!.onConfirmRequest!({ toolUseId: 't-1', tool: 'bash', detail: 'git push' });
    flushMicrotasks();
    void jarvis.finishListening();
    flushMicrotasks();

    expect(atelier.confirmChatToolUse).not.toHaveBeenCalled();
    expect(voice.speak).toHaveBeenCalledWith(jasmine.stringContaining('je ne fais rien'), 'w-2');
    expect(atelier.streamChat).toHaveBeenCalledTimes(1);
    jarvis.ngOnDestroy();
  }));

  it('SF-231-04 — l’agent demande « Tu confirmes ? » : seul un oui/non net repart au cerveau', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    jarvis.send('w-2', 'Lance le chantier 4 en autonome');
    handlers!.onDone({ reply: '🎙 Je lance le chantier 4 en autonome. Tu confirmes ?', actions: [], messageId: 'm' });
    flushMicrotasks();
    expect(jarvis.state()).toBe('ecoute');

    dictation.stop.and.resolveTo('euh');
    void jarvis.finishListening();
    flushMicrotasks();
    expect(atelier.streamChat).toHaveBeenCalledTimes(1);

    void jarvis.handle('Oui');
    flushMicrotasks();
    expect(atelier.streamChat).toHaveBeenCalledTimes(2);
    expect(atelier.streamChat.calls.mostRecent().args[1]).toBe('Oui');
    jarvis.ngOnDestroy();
  }));

  it('sans voix (plafond atteint), la phrase reste écrite', fakeAsync(() => {
    voice.speak.and.rejectWith('budget');
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;

    void jarvis.say('C’est fait.');
    flushMicrotasks();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('C’est fait.');
    expect(text).toContain('Plafond du mois atteint');
    expect(jarvis.state()).toBe('repos');
  }));

  // ---- F-232 / SF-232-02 : le compagnon Android ----

  it('F-232 — sur Android, propose d’installer le compagnon servi par la gateway', () => {
    userAgent = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) Chrome/130 Mobile';
    jarvisApi.companion.and.returnValue(of(COMPANION_V4));
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();

    const link = (fixture.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('.jv__companion');
    expect(link?.textContent).toContain('installer le compagnon Android (v0.4.0)');
    expect(link?.getAttribute('href')).toBe('/api/runner/download/android');
    expect(link?.hasAttribute('download')).toBeTrue();
  });

  it('F-232 — hors Android et sans compagnon, rien n’est demandé ni affiché', () => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();

    expect(jarvisApi.companion).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).querySelector('.jv__companion')).toBeNull();
  });

  it('F-232 — ouvert par un compagnon plus ancien : propose la mise à jour, écoute aussitôt et compte le réveil',
    fakeAsync(() => {
      jarvisApi.companion.and.returnValue(of(COMPANION_V4));
      configure({ source: 'compagnon', compagnon: '3' });
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      flushMicrotasks();
      fixture.detectChanges();

      const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
      expect(text).toContain('Mise à jour du compagnon disponible (v0.4.0)');
      expect(jarvisApi.usage).toHaveBeenCalledWith('WAKE');
      expect(dictation.start).toHaveBeenCalled();
      expect(fixture.componentInstance.state()).toBe('ecoute');
      fixture.componentInstance.ngOnDestroy();
    }));

  it('F-232 — le compagnon à jour : aucune proposition', () => {
    expect(companionOffer(COMPANION_V4, 4, true)).toBeNull();
    expect(companionOffer(COMPANION_V4, 5, true)).toBeNull();
    expect(companionOffer(COMPANION_V4, 3, false)).toEqual({ kind: 'update', versionName: '0.4.0' });
    expect(companionOffer(COMPANION_V4, null, false)).toBeNull();
    expect(companionOffer(NO_COMPANION, null, true)).toBeNull();
  });

  it('F-233 / SF-233-11 / F-239 — charge le répertoire ; aucune formule creuse, même quand la réponse tarde',
    fakeAsync(() => {
    voice.hasClip.and.returnValue(true);
    voice.playClip.and.resolveTo(true);
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    expect(voice.preloadClips).toHaveBeenCalledWith(FORMULA_CLIPS);
    dictation.stop.and.resolveTo('Où en est la migration ?');

    void jarvis.talk();
    flushMicrotasks();
    void jarvis.talk();
    flushMicrotasks();
    tick(10_000);
    // Aucun objet nommé : silence (le cœur montre qu'il réfléchit), jamais une formule générique.
    expect(voice.playClip).not.toHaveBeenCalled();
    expect(voice.speak).not.toHaveBeenCalled();
    jarvis.ngOnDestroy();
    tick(1_000);
  }));

  it('F-239 / SF-239-01 — chemin Opus direct : rien pour « Bonjour », et jamais le même gabarit deux tours de suite',
    fakeAsync(() => {
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;

      jarvis.send('w-2', 'Bonjour Jarvis');
      tick(5_000);
      expect(voice.speak).not.toHaveBeenCalled();
      handlers!.onDone({ reply: '🎙 Bonjour, Monsieur.', actions: [], messageId: 'm-0' });
      flushMicrotasks();
      voice.speak.calls.reset();

      const said: string[] = [];
      for (let i = 0; i < 6; i++) {
        jarvis.send('w-2', 'Regarde le chantier 3 sur CAGIP');
        tick(2_500);
        said.push(voice.speak.calls.mostRecent().args[0]);
        handlers!.onDone({ reply: '🎙 Fait.', actions: [], messageId: `m-${i}` });
        flushMicrotasks();
      }
      for (let i = 1; i < said.length; i++) {
        expect(said[i].replace('CAGIP', '')).not.toBe(said[i - 1].replace('CAGIP', ''));
      }
      jarvis.ngOnDestroy();
    }));

  it('F-233 / SF-233-11 — aucune formule si la première phrase est déjà là', fakeAsync(() => {
    voice.hasClip.and.returnValue(true);
    voice.playClip.and.resolveTo(true);
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;

    jarvis.send('w-2', 'Bonjour');
    tick(400);
    handlers!.onText('🎙 Bonjour, Monsieur. ');
    tick(2_000);
    expect(voice.playClip).not.toHaveBeenCalled();
    expect(voice.speak).not.toHaveBeenCalled();
    jarvis.ngOnDestroy();
  }));

  it('F-233 / SF-233-11 / SF-233-12 — l’interrupteur : Pause / Reprendre ; jamais « Commencer » ni « J’ai fini »',
    fakeAsync(() => {
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      flushMicrotasks();
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      const button = () => ((fixture.nativeElement as HTMLElement).querySelector('.jv__talk')?.textContent ?? '').trim();
      expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Commencer');
      fixture.autoDetectChanges();

      void jarvis.toggleConversation();
      tick(300);
      fixture.detectChanges();
      expect(voice.unlock).toHaveBeenCalled();
      expect(jarvis.state()).toBe('ecoute');
      expect(button()).toContain('Pause');
      expect(button()).not.toContain('J’ai fini');

      void jarvis.toggleConversation();
      flushMicrotasks();
      tick(1_000);
      fixture.detectChanges();
      expect(dictation.cancel).toHaveBeenCalled();
      expect(jarvis.state()).toBe('repos');
      expect(jarvis.conversation.mode()).toBe('paused');
      expect(button()).toContain('Reprendre la conversation');
      expect(dictation.start).toHaveBeenCalledTimes(1);
      jarvis.ngOnDestroy();
    }));

  it('F-233 / SF-233-12 — son bloqué : invite plein écran ; un toucher n’importe où démarre la conversation',
    fakeAsync(() => {
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      flushMicrotasks();
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      const el = fixture.nativeElement as HTMLElement;
      const gate = el.querySelector<HTMLButtonElement>('.jv__wake-gate');
      expect(gate).not.toBeNull();
      expect(gate?.textContent).toContain('Touchez l’écran pour réveiller Jarvis');
      expect(jarvis.conversation.mode()).toBe('idle');
      expect(dictation.start).not.toHaveBeenCalled();
      fixture.autoDetectChanges();

      gate?.click();
      tick(300);
      fixture.detectChanges();
      expect(voice.unlock).toHaveBeenCalled();
      expect(el.querySelector('.jv__wake-gate')).toBeNull();
      expect(jarvis.conversation.mode()).toBe('active');
      expect(jarvis.state()).toBe('ecoute');
      expect(el.textContent).not.toContain('Commencer');
      jarvis.ngOnDestroy();
    }));

  it('F-233 / SF-233-12 — son permis : la conversation démarre à l’ouverture, sans invite ni toucher',
    fakeAsync(() => {
      voice.audioAllowed.and.resolveTo(true);
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.autoDetectChanges();
      flushMicrotasks();
      tick(300);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelector('.jv__wake-gate')).toBeNull();
      expect(jarvis.wakeGate()).toBeFalse();
      expect(jarvis.conversation.mode()).toBe('active');
      expect(jarvis.state()).toBe('ecoute');
      expect(dictation.start).toHaveBeenCalledTimes(1);
      expect(el.textContent).not.toContain('Commencer');
      jarvis.ngOnDestroy();
    }));

  it('F-233 / SF-233-11 — en conversation, il se remet à écouter seul après avoir répondu, sans un mot',
    fakeAsync(() => {
      voice.hasClip.and.returnValue(true);
      voice.playClip.and.resolveTo(true);
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      fixture.autoDetectChanges();
      fixture.autoDetectChanges();
    jarvis.startConversation();
      tick(300);
      expect(jarvis.state()).toBe('ecoute');
      dictation.stop.and.resolveTo('Où en est la migration ?');

      void jarvis.finishListening();
      flushMicrotasks();
      handlers!.onText('🎙 La migration est à l’étape 4. ');
      handlers!.onDone({ reply: '🎙 La migration est à l’étape 4.', actions: [], messageId: 'm-1' });
      flushMicrotasks();
      expect(jarvis.state()).toBe('repos');
      tick(300);
      expect(jarvis.state()).toBe('ecoute');
      expect(dictation.start).toHaveBeenCalledTimes(2);
      expect(voice.playClip).not.toHaveBeenCalled();
      jarvis['flushParked']();
      const report = jarvisApi.timings.calls.mostRecent().args[0];
      expect(report.listenMode).toBe('CONV_BATCH');
      jarvis.ngOnDestroy();
    }));

  it('F-233 / SF-233-11 — un bruit n’envoie rien et l’écoute repart', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    fixture.autoDetectChanges();
    jarvis.startConversation();
    tick(300);
    dictation.stop.and.resolveTo('euh…');

    void jarvis.finishListening();
    flushMicrotasks();
    tick(300);
    expect(atelier.streamChat).not.toHaveBeenCalled();
    expect(voice.speak).not.toHaveBeenCalled();
    expect(jarvis.state()).toBe('ecoute');
    jarvis.ngOnDestroy();
  }));

  it('F-233 / SF-233-11 — « Jarvis, pause » : pause sans tour, et plus de réécoute', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    fixture.autoDetectChanges();
    jarvis.startConversation();
    tick(300);
    dictation.stop.and.resolveTo('Jarvis, pause.');

    void jarvis.finishListening();
    flushMicrotasks();
    tick(1_000);
    expect(atelier.streamChat).not.toHaveBeenCalled();
    expect(voice.speak).toHaveBeenCalledWith('Je me mets en pause.', 'w-2');
    expect(jarvis.conversation.mode()).toBe('paused');
    expect(jarvis.state()).toBe('repos');
    expect(dictation.start).toHaveBeenCalledTimes(1);
    jarvis.ngOnDestroy();
  }));

  it('F-233 / SF-233-11 — après 60 s sans demande : retour au repos, dit une fois et compté', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    fixture.autoDetectChanges();
    jarvis.startConversation();
    tick(300);
    // Une écoute qui s'est close sans un mot : la réécoute constate le silence.
    dictation.stop.and.resolveTo('');
    tick(61_000);
    void jarvis.finishListening();
    flushMicrotasks();
    tick(300);
    expect(jarvisApi.usage).toHaveBeenCalledWith('IDLE_END');
    expect(voice.speak).toHaveBeenCalledWith('Je me mets en veille.', 'w-2');
    expect(jarvis.conversation.mode()).toBe('idle');
    expect(jarvis.state()).toBe('repos');
    tick(1_000);
    expect(dictation.start).toHaveBeenCalledTimes(1);
    jarvis.ngOnDestroy();
  }));

  it('F-233 / SF-233-11 — aucun texte de l’écran ni formule ne tutoie dans l’interrupteur et l’état', () => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    for (const state of ['repos', 'ecoute', 'reflexion', 'parole'] as const) {
      jarvis.state.set(state);
      expect(tutoie(jarvis.statusLabel())).withContext(state).toBeFalse();
    }
    expect(tutoie(jarvis.toggleLabel())).toBeFalse();
  });

  it('F-233 / SF-233-03 — dit chaque phrase 🎙 dès qu’elle est écrite, sans la redire à la fin', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;

    jarvis.send('w-2', 'Regarde les logs du build');
    handlers!.onText('🎙 Je regarde les logs du build, Monsieur. ');
    expect(voice.enqueue).toHaveBeenCalledWith('Je regarde les logs du build, Monsieur.', 'w-2', jasmine.objectContaining({ onPlaying: jasmine.any(Function) })); // SF-234-11 : le nom surligné suit la phrase dite
    expect(jarvis.state()).toBe('parole');
    handlers!.onText('\n\n```\n🎙 pas ceci.\n```\n');
    handlers!.onText('🎙 Le build casse sur un test');
    expect(voice.enqueue).toHaveBeenCalledTimes(1);
    handlers!.onText(' de paie. Je corrige ?\n\nDétail…');
    expect(voice.enqueue).toHaveBeenCalledTimes(3);
    expect(voice.enqueue.calls.argsFor(1)[0]).toBe('Le build casse sur un test de paie.');

    handlers!.onDone({ reply: '🎙 Le build casse sur un test de paie. Je corrige ?\n\nDétail…', actions: [],
      messageId: 'm-1' });
    flushMicrotasks();
    expect(voice.idle).toHaveBeenCalled();
    expect(voice.speak).not.toHaveBeenCalled();
    expect(jarvis.state()).toBe('repos');
  }));

  it('F-241 / SF-241-03 — une ligne 🎙 d’Opus qui évoque un sujet « à ne pas aborder » non amené n’est jamais dite', fakeAsync(() => {
    portraitClient.get.and.returnValue(of({ exists: true, enabled: true, version: 2, sections: [], souvenirs: [],
      size: { chars: 10, tokens: 3, maxTokens: 1500, maxChars: 6000 }, lastInterviewAt: null, interviewOffers: 0,
      blockedTerms: ['divorce'] }));
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const jarvis = fixture.componentInstance;

    jarvis.send('w-2', 'Regarde les logs du build');
    handlers!.onText('🎙 Rien à voir avec votre divorce, Monsieur.\n');
    expect(voice.enqueue).not.toHaveBeenCalled();
    handlers!.onText('🎙 Le build est vert. ');
    expect(voice.enqueue).toHaveBeenCalledTimes(1);
    expect(voice.enqueue.calls.argsFor(0)[0]).toBe('Le build est vert.');
    // Le tour part avec le drapeau de présentation de l'écran (ici : non).
    expect(atelier.streamChat.calls.mostRecent().args[10]).toBeFalse();
    jarvis.ngOnDestroy();
  }));

  it('F-233 / SF-233-03 — sans ligne 🎙 écrite au fil de l’eau, la phrase finale est dite à la fin (repli)', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;

    jarvis.send('w-2', 'Bonjour');
    handlers!.onText('Bonjour Monsieur. Tout va bien.');
    expect(voice.enqueue).not.toHaveBeenCalled();
    handlers!.onDone({ reply: 'Bonjour Monsieur. Tout va bien.', actions: [], messageId: 'm-1' });
    flushMicrotasks();
    expect(voice.speak).toHaveBeenCalledWith('Bonjour Monsieur. Tout va bien.', 'w-2', jasmine.any(Object));
  }));

  it('F-233 / SF-233-05 — écoute en flux : texte partiel, fin de tour du fournisseur, session close et comptée',
    fakeAsync(() => {
      jarvisApi.sttSession.and.returnValue(of({
        sessionId: 's-1', provider: 'elevenlabs', url: 'wss://x/realtime?model_id=m', token: 't',
        expiresAt: '2030-01-01T00:00:00Z', sampleRate: 16000, maxSeconds: 120,
      }));
      let stt: StreamHandlers | null = null;
      const listening = jasmine.createSpyObj('LiveListening', ['commit', 'close'], { stream: undefined });
      listening.close.and.returnValue(4);
      opener.open.and.callFake((_t, h) => {
        stt = h;
        return Promise.resolve(listening);
      });
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;

      void jarvis.talk();
      flushMicrotasks();
      expect(jarvis.state()).toBe('ecoute');
      expect(dictation.start).not.toHaveBeenCalled();
      stt!.onPartial('Où en est');
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Où en est');

      stt!.onCommitted('Où en est la migration ?');
      flushMicrotasks();
      expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Où en est la migration ?', jasmine.any(Object), 'ACT',
        false, [], null, false, 'JARVIS', null, false);
      expect(jarvisApi.closeSttSession).toHaveBeenCalledWith('s-1', 4);
      handlers!.onDone({ reply: '🎙 Étape 4.', actions: [], messageId: 'm-1' });
      flushMicrotasks();
      jarvis['flushParked']();
      expect(jarvisApi.timings.calls.mostRecent().args[0].listenMode).toBe('STREAM');
      expect(jarvisApi.timings.calls.mostRecent().args[0].sttProvider).toBe('elevenlabs');
      expect(jarvisApi.timings.calls.mostRecent().args[0].ttsProvider).toBe('openai');
    }));

  /** Une écoute en flux dont l'énoncé est gardé (WAV de 2 s). */
  function streamWithAudio(): { stt: () => StreamHandlers } {
    jarvisApi.sttSession.and.returnValue(of({
      sessionId: 's-1', provider: 'elevenlabs', url: 'wss://x/realtime?model_id=m', token: 't',
      expiresAt: '2030-01-01T00:00:00Z', sampleRate: 16000, maxSeconds: 120,
    }));
    let stt: StreamHandlers | null = null;
    const listening = jasmine.createSpyObj('LiveListening', ['commit', 'close', 'audio', 'audioSeconds'],
      { stream: undefined });
    listening.close.and.returnValue(2);
    listening.audio.and.returnValue(new Blob(['pcm'], { type: 'audio/wav' }));
    listening.audioSeconds.and.returnValue(2);
    opener.open.and.callFake((_t, h) => {
      stt = h;
      return Promise.resolve(listening);
    });
    return { stt: () => stt! };
  }

  it('F-239 / SF-239-04 — flux : l’énoncé complet retranscrit (vocabulaire) remplace le texte provisoire',
    fakeAsync(() => {
      const ready = streamWithAudio();
      jarvisApi.utterance.and.returnValue(of({ text: 'Où en est CAGIP ?', provider: 'elevenlabs-batch', ms: 600 }));
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      void jarvis.talk();
      flushMicrotasks();
      ready.stt().onCommitted('Où en est Cagibi ?');
      flushMicrotasks();
      expect(jarvisApi.utterance).toHaveBeenCalledWith(jasmine.any(Blob), 2);
      expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Où en est CAGIP ?', jasmine.any(Object), 'ACT', false,
        [], null, false, 'JARVIS', null, false);
      expect(dictation.transcribe).not.toHaveBeenCalled();
      handlers!.onDone({ reply: '🎙 Étape 4.', actions: [], messageId: 'm-1' });
      flushMicrotasks();
      jarvis['flushParked']();
      expect(jarvisApi.timings.calls.mostRecent().args[0].sttProvider).toBe('elevenlabs-batch');
    }));

  it('F-239 / SF-239-03 — non-ADMIN : aucun interrupteur de capture, aucune capture', fakeAsync(() => {
    const ready = streamWithAudio();
    jarvisApi.preferences.and.returnValue(of({ humour: 'LIGHT' as const, captureAudio: true }));
    jarvisApi.utterance.and.returnValue(of({ text: 'Où en est CAGIP ?', provider: 'elevenlabs-batch', ms: 600 }));
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.jv__capture')).toBeNull();
    void fixture.componentInstance.talk();
    flushMicrotasks();
    ready.stt().onCommitted('Où en est Cagibi ?');
    flushMicrotasks();
    expect(jarvisApi.capture).not.toHaveBeenCalled();
  }));

  it('F-239 / SF-239-03 — ADMIN : l’interrupteur existe ; allumé, l’énoncé part au banc avec ses deux textes',
    fakeAsync(() => {
      TestBed.overrideProvider(AuthService, { useValue: { isAdmin: () => true } });
      const ready = streamWithAudio();
      jarvisApi.preferences.and.returnValue(of({ humour: 'LIGHT' as const, captureAudio: false }));
      jarvisApi.setCaptureAudio.and.returnValue(of({ humour: 'LIGHT' as const, captureAudio: true }));
      jarvisApi.utterance.and.returnValue(of({ text: 'Où en est CAGIP ?', provider: 'elevenlabs-batch', ms: 600 }));
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      expect((fixture.nativeElement as HTMLElement).querySelector('.jv__capture')).not.toBeNull();

      void jarvis.talk();
      flushMicrotasks();
      ready.stt().onCommitted('Où en est Cagibi ?');
      flushMicrotasks();
      expect(jarvisApi.capture).not.toHaveBeenCalled();

      jarvis.toggleCapture();
      expect(jarvisApi.setCaptureAudio).toHaveBeenCalledWith(true);
      expect(jarvis.captureAudio()).toBeTrue();
      jarvis.state.set('repos');
      void jarvis.talk();
      flushMicrotasks();
      ready.stt().onCommitted('Où en est Cagibi ?');
      flushMicrotasks();
      expect(jarvisApi.capture).toHaveBeenCalledWith(jasmine.any(Blob), jasmine.objectContaining({
        listenMode: 'STREAM', provider: 'elevenlabs-batch', streamText: 'Où en est Cagibi ?',
        finalText: 'Où en est CAGIP ?', seconds: 2 }));
    }));

  it('F-239 / SF-239-10 — phrase en suspens : pas de fin de tour au premier segment, la suite est jointe',
    fakeAsync(() => {
      const ready = streamWithAudio();
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      void jarvis.talk();
      flushMicrotasks();

      ready.stt().onCommitted('Allons chez le client et donnez-');
      flushMicrotasks();
      tick(1_400);
      expect(jarvis.state()).toBe('ecoute');
      expect(atelier.streamChat).not.toHaveBeenCalled();

      ready.stt().onPartial('moi l’état');
      ready.stt().onCommitted('moi l’état des tickets.');
      flushMicrotasks();
      expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Allons chez le client et donnez- moi l’état des tickets.',
        jasmine.any(Object), 'ACT', false, [], null, false, 'JARVIS', null, false);
    }));

  it('F-239 / SF-239-10 — phrase en suspens puis silence : la fin vient 1,5 s plus tard', fakeAsync(() => {
    const ready = streamWithAudio();
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    void jarvis.talk();
    flushMicrotasks();

    ready.stt().onCommitted('Fais le point sur');
    flushMicrotasks();
    tick(1_499);
    expect(atelier.streamChat).not.toHaveBeenCalled();
    tick(1);
    flushMicrotasks();
    expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Fais le point sur', jasmine.any(Object), 'ACT', false, [],
      null, false, 'JARVIS', null, false);
  }));

  it('F-239 / SF-239-04 — flux : retranscription trop lente (> 2,5 s) ⇒ le texte du flux part', fakeAsync(() => {
    const ready = streamWithAudio();
    jarvisApi.utterance.and.returnValue(new Subject<never>());
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    void jarvis.talk();
    flushMicrotasks();
    ready.stt().onCommitted('Où en est la migration ?');
    flushMicrotasks();
    expect(atelier.streamChat).not.toHaveBeenCalled();
    tick(2_500);
    flushMicrotasks();
    expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Où en est la migration ?', jasmine.any(Object), 'ACT',
      false, [], null, false, 'JARVIS', null, false);
  }));

  it('F-239 / SF-239-04 — dictée et interruption passent par la retranscription, jamais par la dictée (whisper)',
    fakeAsync(() => {
      jarvisApi.utterance.and.returnValue(of({ text: 'Regarde LDIC-223.', provider: 'elevenlabs-batch', ms: 500 }));
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      void jarvis.talk();
      flushMicrotasks();
      void jarvis.talk();
      flushMicrotasks();
      expect(dictation.stopRecording).toHaveBeenCalled();
      expect(dictation.transcribe).not.toHaveBeenCalled();
      expect(dictation.stop).not.toHaveBeenCalled();
      expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Regarde LDIC-223.', jasmine.any(Object), 'ACT', false,
        [], null, false, 'JARVIS', null, false);
      jarvis.ngOnDestroy();
    }));

  it('F-233 / SF-233-05 — flux non configuré (503) : dictée batch, et on n’essaie plus pendant l’ouverture',
    fakeAsync(() => {
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      void jarvis.talk();
      flushMicrotasks();
      expect(dictation.start).toHaveBeenCalledWith(jasmine.objectContaining({ echoCancellation: true }));
      jarvis.state.set('repos');
      void jarvis.talk();
      flushMicrotasks();
      expect(jarvisApi.sttSession).toHaveBeenCalledTimes(1);
    }));

  it('F-239 / SF-239-04 — la parole qui coupe Jarvis est retranscrite par Jarvis, pas par la dictée', fakeAsync(() => {
    voice.interrupt.and.returnValue({ heard: 'La migration est', resume: [] });
    jarvisApi.utterance.and.returnValue(of({ text: 'Et les tests de CAGIP ?', provider: 'elevenlabs-batch', ms: 400 }));
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    jarvis.state.set('parole');
    fixture.detectChanges();
    flushMicrotasks();
    vad!.onSpeechStart();
    vad!.onSpeechConfirmed();
    vad!.onSpeechEnd(new Float32Array(16_000));
    flushMicrotasks();
    expect(jarvisApi.utterance).toHaveBeenCalledWith(jasmine.any(Blob), 1);
    expect(dictation.transcribe).not.toHaveBeenCalled();
    expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Et les tests de CAGIP ?', jasmine.any(Object), 'ACT',
      false, [], null, false, 'JARVIS', 'La migration est', false);
    jarvis.ngOnDestroy();
  }));

  it('F-233 / SF-233-08 — Jarvis parle : voix détectée → volume baissé ; confirmée → coupé, et ce qui est dit est traité',
    fakeAsync(() => {
      voice.interrupt.and.returnValue({ heard: 'La migration est', resume: ['La migration est à l’étape 4.'] });
      dictation.transcribe.and.resolveTo('Et les tests ?');
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      jarvis.state.set('parole');
      fixture.detectChanges();
      flushMicrotasks();
      expect(vadService.start).toHaveBeenCalled();

      vad!.onSpeechStart();
      expect(voice.duck).toHaveBeenCalledWith(true);
      vad!.onSpeechConfirmed();
      expect(voice.interrupt).toHaveBeenCalled();
      expect(jarvis.state()).toBe('ecoute');
      fixture.detectChanges();
      expect(vadStop).not.toHaveBeenCalled();

      vad!.onSpeechEnd(new Float32Array(8000));
      flushMicrotasks();
      // F-244 / SF-244-01 — la dictée de repli appelée depuis /jarvis porte la marque JARVIS (budget de Jarvis).
      expect(dictation.transcribe).toHaveBeenCalledWith(jasmine.any(Blob), jasmine.any(String), 'JARVIS');
      expect(vadStop).toHaveBeenCalled();
      expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Et les tests ?', jasmine.any(Object), 'ACT', false, [],
        null, false, 'JARVIS', 'La migration est', false);
      jarvis.ngOnDestroy();
    }));

  it('F-233 / SF-233-08 — une fausse alerte rend le volume ; une coupure sans mots compte un faux déclenchement',
    fakeAsync(() => {
      dictation.transcribe.and.resolveTo('');
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      jarvis.state.set('parole');
      fixture.detectChanges();
      flushMicrotasks();
      vad!.onSpeechStart();
      vad!.onMisfire();
      expect(voice.duck).toHaveBeenCalledWith(false);
      expect(voice.interrupt).not.toHaveBeenCalled();

      vad!.onSpeechStart();
      vad!.onSpeechConfirmed();
      vad!.onSpeechEnd(new Float32Array(1600));
      flushMicrotasks();
      expect(atelier.streamChat).not.toHaveBeenCalled();
      const report = jarvisApi.timings.calls.mostRecent().args[0];
      expect(report.falseTriggers).toBe(1);
      expect(report.interruptionsConfirmed).toBe(1);
      expect(report.interruptionsRestored).toBe(1);
      jarvis.ngOnDestroy();
    }));

  function cutWhileSpeaking(said: string): JarvisComponent {
    voice.interrupt.and.returnValue({ heard: 'La migration', resume: ['La migration est à l’étape 4.', 'Rien ne bloque.'] });
    dictation.transcribe.and.resolveTo(said);
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    jarvis.state.set('parole');
    fixture.detectChanges();
    flushMicrotasks();
    vad!.onSpeechStart();
    vad!.onSpeechConfirmed();
    fixture.detectChanges();
    vad!.onSpeechEnd(new Float32Array(4000));
    flushMicrotasks();
    return jarvis;
  }

  it('F-233 / SF-233-09 — « mmh » : il reprend à la phrase coupée, sans rien envoyer', fakeAsync(() => {
    const jarvis = cutWhileSpeaking('Mmh.');
    expect(atelier.streamChat).not.toHaveBeenCalled();
    expect(voice.enqueue.calls.allArgs().map((a) => a[0])).toEqual(['La migration est à l’étape 4.', 'Rien ne bloque.']);
    expect(jarvisApi.timings.calls.mostRecent().args[0].acknowledgements).toBe(1);
    jarvis.ngOnDestroy();
  }));

  it('F-233 / SF-233-09 — « chut » : il se tait, et rien d’autre', fakeAsync(() => {
    const jarvis = cutWhileSpeaking('Chut !');
    expect(atelier.streamChat).not.toHaveBeenCalled();
    expect(voice.enqueue).not.toHaveBeenCalled();
    expect(atelier.interruptChat).not.toHaveBeenCalled();
    expect(jarvis.state()).toBe('repos');
    jarvis.ngOnDestroy();
  }));

  // ---------------------------------------------------------------- F-235 / SF-235-05 : deux cerveaux

  function fastOn(): JarvisComponent {
    fastBrain.warm.and.resolveTo(true);
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    return fixture.componentInstance;
  }

  it('F-235 / SF-235-05 — cerveau rapide : la réponse est dite phrase par phrase, aucun tour Opus', fakeAsync(() => {
    const jarvis = fastOn();
    expect(jarvis.fastBrainOn()).toBeTrue();

    void jarvis.handle('Où en est le chantier 3 ?');
    flushMicrotasks();
    expect(fastBrain.converse).toHaveBeenCalledWith(
      jasmine.objectContaining({ subjectId: 'w-2', text: 'Où en est le chantier 3 ?' }), jasmine.any(Object));
    fast!.onText('Le chantier 3 en est aux tests.');
    fast!.onDone({ brain: 'FAST', spokenText: 'Le chantier 3 en est aux tests.', firstTokenMs: 300, model: 'm' });
    fastDone!('ok');
    flushMicrotasks();

    expect(voice.enqueue.calls.mostRecent().args.slice(0, 2)).toEqual(['Le chantier 3 en est aux tests.', 'w-2']);
    expect(atelier.streamChat).not.toHaveBeenCalled();
    expect(jarvis.state()).toBe('repos');
    jarvis.ngOnDestroy();
  }));

  it('F-241 / SF-241-04 — une ligne-marque ✦ n’est jamais dite ; présentation transmise ; allusion mesurée', fakeAsync(() => {
    const jarvis = fastOn();
    void jarvis.handle('Bonjour Jarvis');
    flushMicrotasks();
    expect(fastBrain.converse.calls.mostRecent().args[0].presentation).toBeFalse();
    fast!.onText('✦ allusion: le petit');
    fast!.onText('Bonjour, Monsieur.');
    fast!.onDone({ brain: 'FAST', spokenText: 'Bonjour, Monsieur.', firstTokenMs: 300, model: 'm',
      guards: { groundingTrips: 0, fillersRemoved: 0, repeatsRemoved: 0, forcedDelegation: false, toolText: false,
        imitationsRemoved: 0, portraitBlocked: 0, personalAllusion: 'SPONTANEE', allusionsBlocked: 0 }, portrait: true });
    fastDone!('ok');
    flushMicrotasks();
    expect(voice.enqueue).toHaveBeenCalledTimes(1);
    expect(voice.enqueue.calls.mostRecent().args[0]).toBe('Bonjour, Monsieur.');
    expect(jarvis.spoken()).toBe('Bonjour, Monsieur.');
    jarvis.ngOnDestroy();
  }));

  function fastAnswer(jarvis: JarvisComponent, said: string, spoken: string, pins: PortraitPin[]): void {
    void jarvis.handle(said);
    flushMicrotasks();
    fast!.onText(spoken);
    fast!.onDone({ brain: 'FAST', spokenText: spoken, firstTokenMs: 300, model: 'm',
      guards: { groundingTrips: 0, fillersRemoved: 0, repeatsRemoved: 0, forcedDelegation: false, toolText: false,
        imitationsRemoved: 0, pins } });
    fastDone!('ok');
    flushMicrotasks();
  }

  it('F-241 / SF-241-05 — proposition : rien sans « oui » ; « oui » enregistre (voix) et le dit', fakeAsync(() => {
    const jarvis = fastOn();
    portraitClient.addSouvenir.and.returnValue(of({ id: 's-9', number: 3, text: 'Sa compagne accouche en mars',
      sensitive: false, notedOn: '2026-11-02', source: 'VOIX', stale: false }));
    const said: string[] = [];
    spyOn(jarvis, 'say').and.callFake(async (p: string) => { said.push(p); });
    fastAnswer(jarvis, 'Ma femme accouche en mars.', 'Voulez-vous que je retienne que votre compagne accouche en mars ?',
      [{ action: 'retenir', text: 'Sa compagne accouche en mars', number: null }]);
    expect(portraitClient.addSouvenir).not.toHaveBeenCalled();
    const conversed = fastBrain.converse.calls.count();
    void jarvis.handle('oui');
    flushMicrotasks();
    expect(portraitClient.addSouvenir).toHaveBeenCalledWith('Sa compagne accouche en mars', false, 'VOIX');
    expect(said).toContain('C’est noté.');
    expect(fastBrain.converse.calls.count()).toBe(conversed); // le « oui » ne repart pas au cerveau
    jarvis.ngOnDestroy();
  }));

  it('F-241 / SF-241-05 — « retiens que… » enregistre aussitôt ; « non, oublie ça » l’annule ; « oublie que… » efface le bon', fakeAsync(() => {
    const jarvis = fastOn();
    portraitClient.addSouvenir.and.returnValue(of({ id: 's-7', number: 2, text: 'Vacances le 20 décembre',
      sensitive: false, notedOn: '2026-11-02', source: 'VOIX', stale: false }));
    portraitClient.deleteSouvenir.and.returnValue(of(undefined));
    spyOn(jarvis, 'say').and.callFake(async () => undefined);
    fastAnswer(jarvis, 'Retiens que je pars en vacances le 20 décembre.', 'C’est noté, Monsieur.',
      [{ action: 'noter', text: 'Vacances le 20 décembre', number: null }]);
    expect(portraitClient.addSouvenir).toHaveBeenCalledWith('Vacances le 20 décembre', false, 'VOIX');
    void jarvis.handle('Non, oublie ça.');
    flushMicrotasks();
    expect(portraitClient.deleteSouvenir).toHaveBeenCalledWith('s-7');

    portraitClient.get.and.returnValue(of({ exists: true, enabled: true, version: 4, sections: [],
      souvenirs: [{ id: 's-1', number: 1, text: 'Café serré', sensitive: false, notedOn: '2026-10-01', source: 'ECRAN', stale: false },
        { id: 's-2', number: 2, text: 'Semi-marathon de Lyon', sensitive: false, notedOn: '2026-10-01', source: 'ECRAN', stale: false }],
      size: { chars: 30, tokens: 8, maxTokens: 1500, maxChars: 6000 }, lastInterviewAt: null, interviewOffers: 0,
      blockedTerms: [] }));
    fastAnswer(jarvis, 'Oublie que je prépare le semi-marathon.', 'C’est oublié.',
      [{ action: 'oublier', text: null, number: 2 }]);
    expect(portraitClient.deleteSouvenir).toHaveBeenCalledWith('s-2');
    jarvis.ngOnDestroy();
  }));

  it('F-241 / SF-241-05 — mémoire pleine : dite, rien enregistré', fakeAsync(() => {
    const jarvis = fastOn();
    portraitClient.addSouvenir.and.returnValue(throwError(() => ({ status: 409 })));
    const said: string[] = [];
    spyOn(jarvis, 'say').and.callFake(async (p: string) => { said.push(p); });
    fastAnswer(jarvis, 'Retiens que j’aime le thé.', 'C’est noté, Monsieur.',
      [{ action: 'noter', text: 'Aime le thé', number: null }]);
    expect(said).toContain('Ma mémoire est pleine, faites le tri dans les réglages.');
    jarvis.ngOnDestroy();
  }));

  it('F-239 / SF-239-01 — cerveau rapide lent : aucune formule d’attente, seulement la réponse', fakeAsync(() => {
    voice.hasClip.and.returnValue(true);
    voice.playClip.and.resolveTo(true);
    const jarvis = fastOn();
    void jarvis.handle('Où en est le chantier 3 sur CAGIP ?');
    flushMicrotasks();
    tick(8_000);
    expect(voice.playClip).not.toHaveBeenCalled();
    expect(voice.speak).not.toHaveBeenCalled();
    expect(voice.enqueue).not.toHaveBeenCalled();
    fast!.onText('Le chantier 3 en est aux tests.');
    fast!.onDone({ brain: 'FAST', spokenText: 'Le chantier 3 en est aux tests.', firstTokenMs: 8_000, model: 'm' });
    fastDone!('ok');
    flushMicrotasks();
    expect(voice.enqueue.calls.allArgs().map((a) => a[0])).toEqual(['Le chantier 3 en est aux tests.']);
    jarvis.ngOnDestroy();
  }));

  it('F-239 / SF-239-01 — délégation sans sujet : il demande le sujet ; annonce vide : silence, le tour part',
    fakeAsync(() => {
      const jarvis = fastOn();
      void jarvis.handle('Regarde les tickets');
      flushMicrotasks();
      fast!.onDelegated({ delegationId: 'd-0', subjectId: null, chantier: null, label: 'x',
        announce: 'Je vérifie, Monsieur.', demande: 'x', automatic: true, reason: 'grounding' });
      fastDone!('ok');
      flushMicrotasks();
      expect(voice.enqueue.calls.allArgs().map((a) => a[0])).toEqual([ASK_SUBJECT]);
      expect(atelier.streamChat).not.toHaveBeenCalled();

      voice.enqueue.calls.reset();
      void jarvis.handle('Regarde les tickets d’AGENOR');
      flushMicrotasks();
      fast!.onDelegated({ delegationId: 'd-1', subjectId: 'w-1', chantier: null, label: 'tickets', announce: '',
        demande: 'x', automatic: true, reason: 'grounding' });
      fastDone!('ok');
      flushMicrotasks();
      expect(voice.enqueue).not.toHaveBeenCalled();
      expect(atelier.streamChat).toHaveBeenCalled();
      jarvis.ngOnDestroy();
    }));

  it('F-239 / SF-239-11 — budget de conversation dépassé : signalé une fois, jamais d’envoi à Opus', fakeAsync(() => {
    const jarvis = fastOn();
    for (let i = 0; i < 2; i++) {
      void jarvis.handle('Où en est le chantier 3 ?');
      flushMicrotasks();
      fast!.onText('Le chantier 3 en est aux tests.');
      fast!.onDone({ brain: 'FAST', spokenText: 'Le chantier 3 en est aux tests.', firstTokenMs: 300, model: 'm',
        budget: 'soft' });
      fastDone!('ok');
      flushMicrotasks();
      expect(jarvis.notice()).toBe(i === 0 ? BUDGET_SOFT_NOTICE : null);
      jarvis.notice.set(null);
    }
    expect(atelier.streamChat).not.toHaveBeenCalled();
    jarvis.ngOnDestroy();
  }));

  /** Une écoute en flux prête : le ticket, et ce que l'écran a donné à l'ouverture. */
  function streamReady(): { stt: () => StreamHandlers; preRoll: () => (() => Float32Array | null) | null | undefined } {
    jarvisApi.sttSession.and.returnValue(of({
      sessionId: 's-1', provider: 'elevenlabs', url: 'wss://x/realtime?model_id=m', token: 't',
      expiresAt: '2030-01-01T00:00:00Z', sampleRate: 16000, maxSeconds: 120,
    }));
    let stt: StreamHandlers | null = null;
    let preRoll: (() => Float32Array | null) | null | undefined;
    const listening = jasmine.createSpyObj('LiveListening', ['commit', 'close'], { stream: undefined });
    listening.close.and.returnValue(2);
    opener.open.and.callFake((_t, h, p) => {
      stt = h;
      preRoll = p;
      return Promise.resolve(listening);
    });
    return { stt: () => stt!, preRoll: () => preRoll };
  }

  /** Un réveil sur l'appareil, avec son contexte. */
  function wakeEvent(precededBySpeech: boolean, speechAfter = false): WakeEvent {
    return { precededBySpeech, speechAfter: () => speechAfter, takeAudio: () => new Float32Array(1_600) };
  }

  it('F-239 / SF-239-01 — « Bonjour Jarvis » : ni formule de réveil ni d’attente, seulement la réponse', fakeAsync(() => {
    voice.hasClip.and.returnValue(true);
    voice.playClip.and.resolveTo(true);
    const ready = streamReady();
    const jarvis = fastOn();
    jarvis.state.set('repos');

    // Le moteur a reconnu « Jarvis » juste après « Bonjour » : la phrase est donnée d'abord à l'écoute en flux.
    void (jarvis as unknown as { onWake(e: WakeEvent): Promise<void> }).onWake(wakeEvent(true));
    flushMicrotasks();
    expect(jarvis.state()).toBe('ecoute');
    expect(ready.preRoll()).toBeTruthy();
    expect(ready.preRoll()!()!.length).toBe(1_600);
    ready.stt().onCommitted('Bonjour Jarvis.');
    flushMicrotasks();
    expect(fastBrain.converse).toHaveBeenCalledWith(jasmine.objectContaining({ text: 'Bonjour Jarvis.' }),
      jasmine.any(Object));
    tick(3_000);
    fast!.onText('Bonjour, Monsieur.');
    fast!.onDone({ brain: 'FAST', spokenText: 'Bonjour, Monsieur.', firstTokenMs: 2_900, model: 'm' });
    fastDone!('ok');
    flushMicrotasks();

    expect(voice.playClip).not.toHaveBeenCalled();
    expect(voice.speak).not.toHaveBeenCalled();
    expect(voice.enqueue.calls.allArgs().map((a) => a[0])).toEqual(['Bonjour, Monsieur.']);
    expect(atelier.streamChat).not.toHaveBeenCalled();
    jarvis.ngOnDestroy();
  }));

  it('F-239 / SF-239-01 — « Jarvis, … » qui continue : aucune formule ; « Jarvis » seul : une formule courte, jamais « Je vous écoute »',
    fakeAsync(() => {
      voice.hasClip.and.returnValue(true);
      voice.playClip.and.resolveTo(true);
      streamReady();
      const jarvis = fastOn();
      const wake = (e: WakeEvent) => (jarvis as unknown as { onWake(e: WakeEvent): Promise<void> }).onWake(e);

      void wake(wakeEvent(false, true));
      tick(600);
      flushMicrotasks();
      expect(voice.playClip).not.toHaveBeenCalled();
      expect(jarvis.state()).toBe('ecoute');

      jarvis.state.set('repos');
      jarvis.conversation.rest();
      void wake(wakeEvent(false, false));
      tick(600);
      flushMicrotasks();
      expect(voice.playClip).toHaveBeenCalledTimes(1);
      const clip = voice.playClip.calls.mostRecent().args[0];
      expect(['oui', 'oui-court', 'dites-moi', 'allez-y']).toContain(clip);
      jarvis.ngOnDestroy();
    }));

  it('F-239 / SF-239-01 — tour fantôme : « Sous-titres… » ou « Merci. » sans parole ⇒ aucun tour, mesure sans texte',
    fakeAsync(() => {
      const ready = streamReady();
      const jarvis = fastOn();
      for (const ghost of ['Sous-titres réalisés par la communauté d’Amara.org', 'Merci.', '(musique)']) {
        jarvis.state.set('repos');
        void jarvis.talk();
        flushMicrotasks();
        expect(jarvis.state()).toBe('ecoute');
        ready.stt().onCommitted(ghost);
        flushMicrotasks();
        const report = jarvisApi.timings.calls.mostRecent().args[0];
        expect(report.falseTriggers).toBe(1);
        expect(report.rejectedKind).toBe('hallucination');
        expect(report.rejectedChars).toBe(ghost.length);
        expect(JSON.stringify(report)).not.toContain(ghost);
      }
      expect(fastBrain.converse).not.toHaveBeenCalled();
      expect(atelier.streamChat).not.toHaveBeenCalled();
      jarvis.ngOnDestroy();
    }));

  it('F-239 / SF-239-05 — ce que le cerveau rapide a compris s’affiche, et c’est ce qu’Opus reçoit', fakeAsync(() => {
    const jarvis = fastOn();
    void jarvis.handle('Regarde les tickets bloqués de Cagibi');
    flushMicrotasks();
    fast!.onUnderstood!('Regarde les tickets bloqués de CAGIP.');
    expect(jarvis.said()).toBe('Regarde les tickets bloqués de CAGIP.');
    fast!.onDelegated({ delegationId: 'd-1', subjectId: 'w-2', chantier: null, label: 'tickets bloqués de CAGIP',
      announce: 'Je regarde les tickets bloqués de CAGIP.', demande: 'x', automatic: false, reason: null });
    fastDone!('ok');
    flushMicrotasks();
    expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Regarde les tickets bloqués de CAGIP.',
      jasmine.any(Object), 'ACT', false, [], null, false, 'JARVIS', null, false);
    jarvis.ngOnDestroy();
  }));

  it('F-245 / SF-245-02 — une délégation au fil Jarvis part dans ce fil sans changer le sujet en vue', fakeAsync(() => {
    const jarvis = fastOn();
    const before = jarvis.subjectId();
    void jarvis.handle('Fais-moi un tour d’horizon de mes clients');
    flushMicrotasks();
    fast!.onDelegated({ delegationId: 'd-1', subjectId: 'jt', chantier: null, label: 'tour d’horizon',
      announce: 'Je fais le tour de vos clients, Monsieur.', demande: 'x', automatic: false, reason: null,
      jarvisThread: true });
    fastDone!('ok');
    flushMicrotasks();
    expect(atelier.streamChat).toHaveBeenCalledWith('jt', 'Fais-moi un tour d’horizon de mes clients',
      jasmine.any(Object), 'ACT', false, [], null, false, 'JARVIS', null, false);
    expect(jarvis.subjectId()).toBe(before);
    jarvis.ngOnDestroy();
  }));

  it('F-235 / SF-235-05 — délégation : annonce dite, tour Opus dans le fil cible, 🎙 intermédiaires muettes, fin annoncée avec son label',
    fakeAsync(() => {
      const jarvis = fastOn();
      void jarvis.handle('Regarde les tickets bloqués d’AGENOR');
      flushMicrotasks();
      fast!.onDelegated({
        delegationId: 'd-1', subjectId: 'w-1', chantier: null, label: 'tickets bloqués d’AGENOR',
        announce: 'Je regarde les tickets bloqués d’AGENOR, Monsieur.', demande: 'x', automatic: false, reason: null,
      });
      fastDone!('ok');
      flushMicrotasks();

      expect(voice.enqueue.calls.mostRecent().args.slice(0, 2))
        .toEqual(['Je regarde les tickets bloqués d’AGENOR, Monsieur.', 'w-2']);
      expect(atelier.streamChat).toHaveBeenCalledWith('w-1', 'Regarde les tickets bloqués d’AGENOR',
        jasmine.any(Object), 'ACT', false, [], null, false, 'JARVIS', null, false);
      expect(jarvis.delegations().map((d) => d.label)).toEqual(['tickets bloqués d’AGENOR']);

      voice.enqueue.calls.reset();
      handlers!.onText('🎙 Je lance la recherche Jira.');
      expect(voice.enqueue).not.toHaveBeenCalled();

      handlers!.onDone({ reply: '🎙 Deux tickets sont bloqués.\n\nDétail…', actions: [], messageId: 'm-1' });
      flushMicrotasks();
      expect(voice.speak).toHaveBeenCalledWith('Pour tickets bloqués d’AGENOR : Deux tickets sont bloqués.', 'w-1',
        jasmine.any(Object));
      expect(jarvis.delegations()).toEqual([]);
      jarvis.ngOnDestroy();
    }));

  it('F-235 / SF-235-05 — deux fins proches sont dites dans l’ordre de fin, jamais pendant une autre phrase',
    fakeAsync(() => {
      const jarvis = fastOn();
      const streams: AtelierStreamHandlers[] = [];
      atelier.streamChat.and.callFake((_id: string, _text: string, h: AtelierStreamHandlers) => {
        streams.push(h);
        return Promise.resolve();
      });
      for (const [id, label] of [['d-1', 'premier'], ['d-2', 'second']]) {
        void jarvis.handle(`Lance ${label}`);
        flushMicrotasks();
        fast!.onDelegated({ delegationId: id, subjectId: 'w-1', chantier: null, label, announce: `Je lance ${label}.`,
          demande: label, automatic: false, reason: null });
        fastDone!('ok');
        flushMicrotasks();
      }
      let release: () => void = () => undefined;
      voice.speak.and.callFake(() => new Promise<void>((resolve) => release = resolve));
      streams[1].onDone({ reply: '🎙 Second fini.', actions: [], messageId: 'm-2' });
      streams[0].onDone({ reply: '🎙 Premier fini.', actions: [], messageId: 'm-1' });
      flushMicrotasks();
      expect(voice.speak.calls.allArgs().map((a) => a[0])).toEqual(['Pour second : Second fini.']);
      release();
      flushMicrotasks();
      expect(voice.speak.calls.allArgs().map((a) => a[0])).toEqual(['Pour second : Second fini.',
        'Pour premier : Premier fini.']);
      release();
      flushMicrotasks();
      jarvis.ngOnDestroy();
    }));

  it('F-235 / SF-235-05 — cerveau rapide éteint en route (503) ou repli : exactement le mode actuel', fakeAsync(() => {
    const jarvis = fastOn();
    void jarvis.handle('Bonjour');
    flushMicrotasks();
    fastDone!('unavailable');
    flushMicrotasks();
    expect(jarvis.fastBrainOn()).toBeFalse();
    expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Bonjour', jasmine.any(Object), 'ACT', false, [], null,
      false, 'JARVIS', null, false);
    jarvis.ngOnDestroy();
  }));

  it('F-235 / SF-235-05 — une confirmation attendue va directement à Opus, sans cerveau rapide', fakeAsync(() => {
    const jarvis = fastOn();
    void jarvis.handle('Envoie le compte rendu');
    flushMicrotasks();
    fast!.onDelegated({ delegationId: 'd-1', subjectId: 'w-1', chantier: null, label: 'compte rendu',
      announce: 'Je prépare l’envoi du compte rendu.', demande: 'x', automatic: false, reason: null });
    fastDone!('ok');
    flushMicrotasks();
    handlers!.onDone({ reply: '🎙 J’envoie le compte rendu à Paul. Vous confirmez ?', actions: [], messageId: 'm' });
    flushMicrotasks();
    fastBrain.converse.calls.reset();
    atelier.streamChat.calls.reset();

    void jarvis.handle('oui');
    flushMicrotasks();
    expect(fastBrain.converse).not.toHaveBeenCalled();
    expect(atelier.streamChat).toHaveBeenCalledWith('w-1', 'oui', jasmine.any(Object), 'ACT', false, [], null, false,
      'JARVIS', null, false);
    jarvis.ngOnDestroy();
  }));

  it('F-235 / SF-235-05 — « annule » arrête la dernière délégation vivante et le dit', fakeAsync(() => {
    const jarvis = fastOn();
    void jarvis.handle('Regarde les tickets');
    flushMicrotasks();
    fast!.onDelegated({ delegationId: 'd-1', subjectId: 'w-1', chantier: null, label: 'tickets bloqués',
      announce: 'Je regarde les tickets bloqués.', demande: 'x', automatic: false, reason: null });
    fastDone!('ok');
    flushMicrotasks();

    void jarvis.handle('annule');
    flushMicrotasks();
    expect(atelier.interruptChat).toHaveBeenCalledWith('w-1');
    expect(voice.speak).toHaveBeenCalledWith('J’arrête la recherche sur tickets bloqués.', 'w-1');
    expect(jarvis.delegations()).toEqual([]);
    jarvis.ngOnDestroy();
  }));

  it('F-235 / SF-235-05 (D19) — aucun sujet présélectionné ; sans sujet, le mode actuel demande lequel', fakeAsync(() => {
    configure({});
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    flushMicrotasks();
    const jarvis = fixture.componentInstance;
    expect(jarvis.subjectId()).toBeNull();

    void jarvis.handle('Où en est la migration ?');
    flushMicrotasks();
    expect(atelier.streamChat).not.toHaveBeenCalled();
    expect(voice.speak).toHaveBeenCalledWith('Pour quel client ou quel sujet, Monsieur ?', null);
    jarvis.ngOnDestroy();
  }));

  it('F-235 / SF-235-07 — la mesure du tour porte le cerveau et les garde-fous ; une correction est comptée',
    fakeAsync(() => {
      const jarvis = fastOn();
      dictation.stop.and.resolveTo('Où en est le chantier 3 ?');
      void jarvis.talk();
      flushMicrotasks();
      void jarvis.talk();
      flushMicrotasks();
      fast!.onText('Le chantier 3 en est aux tests.');
      fast!.onDone({ brain: 'FAST', spokenText: 'x', firstTokenMs: 420, model: 'claude-haiku-5-5',
        guards: { groundingTrips: 0, fillersRemoved: 1, repeatsRemoved: 0, forcedDelegation: false, toolText: false,
          imitationsRemoved: 0 } });
      fastDone!('ok');
      flushMicrotasks();
      jarvis['flushParked']();
      const first = jarvisApi.timings.calls.mostRecent().args[0];
      expect(first.brain).toBe('FAST');
      expect(first.fastModel).toBe('claude-haiku-5-5');
      expect(first.fastFirstTokenMs).toBe(420);
      expect(first.fillersRemoved).toBe(1);
      expect(first.spokenWords).toBe(7); // F-240 / SF-240-01 — « Le chantier 3 en est aux tests. »
      expect(first.correction).toBeUndefined();
      expect(JSON.stringify(first)).not.toContain('chantier 3');

      dictation.stop.and.resolveTo('Non, c’est faux');
      void jarvis.talk();
      flushMicrotasks();
      void jarvis.talk();
      flushMicrotasks();
      fast!.onDelegated({ delegationId: 'd', subjectId: 'w-2', chantier: null, label: '', announce: 'Je vérifie, Monsieur.',
        demande: 'x', automatic: true, reason: 'forced' });
      fast!.onDone({ brain: 'FAST_DELEGATED', spokenText: '', firstTokenMs: -1, model: 'm',
        guards: { groundingTrips: 0, fillersRemoved: 0, repeatsRemoved: 0, forcedDelegation: true, toolText: false,
          imitationsRemoved: 0 } });
      fastDone!('ok');
      flushMicrotasks();
      // F-240 / SF-240-01 — la mesure d'un échange délégué attend la fin de sa délégation (ici : la sortie de l'écran).
      const calls = jarvisApi.timings.calls.count();
      jarvis.ngOnDestroy();
      expect(jarvisApi.timings.calls.count()).toBe(calls + 1);
      const second = jarvisApi.timings.calls.mostRecent().args[0];
      expect(second.brain).toBe('FAST_DELEGATED');
      expect(second.correction).toBeTrue();
      expect(second.forcedDelegation).toBeTrue();
      expect(second.delegationReason).toBe('forced');
      expect(second.fastFirstTokenMs).toBeNull();
      expect(second.announceWords).toBe(3);
      expect(second.spokenWords).toBeUndefined();
    }));

  it('F-240 / SF-240-01 — tour Opus direct : la réponse dite et l’annonce avant outils sont comptées à part',
    fakeAsync(() => {
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      dictation.stop.and.resolveTo('Où en est la migration ?');
      void jarvis.talk();
      flushMicrotasks();
      void jarvis.talk();
      flushMicrotasks();
      handlers!.onText('🎙 Je regarde la migration.\n');
      handlers!.onText('🎙 La migration est à l’étape 4. ');
      handlers!.onDone({ reply: '🎙 La migration est à l’étape 4.\n\nDétail…', actions: [], messageId: 'm-1' });
      flushMicrotasks();
      jarvis['flushParked']();
      const report = jarvisApi.timings.calls.mostRecent().args[0];
      expect(report.spokenWords).toBe(6);
      expect(report.announceWords).toBe(4);
      expect(report.spokenChars).toBe('La migration est à l’étape 4.'.length);
      jarvis.ngOnDestroy();
    }));

  it('F-240 / SF-240-01 — échange délégué : la mesure attend la fin dite de la délégation (annonce à part)',
    fakeAsync(() => {
      const jarvis = fastOn();
      dictation.stop.and.resolveTo('Regarde les tickets bloqués d’AGENOR');
      void jarvis.talk();
      flushMicrotasks();
      void jarvis.talk();
      flushMicrotasks();
      fast!.onDelegated({
        delegationId: 'd-9', subjectId: 'w-1', chantier: null, label: 'tickets bloqués d’AGENOR',
        announce: 'Je regarde les tickets bloqués d’AGENOR, Monsieur.', demande: 'x', automatic: false, reason: null,
      });
      fast!.onDone({ brain: 'FAST_DELEGATED', spokenText: '', firstTokenMs: 300, model: 'm' });
      fastDone!('ok');
      flushMicrotasks();
      expect(jarvisApi.timings).not.toHaveBeenCalled();
      handlers!.onDone({ reply: '🎙 Deux tickets sont bloqués.\n\nDétail…', actions: [], messageId: 'm-1' });
      flushMicrotasks();
      jarvis['flushParked']();
      expect(jarvisApi.timings).toHaveBeenCalledTimes(1);
      const report = jarvisApi.timings.calls.mostRecent().args[0];
      expect(report.brain).toBe('FAST_DELEGATED');
      expect(report.announceWords).toBe(7);
      expect(report.spokenWords).toBe(4);
      jarvis.ngOnDestroy();
    }));

  it('F-240 / SF-240-02 — l’invitation ciblée est dite après la phrase, masquée du détail, et mesurée',
    fakeAsync(() => {
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      dictation.stop.and.resolveTo('Où en est le déploiement ?');
      void jarvis.talk();
      flushMicrotasks();
      void jarvis.talk();
      flushMicrotasks();
      handlers!.onDone({ reply: '🎙 Le déploiement est terminé, Monsieur.\n↪ Il y a aussi un point sur Helios, je '
        + 'vous le dis ?\n<<essentiel>>\n- Déployé\n<</essentiel>>\nDétail…', actions: [], messageId: 'm-1' });
      flushMicrotasks();
      const said = voice.speak.calls.allArgs().map((a) => a[0]);
      expect(said).toEqual(['Le déploiement est terminé, Monsieur.', 'Il y a aussi un point sur Helios, je vous le dis ?']);
      expect(jarvis.detail()).not.toContain('Helios');
      jarvis['flushParked']();
      const report = jarvisApi.timings.calls.mostRecent().args[0];
      expect(report.invitation).toBeTrue();
      expect(report.oralCut).toBeFalse();
      expect(report.spokenWords).toBe(5);
      jarvis.ngOnDestroy();
    }));

  it('F-240 / SF-240-03 — trois étages : l’essentiel seul affiché, le détail replié sans l’essentiel, toucher mesuré',
    fakeAsync(() => {
      const fixture = TestBed.createComponent(JarvisComponent);
      fixture.detectChanges();
      const jarvis = fixture.componentInstance;
      dictation.stop.and.resolveTo('Le déploiement est fini ?');
      void jarvis.talk();
      flushMicrotasks();
      void jarvis.talk();
      flushMicrotasks();
      handlers!.onDone({ reply: '🎙 Oui, Monsieur, le déploiement est terminé.\n<<essentiel>>\n- Terminé à 10:42\n'
        + '- Santé au vert\n<</essentiel>>\n\nTrois services mis à jour.', actions: [], messageId: 'm-1' });
      flushMicrotasks();
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      const essential = el.querySelector('.jv__essential');
      expect(essential?.textContent).toContain('Terminé à 10:42');
      expect(essential?.textContent).not.toContain('Trois services');
      expect(el.querySelector('.jv__detail')).toBeNull();
      (el.querySelector('.jv__detail-toggle') as HTMLButtonElement).click();
      fixture.detectChanges();
      const detail = el.querySelector('.jv__detail');
      expect(detail?.textContent).toContain('Trois services mis à jour.');
      expect(detail?.textContent).not.toContain('Terminé à 10:42');
      expect(detail?.textContent).not.toContain('Monsieur');
      jarvis['flushParked']();
      const report = jarvisApi.timings.calls.mostRecent().args[0];
      expect(report.depthReached).toBe(3);
      expect(report.depthVia).toBe('toucher');
      jarvis.ngOnDestroy();
    }));

  it('F-240 / SF-240-03 — une nouvelle réponse replie le détail ; sans détail, pas de bouton', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    dictation.stop.and.resolveTo('Ça va ?');
    void jarvis.talk();
    flushMicrotasks();
    void jarvis.talk();
    flushMicrotasks();
    jarvis.showDetail.set(true);
    handlers!.onDone({ reply: '🎙 Oui.\n<<essentiel>>\nTout va bien.\n<</essentiel>>', actions: [], messageId: 'm-1' });
    flushMicrotasks();
    fixture.detectChanges();
    expect(jarvis.showDetail()).toBeFalse();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jv__essential')?.textContent).toContain('Tout va bien.');
    expect(el.querySelector('.jv__detail-toggle')).toBeNull();
    jarvis.ngOnDestroy();
  }));

  it('F-240 / SF-240-03 — à 360 px, l’essentiel ne déborde pas en largeur (mot ou lien très long)', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    const host = fixture.nativeElement as HTMLElement;
    host.style.display = 'block';
    host.style.width = '360px';
    document.body.appendChild(host);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    dictation.stop.and.resolveTo('Le lien ?');
    void jarvis.talk();
    flushMicrotasks();
    void jarvis.talk();
    flushMicrotasks();
    handlers!.onDone({ reply: '🎙 Le voici.\n<<essentiel>>\nhttps://exemple.fr/' + 'tres-long-chemin-'.repeat(12)
      + '\n<</essentiel>>', actions: [], messageId: 'm-1' });
    flushMicrotasks();
    fixture.detectChanges();
    const essential = host.querySelector('.jv__essential') as HTMLElement;
    expect(essential).not.toBeNull();
    expect(essential.scrollWidth).toBeLessThanOrEqual(essential.clientWidth + 1);
    expect(essential.getBoundingClientRect().width).toBeLessThanOrEqual(360);
    host.remove();
    jarvis.ngOnDestroy();
  }));

  const ANSWER = '🎙 Deux tickets sont bloqués, Monsieur.\n↪ Il y a aussi un point sur Helios, je vous le dis ?\n'
    + '<<essentiel>>\n- ORN-41 : export PDF\n- Helios : bascule jeudi\n<</essentiel>>\n'
    + '## Tickets\nLes deux tickets attendent la recette.\n## Helios\nLa bascule est prévue jeudi.';

  function answered(): JarvisComponent {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    dictation.stop.and.resolveTo('Regarde les tickets bloqués.');
    void jarvis.talk();
    flushMicrotasks();
    void jarvis.talk();
    flushMicrotasks();
    handlers!.onDone({ reply: ANSWER, actions: [], messageId: 'm-1' });
    flushMicrotasks();
    voice.speak.calls.reset();
    return jarvis;
  }

  it('F-240 / SF-240-04 — « dis-m’en plus » dit l’essentiel, « encore » déplie le détail : aucun appel au modèle',
    fakeAsync(() => {
      const jarvis = answered();
      const streams = atelier.streamChat.calls.count();
      void jarvis.handle('Dis-m’en plus.');
      flushMicrotasks();
      expect(voice.speak.calls.mostRecent().args[0]).toBe('ORN-41 : export PDF. Helios : bascule jeudi.');
      expect(jarvis.showDetail()).toBeFalse();
      void jarvis.handle('Encore.');
      flushMicrotasks();
      expect(jarvis.showDetail()).toBeTrue();
      expect(voice.speak.calls.mostRecent().args[0]).toBe('Tickets. Les deux tickets attendent la recette.');
      void jarvis.handle('Encore.');
      flushMicrotasks();
      expect(voice.speak.calls.mostRecent().args[0]).toBe('Helios. La bascule est prévue jeudi.');
      void jarvis.handle('Encore.');
      flushMicrotasks();
      expect(voice.speak.calls.mostRecent().args[0]).toBe('C’est tout pour cette réponse.');
      // Jamais Opus, jamais le cerveau rapide.
      expect(atelier.streamChat.calls.count()).toBe(streams);
      expect(fastBrain.converse).not.toHaveBeenCalled();
      jarvis['flushParked']();
      const report = jarvisApi.timings.calls.all().map((c) => c.args[0]).find((r) => r.depthReached);
      expect(report?.depthReached).toBe(3);
      expect(report?.depthVia).toBe('voix');
      jarvis.ngOnDestroy();
    }));

  it('F-240 / SF-240-04 — un « oui » juste après l’invitation ciblée vaut « dis-m’en plus » ; plus tard, non',
    fakeAsync(() => {
      const jarvis = answered();
      void jarvis.handle('Oui.');
      flushMicrotasks();
      expect(voice.speak.calls.mostRecent().args[0]).toBe('ORN-41 : export PDF. Helios : bascule jeudi.');
      // L'invitation est consommée : un second « oui » n'est plus une descente.
      const streams = atelier.streamChat.calls.count();
      void jarvis.handle('Oui.');
      flushMicrotasks();
      expect(atelier.streamChat.calls.count()).toBe(streams + 1);
      jarvis.ngOnDestroy();
    }));

  it('F-240 / SF-240-04 — sans réponse à l’écran, la dernière réponse d’Opus du fil est relue (D9)', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    const history = jasmine.createSpy('getHistory').and.returnValue(of([
      { role: 'USER', content: 'Regarde.' },
      { role: 'ASSISTANT', content: ANSWER },
      { role: 'ASSISTANT', content: 'Réponse rapide.', brain: 'FAST' },
    ]));
    (atelier as unknown as { getHistory: jasmine.Spy }).getHistory = history;
    void jarvis.handle('Dis-m’en plus.');
    flushMicrotasks();
    expect(history).toHaveBeenCalledWith('w-2');
    expect(voice.speak.calls.mostRecent().args[0]).toBe('ORN-41 : export PDF. Helios : bascule jeudi.');
    expect(atelier.streamChat).not.toHaveBeenCalled();
    jarvis.ngOnDestroy();
  }));

  it('F-240 / SF-240-04 — « ⤵ » du cerveau rapide : jamais dit, l’écran descend d’un étage', fakeAsync(() => {
    const jarvis = fastOn();
    dictation.stop.and.resolveTo('Regarde les tickets bloqués.');
    jarvis.send('w-2', 'Regarde les tickets bloqués.');
    handlers!.onDone({ reply: ANSWER, actions: [], messageId: 'm-1' });
    flushMicrotasks();
    voice.speak.calls.reset();
    voice.enqueue.calls.reset();
    void jarvis.handle('Tu peux développer le point Helios ?');
    flushMicrotasks();
    fast!.onText('⤵');
    fast!.onDone({ brain: 'FAST', spokenText: '⤵', firstTokenMs: 300, model: 'm' });
    fastDone!('ok');
    flushMicrotasks();
    expect(voice.enqueue).not.toHaveBeenCalled();
    expect(voice.speak.calls.allArgs().map((a) => a[0])).not.toContain('⤵');
    expect(voice.speak.calls.mostRecent().args[0]).toBe('ORN-41 : export PDF. Helios : bascule jeudi.');
    jarvis.ngOnDestroy();
  }));
});

