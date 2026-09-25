import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { ChatComponent } from './chat.component';
import { ChatMessage } from '../core/models/chat.models';

/**
 * **Refonte mobile pro du Chat — largeur réelle & non-régression desktop** (F-160 / SF-160-01).
 *
 * <p>Deux garde-fous du cadrage F-160 :</p>
 * <ul>
 *   <li><b>D4 — jamais de scroll horizontal (test réel)</b> : dans un vrai DOM ChromeHeadless, avec
 *   un contenu volontairement large (bloc de code insécable + bulle utilisateur très longue), on
 *   MESURE `host.scrollWidth <= host.clientWidth` à 360 px puis 400 px, patron mobile appliqué. Un
 *   test « sanity » (sonde large de 900 px) prouve que la mesure détecte un vrai débordement — donc que
 *   les assertions vert-après ne sont pas des tautologies. La fenêtre Karma fait 1440 px : les règles `@media
 *   (max-width: 819px)` n'y sont pas actives, on les applique donc au layout réel (extraction des
 *   règles du chat déjà injectées, ré-application sans la garde media).</li>
 *   <li><b>D3 — zéro régression desktop</b> : à la largeur Karma (>= 820 px), les règles mobile ne
 *   s'appliquent pas — le composeur n'est PAS ancré (`position` != `sticky`). La CSSOM aplatie
 *   confirme que toute règle qui ancre le composeur vit sous `max-width: 819px`, et que les règles
 *   mobile du chat n'introduisent aucune couleur littérale (charte via jetons `--cg-*`).</li>
 * </ul>
 */
describe('ChatComponent — refonte mobile pro (F-160 / SF-160-01)', () => {
  let fixture: ComponentFixture<ChatComponent>;
  let component: ChatComponent;
  let httpMock: HttpTestingController;
  let host: HTMLElement;
  let injected: HTMLStyleElement | null = null;

  /** Répond aux appels d'initialisation de l'écran (formats + modèles + conversations). */
  function flushInit(): void {
    httpMock.expectOne('/api/file-formats').flush({
      documents: { mediaTypes: ['application/pdf'], maxBytes: 20971520 },
      attachments: { mediaTypes: ['application/pdf'], maxBytes: 33554432 },
    });
    httpMock
      .expectOne('/api/chat/models')
      .flush({ defaultModel: 'claude-opus-4-8', models: ['claude-opus-4-8'] });
    httpMock.expectOne('/api/conversations').flush([]);
  }

  /** Un fil avec une bulle utilisateur TRÈS longue et une réponse assistant contenant un bloc de code insécable. */
  function seedLargeThread(): void {
    const longCode = 'awsRdsCreateDbInstanceReadReplicaIdentifier'.repeat(40); // ~1700 car., insécable
    const messages: ChatMessage[] = [
      {
        id: 'u1',
        role: 'USER',
        content: 'MigrationRDSsansCoupureProcedureDeBasculeTresLongueQuestion'.repeat(12),
        model: null,
        createdAt: '2026-09-26T08:00:00Z',
      },
      {
        id: 'a1',
        role: 'ASSISTANT',
        content: '```bash\n' + longCode + '\n```',
        model: 'claude-opus-4-8',
        createdAt: '2026-09-26T08:00:01Z',
      },
    ];
    component.messages.set(messages);
    fixture.detectChanges();
  }

  /**
   * Interfaces CSSOM (media incluses) — aplatit les CSSStyleRule accessibles.
   */
  interface FlatRule {
    selectorText: string;
    cssText: string;
    style: CSSStyleDeclaration;
    mediaText: string;
  }

  function flatStyleRules(): FlatRule[] {
    const out: FlatRule[] = [];
    const walk = (rules: CSSRuleList, mediaText: string) => {
      for (const rule of Array.from(rules)) {
        if (rule instanceof CSSMediaRule) {
          walk(rule.cssRules, rule.media.mediaText);
        } else if (rule instanceof CSSStyleRule) {
          out.push({ selectorText: rule.selectorText, cssText: rule.cssText, style: rule.style, mediaText });
        }
      }
    };
    for (const sheet of Array.from(document.styleSheets)) {
      try {
        walk(sheet.cssRules, '');
      } catch {
        continue; // feuille cross-origin : ignorée
      }
    }
    return out;
  }

  /** Marqueurs des sélecteurs propres à l'écran de chat (évite d'attraper les règles d'autres composants). */
  const CHAT_MARKER = /chat-|message|composer|copy-block|conversation|sidebar|files-panel|files-item|files-empty|files-list|model-select|thread|markdown-body/;

  /**
   * Applique au layout réel les règles `@media (max-width: 819px)` DU CHAT déjà injectées par Angular,
   * sans la garde media — de sorte que le patron mobile agisse réellement à un viewport Karma de
   * 1440 px. Filtrées sur les marqueurs du chat : ni les feuilles globales, ni Material, ni les
   * autres composants (dont un `:host{overflow-x:hidden}` qui masquerait faussement un débordement).
   */
  function applyChatMobileRules(): void {
    let css = '';
    for (const sheet of Array.from(document.styleSheets)) {
      let rules: CSSRuleList;
      try {
        rules = sheet.cssRules;
      } catch {
        continue;
      }
      for (const rule of Array.from(rules)) {
        if (rule instanceof CSSMediaRule && /max-width:\s*819px/.test(rule.media.mediaText)) {
          for (const inner of Array.from(rule.cssRules)) {
            if (inner instanceof CSSStyleRule && CHAT_MARKER.test(inner.cssText)) {
              css += inner.cssText + '\n';
            }
          }
        }
      }
    }
    injected = document.createElement('style');
    injected.textContent = css;
    document.head.appendChild(injected);
  }

  /** Rend vrai si l'hôte déborde horizontalement à la largeur donnée. */
  function pageOverflowsAt(width: number): boolean {
    host.style.width = `${width}px`;
    void host.getBoundingClientRect();
    return host.scrollWidth > host.clientWidth;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChatComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideNoopAnimations()],
    }).compileComponents();

    fixture = TestBed.createComponent(ChatComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    flushInit();
    fixture.detectChanges();

    host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host); // ancré au document pour un layout réel
  });

  afterEach(() => {
    injected?.remove();
    injected = null;
    httpMock.verify();
    fixture.destroy();
  });

  // ------------------------------------------------------------------ D4 — largeur réelle
  it('à 360 px, un fil avec bloc de code insécable + bulle longue ne fait PAS défiler la page', () => {
    seedLargeThread();
    applyChatMobileRules();
    expect(pageOverflowsAt(360))
      .withContext("host.scrollWidth <= host.clientWidth attendu — l'écran doit clore l'axe horizontal")
      .toBe(false);
  });

  it('à 400 px, un fil avec bloc de code insécable + bulle longue ne fait PAS défiler la page', () => {
    seedLargeThread();
    applyChatMobileRules();
    expect(pageOverflowsAt(400))
      .withContext('host.scrollWidth <= host.clientWidth attendu à 400 px')
      .toBe(false);
  });

  it('sanity : la mesure détecte un vrai débordement (une sonde large de 900 px fait défiler l\'hôte)', () => {
    // Prouve que `pageOverflowsAt` mesure un LAYOUT réel (et non une tautologie) : une sonde
    // délibérément plus large que l'hôte le fait déborder. Les tests vert-après ci-dessus valent donc
    // bien confirmation que l'écran clôt l'axe horizontal, et non un simple no-op.
    const probe = document.createElement('div');
    probe.style.width = '900px';
    probe.style.height = '1px';
    host.appendChild(probe);
    try {
      expect(pageOverflowsAt(360)).toBe(true);
    } finally {
      probe.remove();
    }
  });

  // ------------------------------------------------------------------ D3 — non-régression desktop
  it('au viewport Karma (>= 820 px), le composeur n\'est PAS ancré (règle gardée sous 819 px)', () => {
    const composer = host.querySelector('.composer') as HTMLElement;
    expect(composer).withContext('.composer absent').toBeTruthy();
    const position = getComputedStyle(composer).position;
    expect(position)
      .withContext('le composeur ne doit être `sticky` que sous 819 px')
      .not.toBe('sticky');
    expect(position).not.toBe('fixed');
  });

  it('toute règle qui ancre le composeur (position: sticky) vit sous max-width: 819px', () => {
    const sticky = flatStyleRules().filter(
      (r) => /\.composer/.test(r.selectorText) && r.style.position === 'sticky',
    );
    expect(sticky.length).withContext('la règle d\'ancrage du composeur devrait exister').toBeGreaterThan(0);
    for (const r of sticky) {
      expect(r.mediaText)
        .withContext(`ancrage du composeur hors media : ${r.selectorText}`)
        .toMatch(/max-width:\s*819px/);
    }
  });

  it('les règles mobile du chat n\'introduisent aucune couleur littérale (charte via jetons --cg-*)', () => {
    const mobileChat = flatStyleRules().filter(
      (r) => /max-width:\s*819px/.test(r.mediaText) && CHAT_MARKER.test(r.cssText),
    );
    expect(mobileChat.length).toBeGreaterThan(0);
    for (const r of mobileChat) {
      expect(r.cssText)
        .withContext(`hex littéral interdit dans une règle mobile du chat : ${r.selectorText}`)
        .not.toMatch(/#[0-9a-fA-F]{3,6}\b/);
      expect(r.cssText)
        .withContext(`rgb() littéral interdit dans une règle mobile du chat : ${r.selectorText}`)
        .not.toMatch(/\brgb\(/);
    }
  });

  it('la bulle utilisateur porte un filet accent et un rayon asymétrique sous 819 px (D7)', () => {
    const rules = flatStyleRules().filter(
      (r) => /max-width:\s*819px/.test(r.mediaText) && /\.message\.user/.test(r.selectorText),
    );
    expect(rules.length).withContext('règle .message.user mobile absente').toBeGreaterThan(0);
    const css = rules.map((r) => r.cssText).join('\n');
    expect(css).withContext('filet accent --cg-orange attendu').toContain('--cg-orange');
    expect(css).withContext('rayon asymétrique attendu').toMatch(/border-radius/);
  });
});
