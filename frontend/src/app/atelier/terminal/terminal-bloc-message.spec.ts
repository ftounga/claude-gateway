import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { AtelierTerminalComponent } from './atelier-terminal.component';

/**
 * **Messages à copier** (F-203 / SF-203-02) : dans le terminal, un bloc ```mail / ```message d'une
 * réponse s'affiche dans un encart copiable (app-copy-block, peau sombre) ; le reste de la réponse —
 * code compris — est rendu comme avant.
 */
describe('AtelierTerminalComponent — encart message à copier (F-203 / SF-203-02)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;

  const mailReply = [
    'Voici une proposition de mail :',
    '',
    '```mail',
    'Objet : Point projet',
    '',
    'Bonjour **Alice**,',
    '',
    '- premier point',
    '- second point',
    '```',
    '',
    'Je peux raccourcir si besoin.',
  ].join('\n');

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierTerminalComponent);
    component = fixture.componentInstance;
    component.projectName = 'mon-projet';
  });

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('rend un bloc ```mail dans un encart copiable, prose conservée autour', () => {
    component.messages = [{ id: 'a1', role: 'ASSISTANT', content: mailReply, actions: [] }];
    fixture.detectChanges();

    const encart = host().querySelector('app-copy-block.terminal-message-block');
    expect(encart).withContext('encart message absent').not.toBeNull();
    expect(encart?.classList).toContain('copy-block-host--dark');
    expect(encart?.querySelector('.copy-block-title')?.textContent).toContain('E-mail');
    expect(encart?.querySelector('.copy-block-subject-text')?.textContent).toContain('Point projet');
    expect(encart?.querySelector('.copy-block-rich strong')?.textContent).toBe('Alice');
    expect(encart?.querySelector('.copy-block-rich')?.textContent).not.toContain('Objet');

    const agents = Array.from(host().querySelectorAll('.terminal-agent')).map((el) => el.textContent ?? '');
    expect(agents.length).toBe(2);
    expect(agents[0]).toContain('Voici une proposition de mail');
    expect(agents[1]).toContain('Je peux raccourcir si besoin.');
    // La balise brute n'apparaît jamais.
    expect(host().textContent).not.toContain('```mail');
  });

  it('rend un bloc ```message en encart « Message »', () => {
    component.messages = [
      { id: 'a1', role: 'ASSISTANT', content: '```message\nSalut, c’est bon pour moi.\n```', actions: [] },
    ];
    fixture.detectChanges();

    const encart = host().querySelector('app-copy-block.terminal-message-block');
    expect(encart?.querySelector('.copy-block-title')?.textContent).toContain('Message');
    expect(encart?.querySelector('.copy-block-subject')).toBeNull();
  });

  it('laisse un bloc de code dans la prose, rendu comme avant (pas d’encart)', () => {
    component.messages = [
      { id: 'a1', role: 'ASSISTANT', content: 'Lance :\n\n```bash\nnpm test\n```', actions: [] },
    ];
    fixture.detectChanges();

    expect(host().querySelector('app-copy-block')).toBeNull();
    const agents = host().querySelectorAll('.terminal-agent');
    expect(agents.length).toBe(1);
    expect(agents[0].querySelector('pre code')?.textContent).toContain('npm test');
  });

  it('rend un message sans bloc exactement comme avant (un seul conteneur Markdown)', () => {
    component.messages = [{ id: 'a1', role: 'ASSISTANT', content: 'Réponse **simple**.', actions: [] }];
    fixture.detectChanges();

    expect(host().querySelector('app-copy-block')).toBeNull();
    const agents = host().querySelectorAll('.terminal-agent');
    expect(agents.length).toBe(1);
    expect(agents[0].querySelector('strong')?.textContent).toBe('simple');
  });

  it('présente l’encart dans la partie « essentiel »', () => {
    component.messages = [
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: '<<essentiel>>\n```mail\nBonjour,\n\nC’est validé.\n```\n<</essentiel>>\nDétail ensuite.',
        actions: [],
      },
    ];
    fixture.detectChanges();

    const essential = host().querySelector('.terminal-essential');
    expect(essential?.querySelector('app-copy-block.terminal-message-block')).not.toBeNull();
  });

  it('présente l’encart dans le flux en direct une fois le bloc clos, pas avant', () => {
    component.streaming = { status: 'running', tokens: null, text: 'Brouillon :\n\n```mail\nBonjour,', plan: [], blocks: [] };
    fixture.detectChanges();
    expect(host().querySelector('app-copy-block')).toBeNull();

    component.streaming = { status: 'running', tokens: null, text: mailReply, plan: [], blocks: [] };
    fixture.detectChanges();
    expect(host().querySelector('app-copy-block.terminal-message-block')).not.toBeNull();
  });
});
