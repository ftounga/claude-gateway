import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { MailService } from '../../core/services/mail.service';
import { PagesService } from '../../core/services/pages.service';

/**
 * **LE RAIL « VOS QUESTIONS » EST EN TÊTE DU FIL SUR MOBILE — À LA RACINE** (F-158 / SF-158-22).
 *
 * <p>Constat PO (390 px) : le rail « Vos questions » (F-126) tombait dans la bande basse des boutons
 * flottants. Mesuré en Chrome, la cause n'était PAS que `order: -1` « ne prenait pas » (il prenait) :
 * la mise en tête reposait sur un artifice de peinture (`order`) alors que le rail restait DERNIER dans
 * le DOM — ordre de lecture/focus faux, et retombée silencieuse en bas à la moindre régression de
 * `display`. SF-158-22 rend le rail AVANT le fil dans le DOM ; la mise en tête mobile devient l'ordre
 * NATUREL. En desktop il reste à droite via `order: 1` (rendu inchangé).</p>
 *
 * <p>On MESURE le layout réel (ChromeHeadless), jamais une règle CSSOM (piège SF-158-10). Pour le
 * mobile, la fenêtre Karma est à 1440 px : on rejoue le rendu dans une <b>iframe de 390 px</b> où les
 * `@media (max-width: 819px)` s'évaluent NATIVEMENT contre le viewport de l'iframe. Pour le desktop, on
 * mesure directement dans la fenêtre Karma (1440 px), où la feuille mobile ne s'applique pas.</p>
 */
describe('AtelierTerminalComponent — rail « Vos questions » en tête (F-158 / SF-158-22)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let host: HTMLElement;

  beforeEach(async () => {
    const mail = jasmine.createSpyObj<MailService>('MailService', ['email']);
    mail.email.and.returnValue(of({}) as never);
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(of({}) as never);

    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: MailService, useValue: mail },
        { provide: PagesService, useValue: pages },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AtelierTerminalComponent);
    fixture.componentInstance.projectName = 'mon-projet';
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
  });

  afterEach(() => {
    host.remove();
    fixture.destroy();
  });

  /** Rend un terminal INTERACTIF portant au moins une question ⇒ classe `--railed` posée, rail présent. */
  function renderRailed(): void {
    const c = fixture.componentInstance;
    c.readOnly = false;
    c.messages = [
      { id: 'u1', role: 'USER', content: 'Ma premiere question', actions: [] },
      { id: 'a1', role: 'ASSISTANT', content: 'reponse', actions: [], terminal: [] },
      { id: 'u2', role: 'USER', content: 'Ma deuxieme question', actions: [] },
    ] as never;
    fixture.detectChanges();
  }

  /**
   * Rejoue le HTML rendu + TOUTES les feuilles injectées par Angular dans une iframe de `width` px, où
   * les `@media` s'évaluent nativement, puis exécute `measure` sur le document de l'iframe. `extraCss`
   * permet d'injecter une règle supplémentaire (ex. annuler la grille) pour prouver l'indépendance.
   */
  function inIframe(width: number, measure: (doc: Document, win: Window) => void, extraCss = ''): Promise<void> {
    const styleHtml =
      Array.from(document.head.querySelectorAll('style'))
        .map((s) => `<style>${s.textContent}</style>`)
        .join('\n') + (extraCss ? `<style>${extraCss}</style>` : '');

    return new Promise((resolve) => {
      const iframe = document.createElement('iframe');
      iframe.style.width = `${width}px`;
      iframe.style.height = '844px';
      iframe.style.border = '0';
      document.body.appendChild(iframe);
      const doc = iframe.contentDocument!;
      doc.open();
      doc.write(
        `<!doctype html><html><head><meta name="viewport" content="width=${width}">${styleHtml}</head><body>${host.outerHTML}</body></html>`,
      );
      doc.close();
      setTimeout(() => {
        try {
          measure(doc, iframe.contentWindow!);
        } finally {
          iframe.remove();
          resolve();
        }
      }, 150);
    });
  }

  it('DOM : le rail est rendu AVANT le fil dans le conteneur qui défile', () => {
    renderRailed();
    const sb = host.querySelector('.terminal-scrollback') as HTMLElement;
    expect(sb.classList.contains('terminal-scrollback--railed')).toBe(true);
    const rail = sb.querySelector('.terminal-qrail') as HTMLElement;
    const thread = sb.querySelector('.terminal-thread') as HTMLElement;
    expect(rail).withContext('.terminal-qrail absent').toBeTruthy();
    expect(thread).withContext('.terminal-thread absent').toBeTruthy();
    // Le rail précède le fil dans l'ordre du document (structure, pas peinture).
    expect(rail.compareDocumentPosition(thread) & Node.DOCUMENT_POSITION_FOLLOWING)
      .withContext('le rail doit précéder le fil dans le DOM')
      .toBeTruthy();
  });

  it('MOBILE (iframe 390 px, @media natif) : le rail est visuellement EN TÊTE du fil', async () => {
    renderRailed();
    await inIframe(390, (doc, win) => {
      const sb = doc.querySelector('.terminal-scrollback') as HTMLElement;
      const rail = doc.querySelector('.terminal-qrail') as HTMLElement;
      const thread = doc.querySelector('.terminal-thread') as HTMLElement;
      expect(win.innerWidth).toBe(390);
      expect(sb.classList.contains('terminal-scrollback--railed')).toBe(true);
      expect(win.getComputedStyle(sb).display).withContext('grille active en mobile').toBe('grid');
      expect(rail.getBoundingClientRect().top)
        .withContext('le rail doit être au-dessus du fil sur mobile')
        .toBeLessThan(thread.getBoundingClientRect().top);
    });
  });

  it('MOBILE : le rail reste EN TÊTE même sans la grille (garantie de STRUCTURE, pas de `order`)', async () => {
    renderRailed();
    // On annule la grille : si la mise en tête tenait à `order`, le rail retomberait sous le fil.
    // Comme elle vient de l'ordre DOM, le rail reste au-dessus.
    await inIframe(
      390,
      (doc, win) => {
        const sb = doc.querySelector('.terminal-scrollback') as HTMLElement;
        const rail = doc.querySelector('.terminal-qrail') as HTMLElement;
        const thread = doc.querySelector('.terminal-thread') as HTMLElement;
        expect(win.getComputedStyle(sb).display)
          .withContext('la grille est bien neutralisée pour ce test')
          .toBe('block');
        expect(rail.getBoundingClientRect().top)
          .withContext('sans grille, l\'ordre DOM garde le rail en tête')
          .toBeLessThan(thread.getBoundingClientRect().top);
      },
      '.terminal-scrollback--railed{display:block !important;}',
    );
  });

  it('DESKTOP (1440 px) : le rail reste à DROITE du fil, grille à deux pistes dont 232px — rendu inchangé', () => {
    renderRailed();
    // Aucune iframe : la fenêtre Karma (1440 px) n'active pas la feuille mobile → rendu desktop.
    const sb = host.querySelector('.terminal-scrollback') as HTMLElement;
    const rail = sb.querySelector('.terminal-qrail') as HTMLElement;
    const thread = sb.querySelector('.terminal-thread') as HTMLElement;

    const tracks = getComputedStyle(sb).gridTemplateColumns.trim().split(/\s+/);
    expect(tracks.length).withContext('desktop : deux pistes (fil + rail)').toBe(2);
    expect(tracks[1]).withContext('desktop : seconde piste (rail) = 232px').toBe('232px');

    // Malgré l'ordre DOM [rail, fil], `order: 1` repousse le rail en 2ᵉ piste : il est à DROITE du fil.
    expect(rail.getBoundingClientRect().left)
      .withContext('desktop : le rail doit rester à droite du fil')
      .toBeGreaterThan(thread.getBoundingClientRect().left);
  });
});
