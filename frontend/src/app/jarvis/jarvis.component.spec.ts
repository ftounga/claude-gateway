import { TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { JARVIS_USER_AGENT, JarvisComponent, companionOffer } from './jarvis.component';
import { JarvisSceneBus } from './scenes/jarvis-scene-bus.service';
import { testUniverse } from './scenes/jarvis-universe.fixture';
import { NotificationCenterService } from '../core/services/notification-center.service';
import { LiveTerminalService } from '../core/services/live-terminal.service';
import { signal as ngSignal } from '@angular/core';

/** F-234 / SF-234-10 — les relevés existants, vus par leurs signaux seulement. */
const notificationCenter = { items: ngSignal<{ id: string; event: string; workspaceId: string | null }[]>([]) };
const liveTerminals = { registry: ngSignal<{ terminals: { workspaceId: string }[] } | null>(null) };
import { AtelierStreamHandlers, WorkspaceSummary } from '../core/models/atelier.models';
import { AtelierService } from '../core/services/atelier.service';
import { DictationService } from '../core/services/dictation.service';
import { JarvisVoiceService } from '../core/services/jarvis-voice.service';
import { JarvisService } from '../core/services/jarvis.service';
import { FastBrainHandlers, FastBrainOutcome, JarvisFastBrainClient } from './jarvis-fast-brain';
import { VeilleService } from '../core/services/veille.service';
import { JarvisStreamOpener, StreamHandlers } from './jarvis-stream-listener';
import { JarvisVadService, VadHandlers } from './jarvis-barge-in';
import { FORMULAS, FORMULA_CLIPS, tutoie } from './jarvis-phrases';
import { JARVIS_3D_LOADER, JarvisEngineFactory } from './stage3d/jarvis-engine.contract';
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
    dictation = jasmine.createSpyObj<DictationService>('DictationService', ['start', 'stop', 'cancel', 'transcribe'],
      { supported: true, stream: null });
    dictation.start.and.resolveTo();
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
    jarvisApi = jasmine.createSpyObj<JarvisService>('JarvisService', ['usage', 'companion', 'companions', 'timings', 'sttSession', 'closeSttSession', 'preferences', 'setHumour', 'scenes', 'render']);
    jarvisApi.sttSession.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
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
      false, [], null, false, 'JARVIS', null);
    expect(jarvis.state()).toBe('reflexion');

    handlers!.onText('🎙 La migration');
    handlers!.onDone({ reply: '🎙 La migration est à l’étape 4.\n\nDétail…', actions: [], messageId: 'm-1' });
    flushMicrotasks();
    expect(voice.speak).toHaveBeenCalledWith('La migration est à l’étape 4.', 'w-2', jasmine.any(Object));
    expect(jarvis.detail()).toContain('Détail');
    expect(jarvis.state()).toBe('repos');
    // F-233 / SF-233-01 — le tour vocal est chronométré, sans contenu.
    expect(jarvisApi.timings).toHaveBeenCalledTimes(1);
    const report = jarvisApi.timings.calls.mostRecent().args[0];
    expect(report.workspaceId).toBe('w-2');
    expect(report.transcriptMs).not.toBeNull();
    expect(report.sentMs).not.toBeNull();
    expect(report.firstTokenMs).not.toBeNull();
    expect(report.doneMs).not.toBeNull();
    expect(JSON.stringify(report)).not.toContain('migration');
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

  it('SF-231-03 / SF-233-11 — un tour qui tarde reçoit UNE formule d’attente variée, puis s’annonce terminé',
    fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;

    jarvis.send('w-2', 'Lance les tests');
    tick(1_199);
    expect(voice.speak).not.toHaveBeenCalled();
    tick(1);
    expect(voice.speak).toHaveBeenCalledTimes(1);
    const said = voice.speak.calls.mostRecent().args[0];
    expect(FILLERS).toContain(said);
    expect(jarvis.state()).toBe('reflexion');
    // Une seule formule par échange : rien de plus pendant que le tour tourne.
    tick(20_000);
    expect(voice.speak).toHaveBeenCalledTimes(1);

    tick(30_000);
    handlers!.onDone({ reply: '🎙 Les tests passent.', actions: [], messageId: 'm-1' });
    flushMicrotasks();
    expect(voice.speak).toHaveBeenCalledWith('C’est terminé. Les tests passent.', 'w-2');
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

  it('F-233 / SF-233-11 — charge le répertoire ; formule d’attente seulement si la réponse tarde', fakeAsync(() => {
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
    // Plus de réaction immédiate à la fin de la parole.
    expect(voice.playClip).not.toHaveBeenCalled();
    tick(1_200);
    expect(voice.playClip).toHaveBeenCalledTimes(1);
    const clip = voice.playClip.calls.mostRecent().args[0];
    expect(FORMULAS.find((f) => f.id === clip)?.kind).toBe('filler');
    jarvis.ngOnDestroy();
    tick(1_000);
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

  it('F-233 / SF-233-03 — sans ligne 🎙 écrite au fil de l’eau, la phrase finale est dite à la fin (repli)', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;

    jarvis.send('w-2', 'Bonjour');
    handlers!.onText('Bonjour Monsieur. Tout va bien.');
    expect(voice.enqueue).not.toHaveBeenCalled();
    handlers!.onDone({ reply: 'Bonjour Monsieur. Tout va bien.', actions: [], messageId: 'm-1' });
    flushMicrotasks();
    expect(voice.speak).toHaveBeenCalledWith('Bonjour Monsieur. Tout va bien.', 'w-2');
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
        false, [], null, false, 'JARVIS', null);
      expect(jarvisApi.closeSttSession).toHaveBeenCalledWith('s-1', 4);
      handlers!.onDone({ reply: '🎙 Étape 4.', actions: [], messageId: 'm-1' });
      flushMicrotasks();
      expect(jarvisApi.timings.calls.mostRecent().args[0].listenMode).toBe('STREAM');
      expect(jarvisApi.timings.calls.mostRecent().args[0].sttProvider).toBe('elevenlabs');
      expect(jarvisApi.timings.calls.mostRecent().args[0].ttsProvider).toBe('openai');
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
      expect(dictation.transcribe).toHaveBeenCalled();
      expect(vadStop).toHaveBeenCalled();
      expect(atelier.streamChat).toHaveBeenCalledWith('w-2', 'Et les tests ?', jasmine.any(Object), 'ACT', false, [],
        null, false, 'JARVIS', 'La migration est');
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
        jasmine.any(Object), 'ACT', false, [], null, false, 'JARVIS', null);
      expect(jarvis.delegations().map((d) => d.label)).toEqual(['tickets bloqués d’AGENOR']);

      voice.enqueue.calls.reset();
      handlers!.onText('🎙 Je lance la recherche Jira.');
      expect(voice.enqueue).not.toHaveBeenCalled();

      handlers!.onDone({ reply: '🎙 Deux tickets sont bloqués.\n\nDétail…', actions: [], messageId: 'm-1' });
      flushMicrotasks();
      expect(voice.speak).toHaveBeenCalledWith('Pour tickets bloqués d’AGENOR : Deux tickets sont bloqués.', 'w-1');
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
      false, 'JARVIS', null);
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
      'JARVIS', null);
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
      const first = jarvisApi.timings.calls.mostRecent().args[0];
      expect(first.brain).toBe('FAST');
      expect(first.fastModel).toBe('claude-haiku-5-5');
      expect(first.fastFirstTokenMs).toBe(420);
      expect(first.fillersRemoved).toBe(1);
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
      const second = jarvisApi.timings.calls.mostRecent().args[0];
      expect(second.brain).toBe('FAST_DELEGATED');
      expect(second.correction).toBeTrue();
      expect(second.forcedDelegation).toBeTrue();
      expect(second.delegationReason).toBe('forced');
      expect(second.fastFirstTokenMs).toBeNull();
      jarvis.ngOnDestroy();
    }));
});
