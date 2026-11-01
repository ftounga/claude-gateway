import { TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';

import { JARVIS_USER_AGENT, JarvisComponent, companionOffer } from './jarvis.component';
import { AtelierStreamHandlers, WorkspaceSummary } from '../core/models/atelier.models';
import { AtelierService } from '../core/services/atelier.service';
import { DictationService } from '../core/services/dictation.service';
import { JarvisVoiceService } from '../core/services/jarvis-voice.service';
import { JarvisService } from '../core/services/jarvis.service';
import { VeilleService } from '../core/services/veille.service';
import { JarvisStreamOpener, StreamHandlers } from './jarvis-stream-listener';
import { JarvisVadService, VadHandlers } from './jarvis-barge-in';

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

  beforeEach(() => {
    try {
      localStorage.removeItem('cg.jarvis.subject');
    } catch {
      // ignoré
    }
    handlers = null;
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
        'duck', 'interrupt', 'resetHeard']);
    voice.interrupt.and.returnValue(null);
    voice.status.and.returnValue(of({
      enabled: true, voice: 'onyx', monthlyBudgetEur: 100, monthSpentEur: 0, budgetReached: false, provider: 'openai',
    }));
    voice.idle.and.resolveTo();
    voice.speak.and.resolveTo();
    voice.preloadClips.and.resolveTo();
    voice.playClip.and.resolveTo(false);
    voice.hasClip.and.returnValue(false);
    voice.level.and.returnValue(0);
    jarvisApi = jasmine.createSpyObj<JarvisService>('JarvisService', ['usage', 'companion', 'companions', 'timings', 'sttSession', 'closeSttSession']);
    jarvisApi.sttSession.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
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
        { provide: JARVIS_USER_AGENT, useFactory: () => userAgent },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(params) } } },
      ],
    });
  }

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

  it('SF-231-03 — un tour qui tarde reçoit un accusé, puis s’annonce terminé', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;

    jarvis.send('w-2', 'Lance les tests');
    tick(1_999);
    expect(voice.speak).not.toHaveBeenCalled();
    // F-233 / SF-233-02 — l'accusé à 2 s (et non plus 5).
    tick(1);
    expect(voice.speak).toHaveBeenCalledWith('Je m’en occupe.', 'w-2');
    expect(jarvis.state()).toBe('reflexion');

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

  it('F-233 / SF-233-02 — charge les clips, réagit dès la fin de parole, et « Oui, Monsieur ? » au réveil', fakeAsync(() => {
    voice.hasClip.and.returnValue(true);
    voice.playClip.and.resolveTo(true);
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    expect(voice.preloadClips).toHaveBeenCalledWith(['oui', 'je-regarde', 'une-seconde']);
    dictation.stop.and.resolveTo('Où en est la migration ?');

    void jarvis.talk();
    flushMicrotasks();
    void jarvis.talk();
    flushMicrotasks();
    expect(voice.playClip).toHaveBeenCalledWith('je-regarde', jasmine.any(Object));
    jarvis.ngOnDestroy();
  }));

  it('F-233 / SF-233-03 — dit chaque phrase 🎙 dès qu’elle est écrite, sans la redire à la fin', fakeAsync(() => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;

    jarvis.send('w-2', 'Regarde les logs du build');
    handlers!.onText('🎙 Je regarde les logs du build, Monsieur. ');
    expect(voice.enqueue).toHaveBeenCalledWith('Je regarde les logs du build, Monsieur.', 'w-2', undefined);
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
});
