import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { AdminJarvisVoiceComponent, sampleMessage } from './admin-jarvis-voice.component';
import { AdminJarvisVoiceService, Audition } from './admin-jarvis-voice.service';

const AUDITION: Audition = {
  phrases: ['Bonjour, Monsieur.', 'Je regarde les journaux du build.'],
  candidates: [{ label: 'A', available: true }, { label: 'B', available: false }],
  chosen: null,
};

/** L'audition à l'aveugle (F-233 / SF-233-07). */
describe('AdminJarvisVoiceComponent (F-233 / SF-233-07)', () => {
  let service: jasmine.SpyObj<AdminJarvisVoiceService>;
  let snack: jasmine.SpyObj<MatSnackBar>;

  beforeEach(() => {
    service = jasmine.createSpyObj<AdminJarvisVoiceService>('AdminJarvisVoiceService',
      ['audition', 'sample', 'choose', 'reset']);
    service.audition.and.returnValue(of(AUDITION));
    snack = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [AdminJarvisVoiceComponent],
      providers: [
        provideNoopAnimations(),
        { provide: AdminJarvisVoiceService, useValue: service },
        { provide: MatSnackBar, useValue: snack },
      ],
    });
  });

  it('montre les voix anonymisées, sans fournisseur ; une voix indisponible ne s’écoute pas', () => {
    const fixture = TestBed.createComponent(AdminJarvisVoiceComponent);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Voix A');
    expect(text).toContain('Voix B');
    expect(text).not.toMatch(/ElevenLabs|OpenAI ·|George|Daniel|Onyx/);
    expect(text).toContain('indisponible');
    const buttons = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .filter((b) => b.textContent?.includes('Écouter'));
    expect(buttons[0].disabled).toBeFalse();
    expect(buttons[1].disabled).toBeTrue();
  });

  it('écoute la phrase choisie, puis le choix révèle la voix et recharge', () => {
    service.sample.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    service.choose.and.returnValue(of({ label: 'A', provider: 'elevenlabs', voice: 'x', name: 'George (ElevenLabs)' }));
    const fixture = TestBed.createComponent(AdminJarvisVoiceComponent);
    fixture.detectChanges();
    const jarvis = fixture.componentInstance;
    jarvis.phraseIndex.set(1);
    jarvis.listen('A');
    expect(service.sample).toHaveBeenCalledWith('A', 1);
    expect(snack.open).toHaveBeenCalledWith(sampleMessage(503), 'OK', jasmine.any(Object));

    jarvis.choose('A');
    expect(snack.open).toHaveBeenCalledWith(jasmine.stringContaining('George (ElevenLabs)'), 'OK', jasmine.any(Object));
    expect(service.audition).toHaveBeenCalledTimes(2);
  });

  it('la voix choisie est affichée, et l’on peut revenir à la voix par défaut', () => {
    service.audition.and.returnValue(of({ ...AUDITION,
      chosen: { label: 'A', provider: 'elevenlabs', voice: 'x', name: 'Daniel (ElevenLabs)' } }));
    service.reset.and.returnValue(of(undefined));
    const fixture = TestBed.createComponent(AdminJarvisVoiceComponent);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Daniel (ElevenLabs)');
    fixture.componentInstance.reset();
    expect(service.reset).toHaveBeenCalled();
  });
});
