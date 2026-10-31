import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AtelierNextStep, AtelierNextStepsChantier } from '../../core/models/atelier.models';
import {
  TerminalNextStepsComponent, composeLaunch, nextStepBadge, nextStepBlockedText, prerequisiteGroups,
} from './terminal-next-steps.component';

/** **Les suites du chantier en deux colonnes, prérequis cochables** (F-209 / SF-209-02). */
describe('F-209 / SF-209-02 — suites en deux colonnes', () => {
  const chantier: AtelierNextStepsChantier = {
    number: 4, done: 5, total: 14, title: 'Migration', others: [{ number: 3, done: 1, total: 2 }],
  };

  const STEPS: AtelierNextStep[] = [
    {
      title: 'Basculer le DNS', why: 'étape 6 du plan', label: 'CONTINUER', request: 'Bascule le DNS.',
      scope: 'CHANTIER', stepIndex: 6, stepTitle: 'Basculer', chantier: 4, column: 'CHANTIER',
      blockedBy: ['étape 4', 'étape 5'],
      prerequisites: [
        { step: 2, title: 'Sauvegarder', risk: 'REVERSIBLE', level: 1 },
        { step: 3, title: 'Prévenir l’équipe', risk: 'EXTERNE', level: 1 },
        { step: 4, title: 'Migrer', risk: 'REVERSIBLE', level: 2 },
      ],
    },
    {
      title: 'Relancer Gino', why: 'attente ouverte', label: 'RISQUE', request: 'Relance Gino.',
      scope: 'CHANTIER', stepIndex: 7, stepTitle: 'Annoncer', chantier: 4, column: 'CHANTIER',
      blockedBy: ['attente feu-vert'],
    },
    {
      title: 'Mail au client', why: 'promis hier', label: 'LIVRER', request: 'Écris au client.',
      scope: 'APPROFONDISSEMENT', column: 'HORS',
    },
    {
      title: 'Ingress', why: 'chantier 3', label: 'VERIFIER', request: 'Vérifie l’ingress.',
      scope: 'CHANTIER', stepIndex: 2, stepTitle: 'Vérifier', chantier: 3, column: 'HORS',
    },
  ];

  let fixture: ComponentFixture<TerminalNextStepsComponent>;
  let launched: string[];
  let picked: string[];

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TerminalNextStepsComponent] });
    fixture = TestBed.createComponent(TerminalNextStepsComponent);
    launched = [];
    picked = [];
    fixture.componentInstance.launch.subscribe((r) => launched.push(r));
    fixture.componentInstance.pick.subscribe((r) => picked.push(r));
    fixture.componentRef.setInput('steps', STEPS);
    fixture.componentRef.setInput('chantier', chantier);
    fixture.componentRef.setInput('turnKey', 'm1');
    fixture.detectChanges();
  });

  const root = (): HTMLElement => fixture.nativeElement as HTMLElement;

  it('fonctions pures : badge, blocage, groupes, composition du lancement', () => {
    expect(nextStepBadge(STEPS[0], chantier)).toBe('Ch.4 · étape 6/14');
    expect(nextStepBadge(STEPS[3], chantier)).toBe('Ch.3 · étape 2/2');
    expect(nextStepBadge(STEPS[2], chantier)).toBeNull();
    expect(nextStepBadge(STEPS[0], null)).toBeNull();
    expect(nextStepBlockedText(STEPS[0])).toBe('bloquée : après les étapes 4, 5');
    expect(nextStepBlockedText(STEPS[1])).toBe('bloquée : attente feu-vert');
    expect(prerequisiteGroups(STEPS[0]).map((g) => g.map((p) => p.step))).toEqual([[2, 3], [4]]);
    expect(composeLaunch(STEPS[0], [2, 3, 4])).toBe('Chantier 4 — d’abord les prérequis, dans cet ordre — '
      + '1) en parallèle : étape 2 « Sauvegarder », étape 3 « Prévenir l’équipe » ; 2) étape 4 « Migrer ». '
      + 'Puis : Bascule le DNS.');
    expect(composeLaunch(STEPS[0], [4])).toBe('Chantier 4 — d’abord les prérequis, dans cet ordre — '
      + '1) étape 4 « Migrer ». Puis : Bascule le DNS.');
    expect(composeLaunch(STEPS[0], [])).toBe('Bascule le DNS.');
  });

  it('F-221 / SF-221-05 : dans un volet du split, une seule colonne — celle du chantier', () => {
    fixture.componentRef.setInput('chantierOnly', true);
    fixture.detectChanges();
    expect(root().querySelector('.cap__col--hors')).toBeNull();
    expect(root().querySelector('.cap__col--chantier .col__title')?.textContent).toContain('Chantier 4 — Migration');
    expect(root().querySelectorAll('.cap__col--chantier .opt').length).toBe(2);
  });

  it('deux colonnes : « Chantier 4 — Migration » avec sa mini-barre à gauche, « Hors chantier » à droite', () => {
    const left = root().querySelector('.cap__col--chantier') as HTMLElement;
    const right = root().querySelector('.cap__col--hors') as HTMLElement;
    expect(left.querySelector('.col__title')?.textContent).toContain('Chantier 4 — Migration');
    expect(left.querySelector('.col__count')?.textContent).toContain('5/14 faites');
    expect((left.querySelector('.col__fill') as HTMLElement).style.width).toBe('36%');
    expect(left.querySelectorAll('.opt').length).toBe(2);
    expect(right.querySelector('.col__title')?.textContent).toContain('Hors chantier');
    expect(right.querySelectorAll('.opt').length).toBe(2);
    expect(left.querySelector('.badge')?.textContent).toContain('Ch.4 · étape 6/14');
    expect(left.querySelector('.blocked')?.textContent).toContain('bloquée : après les étapes 4, 5');
    expect(right.querySelectorAll('.badge')[0]?.textContent).toContain('Ch.3 · étape 2/2');
    expect(right.querySelector('.switch')?.textContent).toContain('Afficher le chantier 3'); // F-226
    // Côte à côte au bureau (1440 px) : mesure réelle, composant attaché au document.
    document.body.appendChild(root());
    try {
      const l = left.getBoundingClientRect();
      const r = right.getBoundingClientRect();
      expect(l.width).toBeGreaterThan(100);
      expect(r.left).toBeGreaterThanOrEqual(l.right - 1);
      expect(Math.abs(r.top - l.top)).toBeLessThan(2);
    } finally {
      root().remove();
    }
  });

  it('touches 1 à 4 numérotées à travers les deux colonnes ; un clic remplit la saisie (inchangé)', () => {
    const keys = Array.from(root().querySelectorAll('.opt:not(.opt--other) .key')).map((k) => k.textContent?.trim());
    expect(keys).toEqual(['1', '2', '3', '4']);
    (root().querySelector('.cap__col--hors .opt') as HTMLButtonElement).click();
    expect(picked).toEqual(['Écris au client.']);
    expect(launched).toEqual([]);
  });

  it('prérequis cochés par défaut sauf EXTERNE ; groupes « 1 → 2 » ; « Lancer » envoie une demande', () => {
    const pre = root().querySelector('.pre') as HTMLElement;
    expect(pre.querySelector('.pre__head')?.textContent).toContain('Avant, dans l’ordre');
    expect(pre.querySelectorAll('.pre__lvl').length).toBe(2);
    const boxes = Array.from(pre.querySelectorAll<HTMLInputElement>('input[type="checkbox"]'));
    expect(boxes.map((b) => b.checked)).toEqual([true, false, true]);
    expect(pre.querySelector('.pre__item--ext .pre__ext')?.textContent).toContain('externe');
    (pre.querySelector('.pre__launch') as HTMLButtonElement).click();
    expect(launched).toEqual(['Chantier 4 — d’abord les prérequis, dans cet ordre — 1) étape 2 « Sauvegarder » ; '
      + '2) étape 4 « Migrer ». Puis : Bascule le DNS.']);
  });

  it('une étape EXTERNE cochée part au premier « Lancer », sans confirmation (F-218)', () => {
    const pre = () => root().querySelector('.pre') as HTMLElement;
    pre().querySelectorAll<HTMLInputElement>('input[type="checkbox"]')[1].click();
    fixture.detectChanges();
    (pre().querySelector('.pre__launch') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(pre().querySelector('[role="alertdialog"]')).toBeNull();
    expect(launched.length).toBe(1);
    expect(launched[0]).toContain('1) en parallèle : étape 2 « Sauvegarder », étape 3 « Prévenir l’équipe »');
  });

  it('sans colonnes (mode Libre) : une seule colonne de 4 au plus, sans badge', () => {
    fixture.componentRef.setInput('steps', [1, 2, 3, 4, 5].map((n) => ({
      title: `S${n}`, why: 'w', label: 'CONTINUER', request: `r${n}`,
    }) as AtelierNextStep));
    fixture.componentRef.setInput('chantier', null);
    fixture.detectChanges();
    expect(root().querySelector('.cap__cols')).toBeNull();
    expect(root().querySelectorAll('.opt:not(.opt--other)').length).toBe(4);
    expect(root().querySelector('.badge')).toBeNull();
  });

  it('colonne Hors vide : la colonne Chantier prend toute la largeur', () => {
    fixture.componentRef.setInput('steps', [STEPS[1]]);
    fixture.detectChanges();
    expect(root().querySelector('.cap__col--hors')).toBeNull();
    expect(root().querySelectorAll('.cap__col').length).toBe(1);
  });
});
