import { TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { JarvisComponent } from './jarvis.component';
import { AtelierStreamHandlers, WorkspaceSummary } from '../core/models/atelier.models';
import { AtelierService } from '../core/services/atelier.service';
import { DictationService } from '../core/services/dictation.service';
import { JarvisVoiceService } from '../core/services/jarvis-voice.service';

function subject(id: string, name: string): WorkspaceSummary {
  return { id, name, createdAt: '2026-11-01T08:00:00Z', source: 'LOCAL', gitRepo: null } as WorkspaceSummary;
}

/** **L'écran Jarvis** (F-231 / SF-231-02) : le même cerveau, à la voix. */
describe('L’écran Jarvis (F-231 / SF-231-02)', () => {
  let atelier: jasmine.SpyObj<AtelierService>;
  let dictation: jasmine.SpyObj<DictationService>;
  let voice: jasmine.SpyObj<JarvisVoiceService>;
  let handlers: AtelierStreamHandlers | null;

  beforeEach(() => {
    try {
      localStorage.removeItem('cg.jarvis.subject');
    } catch {
      // ignoré
    }
    handlers = null;
    atelier = jasmine.createSpyObj<AtelierService>('AtelierService', ['listWorkspaces', 'streamChat']);
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
    TestBed.configureTestingModule({
      imports: [JarvisComponent],
      providers: [
        provideNoopAnimations(), provideRouter([]),
        { provide: AtelierService, useValue: atelier },
        { provide: DictationService, useValue: dictation },
        { provide: JarvisVoiceService, useValue: voice },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ sujet: 'w-2' }) } } },
      ],
    });
  });

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
});
