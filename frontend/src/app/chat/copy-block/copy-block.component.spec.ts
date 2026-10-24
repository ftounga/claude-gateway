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
    expect(component.iconFor('message')).toBe('chat_bubble_outline');
  });

  // ------------------------------------------- F-203 / SF-203-02 : encart message

  const mail = block({
    type: 'mail',
    language: 'mail',
    title: 'E-mail',
    content: 'Objet : Point projet\n\nBonjour **Alice**,\n\n- un\n- deux',
  });

  it('présente l’objet à part et ne le mêle pas au corps', () => {
    fixture.componentRef.setInput('block', mail);
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('.copy-block-subject-text')?.textContent).toContain('Point projet');
    expect(host.querySelector('.copy-block-rich')?.textContent).not.toContain('Objet');
    expect(host.querySelector('.copy-block-rich strong')?.textContent).toBe('Alice');
  });

  it('copie le corps en HTML propre (sans class/style/id) + texte brut, et affiche « Copié »', async () => {
    fixture.componentRef.setInput('block', mail);
    fixture.detectChanges();
    let written: ClipboardItem | null = null;
    const write = jasmine.createSpy('write').and.callFake((items: ClipboardItem[]) => {
      written = items[0];
      return Promise.resolve();
    });
    stubClipboard({ write, writeText: jasmine.createSpy('writeText') });
    spyOn(snackBar, 'open');

    component.copy();
    await Promise.resolve();
    await Promise.resolve();
    fixture.detectChanges();

    expect(write).toHaveBeenCalled();
    const item = written as unknown as ClipboardItem;
    const html = await (await item.getType('text/html')).text();
    const plain = await (await item.getType('text/plain')).text();
    expect(html).toContain('<strong>Alice</strong>');
    expect(html).toContain('<li>');
    expect(html).not.toMatch(/\s(class|style|id)=/);
    expect(html).not.toContain('Objet');
    expect(plain).toBe('Bonjour **Alice**,\n\n- un\n- deux');
    expect(component.copied()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).querySelector('.copy-button')?.textContent).toContain('Copié');
  });

  it('se replie sur le texte brut du corps si la copie riche est refusée', async () => {
    fixture.componentRef.setInput('block', mail);
    fixture.detectChanges();
    const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
    stubClipboard({ write: () => Promise.reject(new Error('refusé')), writeText });
    spyOn(snackBar, 'open');

    component.copy();
    await new Promise((resolve) => setTimeout(resolve));

    expect(writeText).toHaveBeenCalledWith('Bonjour **Alice**,\n\n- un\n- deux');
  });

  it('copie l’objet seul en texte brut', async () => {
    fixture.componentRef.setInput('block', mail);
    fixture.detectChanges();
    const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
    stubClipboard({ writeText });
    spyOn(snackBar, 'open');

    component.copySubject();
    await Promise.resolve();

    expect(writeText).toHaveBeenCalledWith('Point projet');
  });

  it('un bloc de code n’a jamais d’objet extrait', () => {
    fixture.componentRef.setInput('block', block({ content: 'Objet : x\ncode' }));
    fixture.detectChanges();
    expect(component.parts().subject).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('.copy-block-subject')).toBeNull();
  });

  it('porte la peau sombre sur l’hôte quand tone = dark', () => {
    fixture.componentRef.setInput('tone', 'dark');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).classList).toContain('copy-block-host--dark');
  });

  describe('« Marquer envoyé » (F-211 / SF-211-02)', () => {
    const mail = block({ type: 'mail', language: 'mail', title: 'E-mail', content: 'À : Zahi\nObjet : Accès\n\nBonjour' });

    function host(): HTMLElement {
      return fixture.nativeElement as HTMLElement;
    }

    it('sans `sendable`, aucun bouton (rendu F-203 inchangé)', () => {
      fixture.componentRef.setInput('block', mail);
      fixture.detectChanges();
      expect(host().querySelector('.copy-block-mark-sent')).toBeNull();
      expect(host().querySelector('.copy-block-sent')).toBeNull();
    });

    it('un bloc de code ne se marque jamais envoyé', () => {
      fixture.componentRef.setInput('sendable', true);
      fixture.detectChanges();
      expect(host().querySelector('.copy-block-mark-sent')).toBeNull();
    });

    it('montre « Marquer envoyé » et émet au clic', () => {
      fixture.componentRef.setInput('block', mail);
      fixture.componentRef.setInput('sendable', true);
      fixture.detectChanges();
      const emitted = jasmine.createSpy('markSent');
      component.markSent.subscribe(emitted);
      const button = host().querySelector<HTMLButtonElement>('.copy-block-mark-sent');
      expect(button?.textContent).toContain('Marquer envoyé');
      button?.click();
      expect(emitted).toHaveBeenCalled();
    });

    it('envoyé : « Envoyé · jj/MM HH:mm » et « Annuler » qui émet', () => {
      fixture.componentRef.setInput('block', mail);
      fixture.componentRef.setInput('sendable', true);
      fixture.componentRef.setInput('sentAt', new Date(2026, 9, 24, 17, 42).toISOString());
      fixture.detectChanges();
      expect(host().querySelector('.copy-block-sent')?.textContent).toContain('Envoyé · 24/10 17:42');
      expect(host().querySelector('.copy-block-mark-sent')).toBeNull();
      const cancelled = jasmine.createSpy('cancelSent');
      component.cancelSent.subscribe(cancelled);
      host().querySelector<HTMLButtonElement>('.copy-block-sent-cancel')?.click();
      expect(cancelled).toHaveBeenCalled();
    });

    it('lecture seule : l’état se lit, sans bouton ni « Annuler »', () => {
      fixture.componentRef.setInput('block', mail);
      fixture.componentRef.setInput('sendable', true);
      fixture.componentRef.setInput('sendReadOnly', true);
      fixture.detectChanges();
      expect(host().querySelector('.copy-block-mark-sent')).toBeNull();
      fixture.componentRef.setInput('sentAt', new Date().toISOString());
      fixture.detectChanges();
      expect(host().querySelector('.copy-block-sent')).not.toBeNull();
      expect(host().querySelector('.copy-block-sent-cancel')).toBeNull();
    });

    it('pendant l’appel, le bouton est désactivé', () => {
      fixture.componentRef.setInput('block', mail);
      fixture.componentRef.setInput('sendable', true);
      fixture.componentRef.setInput('sendBusy', true);
      fixture.detectChanges();
      expect(host().querySelector<HTMLButtonElement>('.copy-block-mark-sent')?.disabled).toBeTrue();
    });

    it('« À : » est présenté à part et n’est pas dans le corps', () => {
      fixture.componentRef.setInput('block', mail);
      fixture.detectChanges();
      expect(host().querySelector('.copy-block-recipient .copy-block-subject-text')?.textContent).toContain('Zahi');
      expect(component.parts().body).toBe('Bonjour');
      expect(host().querySelector('.copy-block-rich')?.textContent).not.toContain('Zahi');
    });
  });
});

