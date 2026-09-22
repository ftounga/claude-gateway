import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';

/**
 * L'état de dictée, dans la zone de saisie (F-145 / SF-145-02).
 *
 * <p>Demande explicite du PO : *« visuellement quelque chose doit le traduire dans le terminal,
 * l'espace de texte »*. Une icône qui change de couleur dans un coin ne suffit pas — quand on parle,
 * l'œil est sur le champ.</p>
 */
describe('AtelierTerminalComponent — l\'état de dictée dans le champ', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  function field(): HTMLInputElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector('.terminal-field');
  }

  function form(): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector('.terminal-input');
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        provideRouter([]),
      ],
    });
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('LE CRITÈRE : le champ dit qu\'on écoute', () => {
    component.dictationState.set('recording');
    fixture.detectChanges();

    expect(form()?.classList).toContain('terminal-input--recording');
    expect(field()?.placeholder).toContain('Parlez');
  });

  it('distingue l\'écoute de la transcription : le micro est fermé, le texte pas encore là', () => {
    component.dictationState.set('transcribing');
    fixture.detectChanges();

    expect(form()?.classList).toContain('terminal-input--transcribing');
    expect(form()?.classList).not.toContain('terminal-input--recording');
    expect(field()?.placeholder).toContain('Transcription');
  });

  it('retrouve son invite ordinaire quand la dictée est finie', () => {
    component.dictationState.set('recording');
    fixture.detectChanges();
    component.dictationState.set('idle');
    fixture.detectChanges();

    expect(form()?.classList).not.toContain('terminal-input--recording');
    expect(field()?.placeholder).toContain('Décrivez la tâche');
  });
});
