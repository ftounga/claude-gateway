import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { JarvisConstellationComponent, breadcrumb } from './jarvis-constellation.component';
import { LABEL_LIMIT, anchorFor, fitLabel, layoutUniverse } from './jarvis-constellation-layout';
import { JarvisSceneBus } from './jarvis-scene-bus.service';
import { testUniverse } from './jarvis-universe.fixture';

/** Rapport de contraste WCAG entre deux couleurs hexadécimales. */
function contrast(a: string, b: string): number {
  const lum = (hex: string) => {
    const [r, g, bl] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
      .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

@Component({
  imports: [JarvisConstellationComponent],
  template: `<app-jarvis-constellation [subjectId]="subject()" [speaking]="speaking()" (choose)="chosen = $event">
               <button class="orb">orbe</button>
             </app-jarvis-constellation>`,
})
class HostComponent {
  readonly subject = signal<string | null>('s-forge');
  readonly speaking = signal(false);
  chosen: string | null = null;
}

describe('F-234 / SF-234-03 — disposition de la constellation', () => {
  it('même univers ⇒ mêmes positions ; le client courant en haut', () => {
    const a = layoutUniverse(testUniverse(), 's-forge', false);
    const b = layoutUniverse(testUniverse(), 's-forge', false);
    expect(a).toEqual(b);
    expect(a.clients[0].key).toBe('h-cagip');
    expect(a.clients[0].y).toBeLessThan(0);
    expect(a.clients[0].x).toBeCloseTo(0, 1);
  });

  it('téléphone : les sujets des autres clients ne sont pas rendus ; desktop : en grappe', () => {
    const phone = layoutUniverse(testUniverse(), 's-forge', true);
    expect(phone.subjects.every((s) => s.clientKey === 'h-cagip')).toBeTrue();
    expect(phone.clients.length).toBe(2);
    const desktop = layoutUniverse(testUniverse(), 's-forge', false);
    expect(desktop.subjects.some((s) => s.clientKey === 'h-eden' && s.ring === 'cluster')).toBeTrue();
  });

  it('le client courant suit le sujet courant', () => {
    expect(layoutUniverse(testUniverse(), 's-runner', true).currentClientKey).toBe('h-eden');
    expect(layoutUniverse(null, 's-runner', true).clients).toEqual([]);
  });

  it('F-253 / SF-253-07 — une étiquette qui sortirait de l\'écran passe sous son point, recentrée ; sinon inchangée', () => {
    const short = { x: 66.5, y: 1.8, anchor: 'start' as const };
    expect(fitLabel({ x: 62, y: 0 }, short, 'web', 6)).toEqual(short);
    const fitted = fitLabel({ x: 62, y: 0 }, short, 'data-platform', 6);
    expect(fitted.anchor).toBe('middle');
    expect(fitted.y).toBeGreaterThan(0);
    const half = ('data-platform'.length * 6 * 0.6) / 2;
    expect(fitted.x + half).toBeLessThanOrEqual(LABEL_LIMIT);
    expect(fitted.x - half).toBeGreaterThanOrEqual(-LABEL_LIMIT);
    const left = fitLabel({ x: -62, y: -10 }, { x: -66.5, y: -8.2, anchor: 'end' }, 'data-platform-très-long', 6);
    expect(left.x - ('data-platform-très-long'.length * 3.6) / 2).toBeGreaterThanOrEqual(-LABEL_LIMIT);
    expect(left.y).toBeLessThan(-10);
  });

  it('les noms partent vers l\'extérieur', () => {
    expect(anchorFor(40)).toBe('start');
    expect(anchorFor(-40)).toBe('end');
    expect(anchorFor(0)).toBe('middle');
  });

  it('fil d\'Ariane : chantier cité ⇒ client › sujet › chantier › étape ; sinon le sujet courant', () => {
    const u = testUniverse();
    expect(breadcrumb(u, [{ kind: 'chantier', key: 's-forge#3', label: 'x', clientKey: 'h-cagip', subjectId: 's-forge' }],
      's-doc')).toEqual(['CAGIP', 'Migration Forge', 'Chantier 3', 'Étape 3 — Basculer']);
    expect(breadcrumb(u, [], 's-doc')).toEqual(['CAGIP', 'Doc archi']);
    expect(breadcrumb(u, [{ kind: 'client', key: 'h-eden', label: 'Edenred', clientKey: 'h-eden' }], 's-doc'))
      .toEqual(['Edenred']);
  });

  it('contraste des noms sur navy ≥ 4,5:1 (crème, et orange de surbrillance)', () => {
    expect(contrast('#F5EFE3', '#141D33')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('#F0954F', '#141D33')).toBeGreaterThanOrEqual(4.5);
    expect(contrast('#F0954F', '#0B1020')).toBeGreaterThanOrEqual(4.5);
  });
});

describe('F-234 / SF-234-03 — la constellation à l\'écran', () => {
  let bus: JarvisSceneBus;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    bus = TestBed.inject(JarvisSceneBus);
    bus.setUniverse(testUniverse());
  });

  afterEach(() => bus.ngOnDestroy());

  it('dessine clients et sujets autour de l\'orbe projetée ; toucher un sujet = le choisir', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.cst__orb .orb')).not.toBeNull();
    expect(el.querySelectorAll('.cst__client').length).toBe(2);
    const doc = el.querySelector('.cst__subject[data-key="s-doc"]') as SVGGElement;
    expect(doc.getAttribute('role')).toBe('button');
    expect(doc.getAttribute('aria-label')).toBe('Passer sur Doc archi');
    doc.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(fixture.componentInstance.chosen).toBe('s-doc');
    doc.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    expect(fixture.componentInstance.chosen).toBe('s-doc');
  });

  it('un nom cité s\'allume, un fil le relie à l\'orbe ; le chantier cité apparaît ; le fil d\'Ariane suit', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    bus.setSubject('s-forge');
    bus.turnStarted();
    bus.text('🎙 Le chantier 3 avance chez CAGIP.\n');
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.cst__client[data-key="h-cagip"]')?.classList).toContain('is-lit');
    expect(el.querySelectorAll('.cst__thread').length).toBe(2);
    expect(el.querySelector('.cst__mark[data-kind="chantier"] text')?.textContent).toContain('Ch. 3 — Bascule DNS');
    expect(el.querySelector('.cst__crumbs')?.textContent).toContain('Étape 3 — Basculer');
    bus.aborted();
  });

  it('téléphone : les sujets des autres clients ne sont pas dans le DOM', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const cst = fixture.debugElement.children[0].componentInstance as JarvisConstellationComponent;
    cst.narrow.set(true);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.cst__subject[data-key="s-runner"]')).toBeNull();
    cst.narrow.set(false);
    fixture.detectChanges();
    expect(el.querySelector('.cst__subject[data-key="s-runner"]')).not.toBeNull();
  });

  it('le lecteur d\'écran n\'entend rien pendant que Jarvis parle', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.speaking.set(true);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.cst__live')?.textContent?.trim()).toBe('');
  });
});
