import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { FOLD_LINES, referenceOf } from './pasted-text';

/**
 * Un long texte collé ne noie pas la saisie (F-146 / SF-146-01), dans le terminal.
 *
 * <p>Ce qui s'y vérifie : le champ reçoit une <b>référence</b> et non trois cents lignes, la puce
 * dit ce qui est mis de côté, et le retrait est réel — sa référence quitte le champ.</p>
 */
describe('AtelierTerminalComponent — le texte collé replié', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
  let drafts: string[];

  const long = Array.from({ length: FOLD_LINES + 3 }, (_, i) => `ligne ${i}`).join('\n');

  function paste(text: string): void {
    const data = new DataTransfer();
    data.setData('text/plain', text);
    const event = new ClipboardEvent('paste', { clipboardData: data, cancelable: true });
    component.onPaste(event);
    fixture.detectChanges();
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
    drafts = [];
    component.draftChange.subscribe((value) => {
      drafts.push(value);
      component.draft = value;
    });
    fixture.detectChanges();
  });

  it('LE CRITÈRE : le champ reçoit une référence, pas le pavé', () => {
    paste(long);

    expect(drafts.at(-1)).toBe(referenceOf(1));
    expect(drafts.at(-1)).not.toContain('ligne 3');
    expect(component.pastes()).toHaveSize(1);
    expect(component.pastes()[0].lines).toBe(FOLD_LINES + 3);
  });

  it('un collage court n\'est pas replié : le champ le reçoit normalement', () => {
    paste('deux\nlignes');

    expect(component.pastes()).toHaveSize(0);
    expect(drafts).toHaveSize(0);
  });

  it('la puce dit ce qui est mis de côté', () => {
    paste(long);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('texte collé #1');
    expect(text).toContain(`${FOLD_LINES + 3} lignes`);
  });

  it('retirer la puce sort la référence du champ', () => {
    paste(long);

    component.removePaste(component.pastes()[0]);
    fixture.detectChanges();

    expect(component.pastes()).toHaveSize(0);
    expect(drafts.at(-1)).toBe('');
  });

  it('deux collages sont numérotés distinctement', () => {
    paste(long);
    paste(long + '\nencore');

    expect(component.pastes().map((p) => p.index)).toEqual([1, 2]);
    expect(drafts.at(-1)).toContain(referenceOf(1));
    expect(drafts.at(-1)).toContain(referenceOf(2));
  });

  it('un collage en lecture seule ne met rien de côté', () => {
    component.readOnly = true;

    paste(long);

    expect(component.pastes()).toHaveSize(0);
  });
});
