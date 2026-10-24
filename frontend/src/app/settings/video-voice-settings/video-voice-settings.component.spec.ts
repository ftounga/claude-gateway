import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { VideoVoiceSettingsComponent } from './video-voice-settings.component';
import { SpeechService, SpeechVoicesView } from '../../core/services/speech.service';

describe('VideoVoiceSettingsComponent (F-215 / SF-215-01)', () => {
  let fixture: ComponentFixture<VideoVoiceSettingsComponent>;
  let service: jasmine.SpyObj<SpeechService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const view = (overrides: Partial<SpeechVoicesView> = {}): SpeechVoicesView => ({
    enabled: true,
    voices: [
      { id: 'onyx', label: 'Onyx — grave, posée (par défaut)' },
      { id: 'nova', label: 'Nova — claire, dynamique' },
    ],
    defaultVoice: 'onyx',
    defaultInstructions: 'Formateur technique français.',
    preference: null,
    ...overrides,
  });

  function build(initial: SpeechVoicesView = view()): void {
    service = jasmine.createSpyObj<SpeechService>('SpeechService', ['voices', 'save', 'reset', 'sample']);
    service.voices.and.returnValue(of(initial));
    service.save.and.returnValue(of(initial));
    service.reset.and.returnValue(of(undefined));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [VideoVoiceSettingsComponent],
      providers: [
        provideNoopAnimations(),
        { provide: SpeechService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(VideoVoiceSettingsComponent);
    fixture.detectChanges();
  }

  const el = (): HTMLElement => fixture.nativeElement as HTMLElement;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const cmp = (): any => fixture.componentInstance;

  it('affiche la carte avec la voix par défaut quand aucune préférence', () => {
    build();
    expect(el().querySelector('.voice')).not.toBeNull();
    expect(cmp().voice).toBe('onyx');
    expect(el().querySelector('.voice__reset')).toBeNull();
  });

  it("masque la carte quand la voix off est éteinte ou illisible", () => {
    build(view({ enabled: false }));
    expect(el().querySelector('.voice')).toBeNull();
    TestBed.resetTestingModule();
    service = jasmine.createSpyObj<SpeechService>('SpeechService', ['voices']);
    service.voices.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    TestBed.configureTestingModule({
      imports: [VideoVoiceSettingsComponent],
      providers: [provideNoopAnimations(), { provide: SpeechService, useValue: service }],
    });
    fixture = TestBed.createComponent(VideoVoiceSettingsComponent);
    fixture.detectChanges();
    expect(el().querySelector('.voice')).toBeNull();
  });

  it('reprend la préférence enregistrée et propose le retour au défaut', () => {
    build(view({ preference: { voice: 'nova', instructions: 'plus dynamique' } }));
    expect(cmp().voice).toBe('nova');
    expect(cmp().instructions).toBe('plus dynamique');
    expect(el().querySelector('.voice__reset')).not.toBeNull();
  });

  it('enregistre la voix et les consignes (vides => null)', () => {
    build();
    cmp().voice = 'nova';
    cmp().instructions = '   ';
    (el().querySelector('.voice__save') as HTMLButtonElement).click();
    expect(service.save).toHaveBeenCalledWith({ voice: 'nova', instructions: null });
    expect(snackBar.open).toHaveBeenCalled();
  });

  it("dit l'erreur du serveur à l'enregistrement", () => {
    build();
    service.save.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 400, error: { message: 'Voix inconnue.' } })),
    );
    (el().querySelector('.voice__save') as HTMLButtonElement).click();
    expect(snackBar.open).toHaveBeenCalledWith('Voix inconnue.', 'Fermer', jasmine.any(Object));
  });

  it('revient au réglage par défaut', () => {
    build(view({ preference: { voice: 'nova', instructions: 'x' } }));
    (el().querySelector('.voice__reset') as HTMLButtonElement).click();
    expect(service.reset).toHaveBeenCalled();
    expect(cmp().voice).toBe('onyx');
    expect(cmp().instructions).toBe('');
  });

  it("écoute l'échantillon de la voix choisie", fakeAsync(() => {
    build();
    const play = spyOn(HTMLMediaElement.prototype, 'play').and.returnValue(Promise.resolve());
    service.sample.and.returnValue(of(new Blob([new Uint8Array([1, 2, 3])], { type: 'audio/mpeg' })));
    cmp().voice = 'nova';
    (el().querySelector('.voice__listen') as HTMLButtonElement).click();
    tick();
    expect(service.sample).toHaveBeenCalledWith('nova');
    expect(play).toHaveBeenCalled();
  }));
});
