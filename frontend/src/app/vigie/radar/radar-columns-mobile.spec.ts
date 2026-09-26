import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of } from 'rxjs';

import { BoardCommitment, RadarBoard, RadarCommitmentView } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { RadarColumnsComponent } from './radar-columns.component';

/**
 * **Les sections d'engagements mobile pro : une colonne de cartes, sans débordement, desktop intact**
 * (F-160 / SF-160-03).
 *
 * <p>SF-160-03 empile « À faire par moi » / « Sujets en cours » / « J'attends des autres » en cartes sur
 * UNE colonne sous 819 px (silhouette de la maquette validée PO), en palette 100 % charte claire.
 * Garde-fous, sur le modèle imposé par le cadrage :</p>
 *
 * <ul>
 *   <li><b>D4 — jamais de scroll horizontal</b> : mesure en DOM réel (ChromeHeadless) ; un engagement au
 *       libellé volontairement insécable ne fait pas défiler la page à 360/400 px avec le patron mobile ;</li>
 *   <li><b>D3 — non-régression desktop (CSSOM)</b> : la carte mobile (rayon 14px) vit sous
 *       <code>max-width: 819px</code> ; aucune règle F-160 ne fuit sur le desktop, aucune couleur en dur ;</li>
 *   <li><b>D3 — non-régression desktop (calculé)</b> : à la fenêtre Karma (media inactive), la grille
 *       garde ses TROIS colonnes d'avant F-160 et la carte son rayon d'origine.</li>
 * </ul>
 *
 * <p>La fenêtre Karma n'active pas <code>max-width: 819px</code> : pour la mesure de layout on ré-applique
 * au document les règles mobile DE LA FEUILLE radar-columns déjà injectées par Angular, sans la garde
 * media — comme <code>terminal-largeur-reelle.spec.ts</code>.</p>
 */
describe('RadarColumnsComponent — sections d\'engagements mobile pro (F-160 / SF-160-03)', () => {
  let fixture: ComponentFixture<RadarColumnsComponent>;
  let host: HTMLElement;
  let injected: HTMLStyleElement | null = null;

  const commitment = (extra: Partial<RadarCommitmentView> = {}): RadarCommitmentView => ({
    id: 'c1', subjectId: 's1', subjectName: 'Agenor — ossature Terraform', direction: 'ME_TO_OTHER',
    description: 'Répondre à Daoud (tfbackend à corriger)', fromPerson: null, toPerson: null, otherPerson: null,
    dueDate: '2026-09-12', dueDeduced: false, status: 'OPEN', certainty: 'CERTAIN', sovereign: false,
    disowned: false, evidenceIds: ['e1'], followUpDueOn: null, followUpDue: false, ...extra,
  });

  const item = (c: Partial<RadarCommitmentView> = {}, extra: Partial<BoardCommitment> = {}): BoardCommitment => ({
    commitment: commitment(c), source: 'TEAMS_MESSAGE', sourceAt: '2026-09-11T09:00:00Z',
    deepLink: 'https://teams.microsoft.com/l/message/1', question: false, due: true, overdueDays: 3, ...extra,
  });

  const board = (): RadarBoard => ({
    toDo: [item()],
    subjects: [],
    waiting: [
      item({ id: 'c3', direction: 'OTHER_TO_ME', description: 'DSI — ouverture du port 22',
        fromPerson: { id: 'p1', displayName: 'DSI CAGIP' }, followUpDue: true },
      { overdueDays: 0, source: 'PASTED_MAIL' }),
    ],
  });

  function render(): void {
    const radar = jasmine.createSpyObj<RadarService>('RadarService', ['board', 'correctCommitment', 'closeSubject',
      'confirmClosure', 'rejectClosure', 'dismissWake', 'setSubjectState', 'undo']);
    radar.board.and.returnValue(of(board()));
    const snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    const dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);

    TestBed.configureTestingModule({
      imports: [RadarColumnsComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: RadarService, useValue: radar },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialog, useValue: dialog },
      ],
    });
    fixture = TestBed.createComponent(RadarColumnsComponent);
    fixture.componentRef.setInput('hostId', 'h1');
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
    document.body.appendChild(host);
  }

  afterEach(() => {
    injected?.remove();
    injected = null;
    fixture?.destroy();
  });

  interface FlatRule { selectorText: string; cssText: string; mediaText: string; }

  function flatStyleRules(): FlatRule[] {
    const out: FlatRule[] = [];
    const walk = (rules: CSSRuleList, mediaText: string) => {
      for (const rule of Array.from(rules)) {
        if (rule instanceof CSSMediaRule) {
          walk(rule.cssRules, rule.media.mediaText);
        } else if (rule instanceof CSSStyleRule) {
          out.push({ selectorText: rule.selectorText, cssText: rule.cssText, mediaText });
        }
      }
    };
    for (const sheet of Array.from(document.styleSheets)) {
      try {
        walk(sheet.cssRules, '');
      } catch {
        continue;
      }
    }
    return out;
  }

  /** Ré-applique les règles `max-width: 819px` de la FEUILLE radar-columns, sans la garde media. */
  function applyMobileRules(): void {
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
            if (inner.cssText.includes('radar-columns')) {
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

  /**
   * Injecte dans le titre du premier engagement une sonde mono INSÉCABLE très large, mesure si LA PAGE
   * (l'hôte du composant) déborde à `width`, puis nettoie.
   */
  function pageOverflowsAt(width: number): boolean {
    host.style.width = `${width}px`;
    const title = host.querySelector('.radar-columns__t') as HTMLElement;
    expect(title).withContext('aucun engagement rendu').toBeTruthy();

    const probe = document.createElement('span');
    probe.className = 'sf16003-probe';
    probe.style.whiteSpace = 'nowrap';
    probe.textContent = 'AgenorOssatureTerraformInsecable'.repeat(30);
    title.appendChild(probe);

    void host.getBoundingClientRect();
    const overflow = host.scrollWidth > host.clientWidth;
    probe.remove();
    return overflow;
  }

  // ------------------------------------------------------------------ D4 — jamais de scroll-x
  it('à 360 px, un engagement au libellé très large ne fait PAS défiler la page', () => {
    render();
    applyMobileRules();
    expect(pageOverflowsAt(360))
      .withContext('la carte d\'engagement devrait clore l\'axe horizontal — host.scrollWidth <= host.clientWidth')
      .toBe(false);
  });

  it('à 400 px, un engagement au libellé très large ne fait PAS défiler la page', () => {
    render();
    applyMobileRules();
    expect(pageOverflowsAt(400))
      .withContext('la carte d\'engagement devrait clore l\'axe horizontal — host.scrollWidth <= host.clientWidth')
      .toBe(false);
  });

  // ------------------------------------------------------------------ D3 — non-régression desktop
  it('CSSOM : la carte de section mobile (rayon 14px) ne vit QUE sous max-width: 819px', () => {
    render();
    const cardRules = flatStyleRules().filter(
      (r) => r.selectorText.includes('radar-columns__panel') && /border-radius:\s*14px/.test(r.cssText),
    );
    expect(cardRules.length)
      .withContext('la règle de carte de section mobile (rayon 14px) devrait exister')
      .toBeGreaterThan(0);
    for (const rule of cardRules) {
      expect(/max-width:\s*819px/.test(rule.mediaText))
        .withContext(`fuite desktop : "${rule.selectorText}" hors @media (media="${rule.mediaText}")`)
        .toBe(true);
    }
  });

  it('CSSOM : les règles F-160 radar-columns n\'introduisent aucune couleur hex/rgb nouvelle (jetons --cg-*)', () => {
    render();
    const mobileRules = flatStyleRules().filter(
      (r) => /max-width:\s*819px/.test(r.mediaText) && r.selectorText.includes('radar-columns'),
    );
    expect(mobileRules.length).toBeGreaterThan(0);
    for (const rule of mobileRules) {
      expect(rule.cssText)
        .withContext(`couleur en dur dans "${rule.selectorText}"`)
        .not.toMatch(/#[0-9a-fA-F]{3,6}\b/);
      expect(rule.cssText)
        .withContext(`rgb() en dur dans "${rule.selectorText}"`)
        .not.toMatch(/\brgb\(/);
    }
  });

  it('calculé : à la fenêtre Karma (media inactive), la grille garde ses TROIS colonnes d\'avant F-160', () => {
    render();
    const grid = host.querySelector('.radar-columns') as HTMLElement;
    const panel = host.querySelector('.radar-columns__panel') as HTMLElement;
    // media 819 inactive : trois pistes de grille (structure desktop inchangée)...
    const tracks = getComputedStyle(grid).gridTemplateColumns.trim().split(/\s+/);
    expect(tracks.length)
      .withContext('la grille desktop à 3 colonnes ne doit pas être aplatie hors media')
      .toBe(3);
    // ...et la carte n'a PAS le rayon 14px du mobile.
    expect(getComputedStyle(panel).borderTopLeftRadius)
      .withContext('le rayon 14px mobile ne doit pas fuir sur le desktop')
      .not.toBe('14px');
  });
});
