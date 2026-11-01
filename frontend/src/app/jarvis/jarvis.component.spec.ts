import { TestBed, fakeAsync, flushMicrotasks, tick } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { JARVIS_USER_AGENT, JarvisComponent, companionOffer } from './jarvis.component';
import { AtelierStreamHandlers, WorkspaceSummary } from '../core/models/atelier.models';
import { AtelierService } from '../core/services/atelier.service';
import { DictationService } from '../core/services/dictation.service';
import { JarvisVoiceService } from '../core/services/jarvis-voice.service';
import { JarvisService } from '../core/services/jarvis.service';
import { VeilleService } from '../core/services/veille.service';

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
    dictation = jasmine.createSpyObj<DictationService>('DictationService', ['start', 'stop', 'cancel'],
      { supported: true, stream: null });
    dictation.start.and.resolveTo();
    voice = jasmine.createSpyObj<JarvisVoiceService>('JarvisVoiceService', ['speak', 'stop', 'unlock', 'level']);
    voice.speak.and.resolveTo();
    voice.level.and.returnValue(0);
    jarvisApi = jasmine.createSpyObj<JarvisService>('JarvisService', ['usage', 'companion']);
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
    expect(voice.speak).toHaveBeenCalledWith('Veille Jira AGENOR : AG-2 est passé en Bloqué. Que veux-tu faire ?', 'w-1');
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
      false, [], null, false, 'JARVIS');
    expect(jarvis.state()).toBe('reflexion');

    handlers!.onDone({ reply: '🎙 La migration est à l’étape 4.\n\nDétail…', actions: [], messageId: 'm-1' });
    flushMicrotasks();
    expect(voice.speak).toHaveBeenCalledWith('La migration est à l’étape 4.', 'w-2');
    expect(jarvis.detail()).toContain('Détail');
    expect(jarvis.state()).toBe('repos');
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
    tick(5_000);
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
    expect(voice.speak).toHaveBeenCalledWith('Je vais lancer la commande « git push ». Tu confirmes ?', 'w-2');
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

  it('F-232 — hors Android et sans compagnon servi, ni lien ni encart', () => {
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('.jv__companion')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('app-jarvis-companion-panel')).toBeNull();
  });

  it('SF-232-03 — compagnon servi : l’encart d’appairage s’affiche, même hors Android (pas de lien d’installation)', () => {
    jarvisApi.companion.and.returnValue(of(COMPANION_V4));
    const fixture = TestBed.createComponent(JarvisComponent);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('app-jarvis-companion-panel')).not.toBeNull();
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
});
