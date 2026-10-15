import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import {
  TERMINAL_BAR_COMPACT_MIN,
  TERMINAL_BAR_FULL_MIN,
  TerminalBarTier,
  terminalBarTier,
} from './terminal-bar-tier';
import { MailService } from '../../core/services/mail.service';
import { PagesService } from '../../core/services/pages.service';

/**
 * **L'en-tête du terminal sur un écran de portable** (F-200 / SF-200-01).
 *
 * <p>Constat PO : sur l'écran du PC seul, les boutons de l'en-tête se superposaient (nom du client,
 * Nouveau départ…). On MESURE ici, dans un vrai DOM ChromeHeadless, l'en-tête chargé au maximum
 * (fil d'Ariane avec client, dossier, budget, coût, moteur, « connecté », run en cours, neuf
 * actions) aux cinq largeurs d'écran courantes : aucun débordement, aucune superposition, une seule
 * ligne. La vue terminal remplace toute la grille de l'Atelier : la largeur de l'en-tête est celle
 * de la fenêtre.</p>
 */
describe('AtelierTerminalComponent — en-tête à paliers (F-200 / SF-200-01)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let component: AtelierTerminalComponent;
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
    component = fixture.componentInstance;
    host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
  });

  afterEach(() => {
    fixture.destroy();
    host.remove();
  });

  /** L'en-tête chargé au MAXIMUM : tout ce qu'il peut porter en même temps. */
  function renderLoaded(width: number): HTMLElement {
    component.projectName = 'refonte-portail-souscription-entreprises';
    component.hostName = 'CAGIP — Ingénierie';
    component.localFolder = 'C:\\Users\\francky\\dev\\refonte-portail-souscription-entreprises';
    component.instructionsPath = 'CLAUDE.md';
    component.pendingActions.set(3);
    component.gitProject = true;
    // Sur la machine (dossier + « ma machine — en ligne · vu il y a … ») : la pastille de moteur la
    // plus longue. « Réinitialiser » (bac hébergé seulement) est vérifié à part, dans le menu.
    component.engine = 'LOCAL_MACHINE';
    component.runnerStatus = { connected: true, paired: true, lastSeenAt: new Date().toISOString() };
    component.live = true;
    component.submitting = true;
    component.askBeforeBash = true;
    host.style.display = 'block';
    host.style.width = `${width}px`;
    component.barWidth.set(width);
    fixture.detectChanges();
    // Budget et coût lisent des services réels : on pose leur rendu compact le plus large.
    stub('app-weekly-budget', '1 234,56 € / 5 000,00 €', 12, 64);
    stub('app-project-cost', '1 234,56 € · 12 345,67 €', 11, 0);
    const bar = host.querySelector('.terminal-bar') as HTMLElement;
    void bar.getBoundingClientRect();
    return bar;
  }

  function stub(selector: string, text: string, fontSize: number, gauge: number): void {
    const el = host.querySelector(selector) as HTMLElement;
    el.innerHTML =
      `<span style="display:inline-flex;align-items:center;gap:8px;white-space:nowrap;font-size:${fontSize}px">` +
      `${text}${gauge ? `<span style="display:inline-block;width:${gauge}px;height:6px"></span>` : ''}</span>`;
  }

  /** Les éléments visibles de la barre : ses enfants, et ceux du groupe d'actions. */
  function barItems(bar: HTMLElement): HTMLElement[] {
    const actions = bar.querySelector('.terminal-bar-actions') as HTMLElement;
    return [
      ...Array.from(bar.children).filter((el) => el !== actions),
      ...Array.from(actions.children),
    ].filter((el): el is HTMLElement => el instanceof HTMLElement && el.getBoundingClientRect().width > 0);
  }

  function overlaps(a: DOMRect, b: DOMRect): boolean {
    const tol = 0.5;
    return a.left < b.right - tol && b.left < a.right - tol && a.top < b.bottom - tol && b.top < a.bottom - tol;
  }

  describe('choix du palier (fonction pure)', () => {
    it('largeur inconnue ⇒ complet (rendu historique)', () => {
      expect(terminalBarTier(0, false)).toBe('full');
    });

    it('fenêtre téléphone ⇒ téléphone, quelle que soit la largeur', () => {
      expect(terminalBarTier(1920, true)).toBe('narrow');
    });

    it('en-tête < 820 px (grille, mosaïque) ⇒ téléphone', () => {
      expect(terminalBarTier(819, false)).toBe('narrow');
    });

    it('écrans de portable ⇒ serré puis compact ; très grand écran ⇒ complet', () => {
      for (const w of [820, 1280, 1366, 1440, 1536, TERMINAL_BAR_COMPACT_MIN - 1]) {
        expect(terminalBarTier(w, false)).withContext(`${w}px`).toBe('tight');
      }
      for (const w of [TERMINAL_BAR_COMPACT_MIN, 1920, TERMINAL_BAR_FULL_MIN - 1]) {
        expect(terminalBarTier(w, false)).withContext(`${w}px`).toBe('compact');
      }
      expect(terminalBarTier(TERMINAL_BAR_FULL_MIN, false)).toBe('full');
      expect(terminalBarTier(3840, false)).toBe('full');
    });
  });

  // Les cinq écrans du cadrage, plus le seuil de chaque palier (le cas le plus serré de chacun).
  for (const width of [1280, 1366, 1440, 1536, 1920, TERMINAL_BAR_COMPACT_MIN, TERMINAL_BAR_FULL_MIN]) {
    describe(`à ${width} px`, () => {
      it('rien ne déborde ni ne se superpose, et la barre tient sur une ligne', () => {
        const bar = renderLoaded(width);
        expect(bar.scrollWidth).withContext('débordement horizontal').toBeLessThanOrEqual(bar.clientWidth);
        const items = barItems(bar);
        expect(items.length).toBeGreaterThan(5);
        const barRect = bar.getBoundingClientRect();
        for (let i = 0; i < items.length; i++) {
          const a = items[i].getBoundingClientRect();
          expect(a.right).withContext(`${items[i].className} sort de la barre`).toBeLessThanOrEqual(barRect.right + 0.5);
          for (let j = i + 1; j < items.length; j++) {
            expect(overlaps(a, items[j].getBoundingClientRect()))
              .withContext(`${items[i].className} chevauche ${items[j].className}`)
              .toBe(false);
          }
        }
        // Une seule ligne : tous les éléments partagent la même bande verticale.
        const tops = items.map((el) => el.getBoundingClientRect());
        const minBottom = Math.min(...tops.map((r) => r.bottom));
        const maxTop = Math.max(...tops.map((r) => r.top));
        const layout = items
          .map((el) => `${el.className.split(' ')[0] || el.tagName}:${Math.round(el.getBoundingClientRect().width)}`)
          .join(' ');
        expect(maxTop).withContext(`la barre est passée à la ligne — ${layout}`).toBeLessThan(minBottom);
      });

      it('les actions primaires restent en clair, au bon palier', () => {
        const bar = renderLoaded(width);
        expect(bar.querySelector('.terminal-interrupt')).withContext('Interrompre').toBeTruthy();
        expect(bar.querySelector('.terminal-todo')).withContext('à faire').toBeTruthy();
        expect(bar.querySelector('.terminal-guard')).withContext('Validation').toBeTruthy();
        const compact = width < TERMINAL_BAR_FULL_MIN;
        const expected: TerminalBarTier = !compact ? 'full' : width < TERMINAL_BAR_COMPACT_MIN ? 'tight' : 'compact';
        expect(component.barTier()).toBe(expected);
        expect(!!bar.querySelector('.terminal-overflow')).withContext('menu ⋯').toBe(compact);
        expect(!!bar.querySelector('.terminal-restart')).withContext('Nouveau départ en clair').toBe(!compact);
      });
    });
  }

  it('palier compact : le menu ⋯ porte les actions secondaires, sans doublon des primaires', async () => {
    const bar = renderLoaded(1366);
    component.engine = 'HOSTED_SANDBOX'; // « Réinitialiser » n'existe que sur le bac hébergé
    fixture.detectChanges();
    (bar.querySelector('.terminal-overflow') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    const panel = document.querySelector('.terminal-overflow-menu') as HTMLElement;
    expect(panel).withContext('menu ⋯ non ouvert').toBeTruthy();
    const labels = Array.from(panel.querySelectorAll('[mat-menu-item]')).map((el) => el.textContent?.trim() ?? '');
    for (const expected of ['CLAUDE.md', 'Fichiers', 'Publier', 'Réinitialiser', 'Compacter maintenant', 'Nouveau départ', 'Quitter']) {
      expect(labels.some((l) => l.includes(expected))).withContext(expected).toBeTrue();
    }
    expect(labels.some((l) => l.includes('à faire'))).withContext('« à faire » en double').toBeFalse();
    expect(labels.some((l) => l.includes('Validation'))).withContext('Validation en double').toBeFalse();
  });

  it('palier compact : une entrée du menu émet le même événement que son bouton', async () => {
    const bar = renderLoaded(1280);
    const restart = spyOn(component.restart, 'emit');
    (bar.querySelector('.terminal-overflow') as HTMLButtonElement).click();
    fixture.detectChanges();
    await fixture.whenStable();
    (document.querySelector('.terminal-overflow-menu .terminal-restart') as HTMLButtonElement).click();
    expect(restart).toHaveBeenCalled();
  });

  it('le palier suit la largeur RÉELLE de l\'en-tête (relevé du ResizeObserver), pas la fenêtre', () => {
    component.onBarResize(1300);
    expect(component.barTier()).toBe('tight');
    component.onBarResize(TERMINAL_BAR_COMPACT_MIN + 40);
    expect(component.barTier()).toBe('compact');
    component.onBarResize(TERMINAL_BAR_FULL_MIN + 40);
    expect(component.barTier()).toBe('full');
    component.onBarResize(700);
    expect(component.barTier()).toBe('narrow');
  });

  it('un relevé qui ne change pas de palier ne relance rien', () => {
    component.onBarResize(1300);
    component.onBarResize(1310);
    expect(component.barWidth()).toBe(1300);
  });

  it('l\'hôte du terminal est observé (largeur du conteneur), et lâché à la destruction', () => {
    const observe = spyOn(ResizeObserver.prototype, 'observe').and.callThrough();
    const disconnect = spyOn(ResizeObserver.prototype, 'disconnect').and.callThrough();
    const other = TestBed.createComponent(AtelierTerminalComponent);
    expect(observe).toHaveBeenCalledWith(other.nativeElement);
    other.destroy();
    expect(disconnect).toHaveBeenCalled();
  });
});
