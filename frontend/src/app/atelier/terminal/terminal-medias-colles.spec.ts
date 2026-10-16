import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { FOLD_LINES, referenceOf } from './pasted-text';

/**
 * Médias collés façon Claude Code (F-201 / SF-201-02), dans le terminal : position du curseur
 * signalée au collage, références de texte collé au curseur et numérotées sans doublon, cycle de vie
 * marqueur ↔ puce, traduction à l'envoi.
 */
describe('AtelierTerminalComponent — médias collés marqués dans le texte', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
  let drafts: string[];
  let removed: string[];

  const long = Array.from({ length: FOLD_LINES + 3 }, (_, i) => `ligne ${i}`).join('\n');

  function field(): HTMLInputElement {
    return (fixture.nativeElement as HTMLElement).querySelector('input.terminal-field') as HTMLInputElement;
  }

  function setDraft(value: string, caret?: number): void {
    component.draft = value;
    fixture.detectChanges();
    const input = field();
    input.value = value;
    const at = caret ?? value.length;
    input.setSelectionRange(at, at);
  }

  function pasteText(text: string): void {
    const data = new DataTransfer();
    data.setData('text/plain', text);
    component.onPaste(new ClipboardEvent('paste', { clipboardData: data, cancelable: true }));
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations(), provideRouter([])],
    });
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    drafts = [];
    removed = [];
    component.draftChange.subscribe((value) => {
      drafts.push(value);
      component.draft = value;
    });
    component.depositRemove.subscribe((id) => removed.push(id));
    fixture.detectChanges();
  });

  it('LE CRITÈRE : une image collée signale la position du curseur, puis les fichiers', () => {
    setDraft('compare  avec la maquette', 8);
    const order: string[] = [];
    let caret = -1;
    component.filesCaret.subscribe((value) => {
      order.push('caret');
      caret = value;
    });
    component.filesSelected.subscribe(() => order.push('files'));

    const data = new DataTransfer();
    data.items.add(new File(['x'], 'image.png', { type: 'image/png' }));
    component.onPaste(new ClipboardEvent('paste', { clipboardData: data, cancelable: true }));

    expect(order).toEqual(['caret', 'files']);
    expect(caret).toBe(8);
  });

  it('un long texte collé se pose AU CURSEUR, pas en fin de saisie', () => {
    setDraft('avant après', 6);

    pasteText(long);

    expect(drafts.at(-1)).toBe(`avant ${referenceOf(1)} après`);
  });

  it('CORRECTIF : après un retrait, un nouveau collage ne reprend pas un numéro encore porté', () => {
    pasteText(long);
    pasteText(long);
    component.removePaste(component.pastes()[0]);
    fixture.detectChanges();

    pasteText(long);

    expect(component.pastes().map((p) => p.index)).toEqual([2, 3]);
  });

  it('la puce rappelle le marqueur posé dans la saisie', () => {
    component.depositNotices = [
      { id: 'n1', path: '.atelier/entrees/capture-1.png', sizeLabel: '1 Ko', marker: '[Image #1]' },
    ];
    fixture.detectChanges();

    const chip = (fixture.nativeElement as HTMLElement).querySelector('.terminal-attachment') as HTMLElement;
    expect(chip.querySelector('.terminal-attachment__marker')?.textContent).toContain('[Image #1]');
  });

  it('retirer la puce retire aussi son marqueur du champ', () => {
    component.draft = 'vois [Image #1] et dis-moi';
    const notice = { id: 'n1', path: 'entrees/capture-1.png', sizeLabel: '1 Ko', marker: '[Image #1]' };
    component.depositNotices = [notice];
    fixture.detectChanges();

    component.removeAttachment(notice);

    expect(drafts.at(-1)).toBe('vois et dis-moi');
    expect(removed).toEqual(['n1']);
  });

  it('effacer le marqueur à la main retire la pièce', () => {
    component.depositNotices = [
      { id: 'n1', path: 'entrees/a.png', sizeLabel: '1 Ko', marker: '[Image #1]' },
      { id: 'n2', path: 'entrees/b.png', sizeLabel: '1 Ko', marker: '[Image #2]' },
    ];
    fixture.detectChanges();

    component.onDraftInput('il reste [Image #2]', field());

    expect(removed).toEqual(['n1']);
  });

  it('effacer la référence d\'un texte collé retire sa puce', () => {
    pasteText(long);

    component.onDraftInput('plus rien', field());

    expect(component.pastes()).toHaveSize(0);
  });

  it('à l\'envoi, chaque marqueur devient une référence explicite au fichier, à sa place', () => {
    component.depositNotices = [
      { id: 'n1', path: '.atelier/entrees/capture-a.png', sizeLabel: '1 Ko', marker: '[Image #1]' },
    ];
    component.draft = 'que montre [Image #1] ?';
    let sent = false;
    component.send.subscribe(() => (sent = true));

    component.submit();

    expect(sent).toBeTrue();
    expect(drafts.at(-1)).toBe('que montre [Image #1 = .atelier/entrees/capture-a.png] ?');
  });
});
