import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { DictationService } from '../../core/services/dictation.service';
import { DictationButtonComponent } from './dictation-button.component';

/**
 * Le micro de la zone de saisie (F-145 / SF-145-01).
 *
 * <p>Deux garanties : l'écran <b>dit qu'il enregistre</b> — un micro ouvert sans indication est
 * inacceptable sur la machine d'un client — et la dictée <b>écrit sans envoyer</b>.</p>
 */
describe('DictationButtonComponent', () => {
  let fixture: ComponentFixture<DictationButtonComponent>;
  let dictation: jasmine.SpyObj<DictationService>;

  function dom(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function build(supported = true): void {
    dictation = jasmine.createSpyObj<DictationService>('DictationService', ['start', 'stop', 'cancel'], {
      supported,
      recording: false,
    });
    dictation.start.and.resolveTo();
    dictation.stop.and.resolveTo('vérifie le certificat');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [DictationButtonComponent],
      providers: [
        { provide: DictationService, useValue: dictation },
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
      ],
    });
    fixture = TestBed.createComponent(DictationButtonComponent);
    fixture.detectChanges();
  }

  it('LE CRITÈRE : la voix rejoint le brouillon, et rien n\'est envoyé', async () => {
    build();
    const received: string[] = [];
    fixture.componentInstance.transcribed.subscribe((text) => received.push(text));

    await fixture.componentInstance.begin();
    fixture.detectChanges();
    await fixture.componentInstance.finish();
    fixture.detectChanges();

    expect(received).toEqual(['vérifie le certificat']);
    // Le composant n'a aucun moyen d'envoyer : il émet, l'écran décide.
    expect(dictation.stop).toHaveBeenCalledTimes(1);
  });

  it('DIT qu\'il enregistre, et cesse de le dire à l\'arrêt', async () => {
    build();

    await fixture.componentInstance.begin();
    fixture.detectChanges();
    expect(dom().textContent).toContain('Enregistrement…');

    await fixture.componentInstance.finish();
    fixture.detectChanges();
    expect(dom().textContent).not.toContain('Enregistrement…');
  });

  it('dit le refus du micro, une fois, sans casser l\'écran', async () => {
    build();
    dictation.start.and.rejectWith('micro-refuse');

    await fixture.componentInstance.begin();
    fixture.detectChanges();

    expect(dom().textContent).toContain('Micro refusé');
    expect(fixture.componentInstance.recording()).toBeFalse();
  });

  it('dit clairement que la dictée n\'est pas configurée', async () => {
    build();
    dictation.stop.and.rejectWith('non-configure');

    await fixture.componentInstance.begin();
    await fixture.componentInstance.finish();
    fixture.detectChanges();

    expect(dom().textContent).toContain("n'est pas configurée");
  });

  it('n\'émet rien quand l\'extrait était trop court', async () => {
    build();
    dictation.stop.and.resolveTo('');
    const received: string[] = [];
    fixture.componentInstance.transcribed.subscribe((text) => received.push(text));

    await fixture.componentInstance.begin();
    await fixture.componentInstance.finish();

    expect(received).toEqual([]);
  });

  it('ne montre aucun bouton quand le navigateur ne sait pas enregistrer', () => {
    build(false);

    expect(dom().querySelector('button')).toBeNull();
  });

  it('démarre sur Ctrl + Espace, et s\'arrête au relâchement', async () => {
    build();

    document.dispatchEvent(new KeyboardEvent('keydown', { code: 'Space', ctrlKey: true }));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(dictation.start).toHaveBeenCalled();

    document.dispatchEvent(new KeyboardEvent('keyup', { code: 'Space' }));
    await fixture.whenStable();
    expect(dictation.stop).toHaveBeenCalled();
  });

  // ------------------------------------- la barre d'espace MAINTENUE (F-145 / SF-145-02)

  /** Envoie une frappe comme si elle partait de cet élément. */
  function key(type: 'keydown' | 'keyup', target: EventTarget, options: KeyboardEventInit = {}): void {
    const event = new KeyboardEvent(type, { code: 'Space', bubbles: true, cancelable: true, ...options });
    Object.defineProperty(event, 'target', { value: target });
    document.dispatchEvent(event);
  }

  /** Laisse passer le délai de maintien. */
  async function hold(): Promise<void> {
    jasmine.clock().tick(DictationButtonComponent.HOLD_MS + 10);
    await fixture.whenStable();
  }

  it('LE CRITÈRE : un appui COURT écrit un espace et ne déclenche rien', async () => {
    build();
    jasmine.clock().install();

    key('keydown', document.body);
    jasmine.clock().tick(200);
    key('keyup', document.body);
    await fixture.whenStable();

    expect(dictation.start).not.toHaveBeenCalled();
    jasmine.clock().uninstall();
  });

  it('LE CRITÈRE : un MAINTIEN bascule en dictée, même depuis le champ', async () => {
    build();
    jasmine.clock().install();
    const field = document.createElement('input');

    key('keydown', field);
    await hold();

    expect(dictation.start).toHaveBeenCalled();
    jasmine.clock().uninstall();
  });

  it('au basculement, les espaces insérés pendant le maintien sont retirés', async () => {
    build();
    jasmine.clock().install();
    fixture.componentRef.setInput('draft', 'vérifie le certificat');
    fixture.detectChanges();
    const restored: string[] = [];
    fixture.componentInstance.draftRestored.subscribe((value) => restored.push(value));

    key('keydown', document.body);
    // La répétition du clavier insère des espaces : le composant ne doit pas en tenir compte.
    key('keydown', document.body, { repeat: true });
    await hold();

    expect(restored).toEqual(['vérifie le certificat']);
    jasmine.clock().uninstall();
  });

  it('Espace sur un BOUTON active le bouton : on ne vole pas une touche au clavier', async () => {
    build();
    jasmine.clock().install();
    const button = document.createElement('button');

    key('keydown', button);
    await hold();

    expect(dictation.start).not.toHaveBeenCalled();
    jasmine.clock().uninstall();
  });

  it('Ctrl + Espace bascule IMMÉDIATEMENT, sans attendre le seuil', async () => {
    build();

    key('keydown', document.createElement('input'), { ctrlKey: true });
    await fixture.whenStable();

    expect(dictation.start).toHaveBeenCalled();
  });

  it('un maintien ne lance qu\'UN enregistrement malgré la répétition', async () => {
    build();
    jasmine.clock().install();

    key('keydown', document.body);
    key('keydown', document.body, { repeat: true });
    key('keydown', document.body, { repeat: true });
    await hold();

    expect(dictation.start).toHaveBeenCalledTimes(1);
    jasmine.clock().uninstall();
  });

  it('annonce son état, pour que la zone de saisie le traduise', async () => {
    build();
    const states: string[] = [];
    fixture.componentInstance.stateChange.subscribe((state) => states.push(state));

    await fixture.componentInstance.begin();
    await fixture.componentInstance.finish();

    expect(states).toEqual(['recording', 'transcribing', 'idle']);
  });
});
