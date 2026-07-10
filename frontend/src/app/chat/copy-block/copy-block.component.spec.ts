import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { CopyBlockComponent } from './copy-block.component';
import { CopyBlock } from '../../shared/copy-block.model';

function block(overrides: Partial<CopyBlock> = {}): CopyBlock {
  return { type: 'code', language: 'typescript', title: 'Code (typescript)', content: 'const a = 1;', ...overrides };
}

describe('CopyBlockComponent', () => {
  let fixture: ComponentFixture<CopyBlockComponent>;
  let component: CopyBlockComponent;
  let snackBar: MatSnackBar;

  // navigator.clipboard est souvent porté par le prototype : on remplace la propriété propre le
  // temps du test puis on la restaure (spyOnProperty échoue faute de descripteur `get`).
  let clipboardOwnDescriptor: PropertyDescriptor | undefined | null = null;

  function stubClipboard(value: unknown): void {
    if (clipboardOwnDescriptor === null) {
      clipboardOwnDescriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    }
    Object.defineProperty(navigator, 'clipboard', { value, configurable: true });
  }

  afterEach(() => {
    if (clipboardOwnDescriptor === null) {
      return;
    }
    if (clipboardOwnDescriptor) {
      Object.defineProperty(navigator, 'clipboard', clipboardOwnDescriptor);
    } else {
      delete (navigator as unknown as { clipboard?: unknown }).clipboard;
    }
    clipboardOwnDescriptor = null;
  });

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CopyBlockComponent],
      providers: [provideNoopAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(CopyBlockComponent);
    component = fixture.componentInstance;
    snackBar = TestBed.inject(MatSnackBar);
    fixture.componentRef.setInput('block', block());
    fixture.detectChanges();
  });

  it('rend le libellé et le contenu brut du bloc', () => {
    const host: HTMLElement = fixture.nativeElement;
    expect(host.querySelector('.copy-block-title')?.textContent).toContain('Code (typescript)');
    expect(host.querySelector('.copy-block-content')?.textContent).toContain('const a = 1;');
  });

  it('copie le contenu brut dans le presse-papiers et notifie', async () => {
    const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
    stubClipboard({ writeText });
    const snackSpy = spyOn(snackBar, 'open');

    component.copy();
    await Promise.resolve();

    expect(writeText).toHaveBeenCalledWith('const a = 1;');
    expect(snackSpy).toHaveBeenCalled();
  });

  it('affiche une erreur douce si le presse-papiers est indisponible (pas d’exception)', () => {
    stubClipboard(undefined);
    const snackSpy = spyOn(snackBar, 'open');

    expect(() => component.copy()).not.toThrow();
    expect(snackSpy).toHaveBeenCalledWith(
      'Copie impossible dans ce contexte.',
      'Fermer',
      jasmine.objectContaining({ duration: 3000 }),
    );
  });

  it('mappe chaque type sur son icône', () => {
    expect(component.iconFor('code')).toBe('code');
    expect(component.iconFor('doc')).toBe('description');
    expect(component.iconFor('mail')).toBe('mail_outline');
  });
});
